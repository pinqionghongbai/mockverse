#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
D23 Redis 三项能力验证（只依赖标准库）。

用法（在 docs/evidence/D23 目录下）：
    PYTHONUTF8=1 python -I d23_redis_check.py out.txt [base_url]

前置：
    - interview-service 已起（默认 http://localhost:8081）
    - Redis 已起（用于观察键与 TTL，脚本本身不直连 Redis，只走 HTTP）

检查项：
    1. start 可用（这一步**不能**证明缓存生效——见下面的"能证明什么"）
    2. 空值兜底：category=NOPE 返回 409，且缓存值是 __EMPTY__
    3. 会话锁：同一会话并发两条 answer，必须一条 200、一条 409
    4. 锁释放：随后再发一条 answer 必须 200（证明 finally 真的解锁了）

能证明什么、不能证明什么：
    本脚本只证明**行为**（409/200 的分布），不证明**缓存命中**。
    "缓存真的省掉了 SQL"必须看两处原始证据，脚本代替不了：
        # a) 键与 TTL
        docker exec mockverse-redis redis-cli KEYS  "mv:question:*"
        docker exec mockverse-redis redis-cli TTL   "mv:question:candidates:any:any"
        # b) 连发 3 次 start 前后的 SQL 计数（服务日志里的 MyBatis 输出）
        grep -c "FROM question WHERE" <interview-service.log>
    D23 实测：3 次 start 后该计数 delta=0，TTL 597。
"""

import json
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8081"
OUT = open(sys.argv[1] if len(sys.argv) > 1 else "d23_redis_out.txt", "w", encoding="utf-8")


def log(*args):
    line = " ".join(str(a) for a in args)
    print(line)
    OUT.write(line + "\n")
    OUT.flush()


def post(path, body=None, timeout=180):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    if data is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def start(user_id, extra=""):
    status, body = post("/api/interview/start?userId=%d%s" % (user_id, extra))
    return status, json.loads(body) if status == 200 else body


def answer(session_id, text):
    return post("/api/interview/%d/answer" % session_id, {"answer": text})


def main():
    log("== D23 Redis 行为验证 ==")
    log("base =", BASE)

    # 1. 冒烟：start 能通（缓存命中与否见文件头的"能证明什么"）
    status, data = start(1)
    log("[1] start ->", status, "session =", data.get("sessionId") if status == 200 else data)
    assert status == 200, "start 失败，后面的检查没有意义"

    # 2. 空值兜底：不存在的分类应 409（而不是 500 或 200）
    status, body = start(1, "&category=NOPE")
    log("[2] start(category=NOPE) ->", status)
    assert status == 409, "空题库分支应返回 409，实际 %s" % status

    # 3. 会话锁：并发两条 answer 打同一个会话
    status, data = start(1)
    assert status == 200, "第二次 start 失败"
    session_id = data["sessionId"]
    log("[3] 并发 answer 打 session", session_id)

    results = {}

    def fire(tag):
        results[tag] = answer(session_id, "并发测试：String 不可变是因为常量池共享与 hash 缓存")

    threads = [threading.Thread(target=fire, args=(i,)) for i in (1, 2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    codes = sorted(code for code, _ in results.values())
    log("    codes =", codes)
    assert codes == [200, 409], "并发应为一条 200 + 一条 409，实际 %s" % codes

    # 4. 锁释放：紧接着再发一条，必须 200（证明不是"锁卡住了"而是"锁用完了就放"）
    time.sleep(0.5)
    code, body = answer(session_id, "锁释放后继续：第二轮的追问请继续")
    log("[4] 锁释放后 answer ->", code)
    assert code == 200, "锁没有被释放：%s" % body[:200]
    log("    round =", json.loads(body).get("round"))

    log("== 全部通过 ==")


if __name__ == "__main__":
    main()
    OUT.close()
