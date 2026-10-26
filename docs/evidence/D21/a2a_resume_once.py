"""D21 全链路：A2A 跨进程路由到 resume_agent（8083）。

用法：PYTHONUTF8=1 python -I a2a_resume_once.py
"""
import json
import time
import urllib.request

BASE = "http://localhost:8081/a2a/v1"
RESUME = "这是简历诊断问题，请诊断这份简历：\n张三，3 年 Java 后端，熟悉 Spring Boot 与 MySQL。"


def rpc(method, params, rid="probe"):
    body = json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}).encode()
    req = urllib.request.Request(BASE, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read().decode("utf-8"))


t0 = time.time()
d = rpc("message/send", {"message": {
    "kind": "message", "role": "user",
    "parts": [{"kind": "text", "text": RESUME}],
    "messageId": "probe-resume-once"}}, "send-resume")
task = d.get("result", {})
state = (task.get("status") or {}).get("state")
kinds = []
for a in (task.get("artifacts") or []):
    for p in (a.get("parts") or []):
        kinds.append("text" if p.get("kind") == "text" else (p.get("data") or {}).get("name"))
print("[resume] send %.1fs state=%s parts=%s" % (time.time() - t0, state, kinds))
