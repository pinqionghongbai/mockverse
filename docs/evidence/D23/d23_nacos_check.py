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


def wait_log(pattern, timeout=20):
    """轮询日志文件，等某一行出现。返回命中的那一行（或 None）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with open(LOG_PATH, encoding="utf-8", errors="replace") as f:
                hits = [ln.strip() for ln in f if pattern in ln]
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
    log("[3] 发布 max-rounds=3 ->", publish(3))
    hit = wait_log("max-rounds 5 -> 3")
    log("    日志:", hit)
    assert hit, "配置变更后 20s 内日志没有出现 'max-rounds 5 -> 3'"

    log("[3] 发布 max-rounds=5 ->", publish(5))
    hit = wait_log("max-rounds 3 -> 5")
    log("    日志:", hit)
    assert hit, "恢复配置后 20s 内日志没有出现 'max-rounds 3 -> 5'"

    log("== 全部通过 ==")


if __name__ == "__main__":
    try:
        main()
    finally:
        OUT.close()
