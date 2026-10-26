#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
D23 Nacos 服务注册 + 动态配置验证（只依赖标准库）。

用法（在 docs/evidence/D23 目录下）：
    PYTHONUTF8=1 python -I d23_nacos_check.py out.txt <service_log_path> [nacos_base]

示例：
    PYTHONUTF8=1 python -I d23_nacos_check.py d23_nacos_out.txt ../../../.d23-interview-service.log

前置：
    - Nacos 已起（默认 http://localhost:8848，认证关闭）
    - 四个服务已起，interview-service 的日志可读（脚本要 grep 一行"动态配置生效"）

检查项：
    1. 服务列表：interview / knowledge / resume / judge 四个都在
    2. 实例 IP：不是 198.18.x.x（代理 fake-ip 网段，见 NacosRegistrar.resolveIp）
    3. 动态配置：发布 max-rounds=3 → 日志出现 "max-rounds 5 -> 3" → 改回 5 → 日志出现 "3 -> 5"

为什么第 3 项读日志而不是读接口：max-rounds 生效后的可观察结果在
`interview_session.max_rounds` 列里（新建会话才写入），HTTP 响应体不带这个字段。
读日志是在"不引入 MySQL 驱动、不改对外契约"的前提下最直接的证据。
"""

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

OUT_PATH = sys.argv[1] if len(sys.argv) > 1 else "d23_nacos_out.txt"
LOG_PATH = sys.argv[2] if len(sys.argv) > 2 else "../../../.d23-interview-service.log"
NACOS = sys.argv[3] if len(sys.argv) > 3 else "http://localhost:8848"

SERVICES = ["interview-service", "knowledge-service", "resume-service", "judge-service"]
DATA_ID = "interview-service.yaml"
GROUP = "DEFAULT_GROUP"

OUT = open(OUT_PATH, "w", encoding="utf-8")


def log(*args):
    line = " ".join(str(a) for a in args)
    print(line)
    OUT.write(line + "\n")
    OUT.flush()


def get(url):
    with urllib.request.urlopen(url, timeout=10) as resp:
        return resp.read().decode("utf-8", "replace")


def publish(max_rounds):
    content = "mockverse:\n  interview:\n    max-rounds: %d\n" % max_rounds
    data = urllib.parse.urlencode({
        "dataId": DATA_ID, "group": GROUP, "content": content,
    }).encode("utf-8")
    req = urllib.request.Request(NACOS + "/nacos/v1/cs/configs", data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.read().decode("utf-8", "replace").strip()


def line_count():
    """当前日志行数。用作"只看发布之后新增的行"的基线。"""
    try:
        with open(LOG_PATH, encoding="utf-8", errors="replace") as f:
            return sum(1 for _ in f)
    except FileNotFoundError:
        return 0


def wait_log_after(pattern, offset, timeout=25):
    """轮询日志文件，只在**第 offset 行之后**找匹配。

    为什么必须带 offset：服务启动时会做一次"启动拉取"，如果 Nacos 上的现值与本地默认值
    不同，启动日志里就会出现一条 `max-rounds 5 -> 3`。不带基线地全文件搜索会直接命中
    这条旧记录，于是"发布→生效"的断言在**什么都没发生**的情况下也能通过——
    这是 D23 二查抓到的一个真问题：证据脚本比被测代码更容易骗人。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with open(LOG_PATH, encoding="utf-8", errors="replace") as f:
                hits = [ln.strip() for i, ln in enumerate(f, 1)
                        if i > offset and pattern in ln]
            if hits:
                return hits[-1]
        except FileNotFoundError:
            pass
        time.sleep(1)
    return None


def main():
    log("== D23 Nacos 验证 ==")
    log("nacos =", NACOS)
    log("log   =", LOG_PATH)

    # 1. 服务列表
    listing = json.loads(get(NACOS + "/nacos/v1/ns/service/list?pageNo=1&pageSize=50"))
    names = listing.get("doms", [])
    log("[1] 已注册服务:", names)
    missing = [s for s in SERVICES if s not in names]
    assert not missing, "未注册: %s" % missing

    # 2. 实例 IP（排除代理 fake-ip 网段）
    for svc in SERVICES:
        inst = json.loads(get(NACOS + "/nacos/v1/ns/instance/list?serviceName=" + svc))
        ips = [h["ip"] for h in inst.get("hosts", [])]
        healthy = [h.get("healthy") for h in inst.get("hosts", [])]
        log("[2] %-18s ip=%s healthy=%s" % (svc, ips, healthy))
        assert ips, "%s 没有实例" % svc
        for ip in ips:
            assert not ip.startswith("198.18.") and not ip.startswith("198.19."), \
                "%s 注册了 fake-ip 地址 %s（消费者连不通）" % (svc, ip)

    # 3. 动态配置：改小再改回
    # 每次发布前记下行数，之后只认新增的行（见 wait_log_after 的说明）
    base = line_count()
    log("[3] 发布 max-rounds=3 ->", publish(3))
    hit = wait_log_after("max-rounds 5 -> 3", base)
    log("    日志:", hit)
    assert hit, "配置变更后 25s 内没有出现新的 'max-rounds 5 -> 3'（只认发布之后新增的行）"

    base = line_count()
    log("[3] 发布 max-rounds=5 ->", publish(5))
    hit = wait_log_after("max-rounds 3 -> 5", base)
    log("    日志:", hit)
    assert hit, "恢复配置后 25s 内没有出现新的 'max-rounds 3 -> 5'（只认发布之后新增的行）"

    log("== 全部通过 ==")


if __name__ == "__main__":
    try:
        main()
    finally:
        OUT.close()
