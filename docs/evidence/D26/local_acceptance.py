#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""D26 本地全套验收：一条命令回答"现在这套东西到底是不是完整的"。

用法（在仓库根目录）：
    PYTHONUTF8=1 python -I docs/evidence/D26/local_acceptance.py [out.txt]

为什么要有这个脚本：
    D24/D25 的验收结论散在终端输出和 journal 里，下次开工只能靠"上次好像跑通了"。
    这里把可自动判定的部分固定下来，**判据全部来自被测系统本身**（compose 配置、
    容器状态、健康端点、基础设施里真实存在的键/服务名/topic），不写死期望数字。

刻意不做的两件事：
    1. 不调 LLM 接口。那部分（四条链路）靠浏览器走查 + 截图，见 journal。
    2. 不把"容器数量"写死。D24 五轮审查踩过"文档写 9 实际 10"，所以数量由
       `docker compose config --services` 现算，只校验"配置里的服务都起来了且健康"。
"""

import json
import os
import subprocess
import sys
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = sys.argv[1] if len(sys.argv) > 1 else None

lines = []
failures = []


def say(msg):
    print(msg)
    lines.append(msg)


def run(cmd):
    """跑一条命令，返回 (returncode, stdout+stderr)。"""
    p = subprocess.run(cmd, cwd=ROOT, shell=isinstance(cmd, str),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def check(name, ok, detail):
    say(("[PASS] " if ok else "[FAIL] ") + name + " :: " + detail)
    if not ok:
        failures.append(name)


def get_json(url, timeout=10):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8")


# ---------- 1. compose 配置的服务 vs 实际容器 ----------

rc, out = run("docker compose config --services")
if rc != 0:
    say("docker compose config 失败，后续检查无意义：\n" + out)
    sys.exit(1)
expected = {s.strip() for s in out.splitlines() if s.strip()}

rc, out = run("docker compose ps --format json")
actual = {}
if rc == 0:
    for line in out.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        actual[item.get("Service", "")] = item

check("compose 服务全部存在", expected == set(actual),
      "配置 %d 个: %s | 运行 %d 个" % (len(expected), ",".join(sorted(expected)),
                                       len(actual)))
for svc in sorted(expected):
    item = actual.get(svc)
    if item is None:
        check("容器 " + svc, False, "未运行")
        continue
    state = (item.get("State") or "").lower()
    health = (item.get("Health") or "").lower()
    ok = state == "running" and health in ("healthy", "")
    check("容器 " + svc, ok, "state=%s health=%s" % (state, health or "-"))

# ---------- 2. 经 nginx 的健康端点 ----------

for svc, path, must in [
    ("interview", "/healthz/interview", '"database":{"connected":true'),
    ("knowledge", "/healthz/knowledge", '"available":true'),
    ("resume", "/healthz/resume", '"database":{"connected":true'),
    ("judge", "/healthz/judge", '"status":"UP"'),
]:
    try:
        status, body = get_json("http://localhost" + path)
        ok = status == 200 and must in body
        check("健康端点 " + svc, ok, "HTTP %d, 含 %s=%s" % (status, must, must in body))
    except Exception as e:  # noqa: BLE001 - 验收脚本要把任何异常都记成失败
        check("健康端点 " + svc, False, repr(e))

# ---------- 3. 基础设施里的真实痕迹 ----------

rc, out = run(["docker", "exec", "mockverse-redis", "redis-cli", "KEYS", "mv:*"])
keys = [k for k in out.split() if k.startswith("mv:")]
check("Redis 缓存键", rc == 0 and len(keys) > 0, "命中 %d 个 mv:* 键" % len(keys))

try:
    _, body = get_json("http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20")
    services = set(json.loads(body).get("doms") or [])
    want = {"interview-service", "knowledge-service", "resume-service", "judge-service"}
    check("Nacos 注册", want <= services, "已注册 %s" % ",".join(sorted(services)))
except Exception as e:  # noqa: BLE001
    check("Nacos 注册", False, repr(e))

rc, out = run(["docker", "exec", "mockverse-kafka",
               "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092", "--list"])
topics = set(out.split())
check("Kafka topic", {"judge-request", "judge-result"} <= topics,
      "存在 %s" % ",".join(sorted(t for t in topics if not t.startswith("__"))))

# ---------- 汇总 ----------

say("")
if failures:
    say("FAILED: %d 项 -> %s" % (len(failures), ", ".join(failures)))
else:
    say("ALL PASS: compose 服务 %d 个全部健康，四个健康端点、Redis/Nacos/Kafka 痕迹齐全"
        % len(expected))

if OUT:
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

sys.exit(1 if failures else 0)
