"""D20 取证：单发一条 A2A 请求到 8081，路由到进程内 interviewer_agent，只观察不轮询。

用法：PYTHONUTF8=1 python -I a2a_iv_once.py
"""
import json
import time
import urllib.request

BASE = "http://localhost:8081/a2a/v1"


def rpc(method, params, rid="probe"):
    body = json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}).encode()
    req = urllib.request.Request(BASE, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


t0 = time.time()
d = rpc("message/send", {"message": {
    "kind": "message", "role": "user",
    "parts": [{"kind": "text", "text": "开始一场 Java 后端面试"}],
    "messageId": "probe-iv-once"}}, "send-iv")
task = d.get("result", {})
state = (task.get("status") or {}).get("state")
kinds = []
for a in (task.get("artifacts") or []):
    for p in (a.get("parts") or []):
        kinds.append("text" if p.get("kind") == "text" else (p.get("data") or {}).get("name"))
print("[iv] send %.1fs state=%s parts=%s task=%s" % (time.time() - t0, state, kinds, task.get("id")))

tid = task.get("id")
for i in range(6):
    time.sleep(5)
    d = rpc("tasks/get", {"id": tid}, "get")
    st = (d.get("result", {}).get("status") or {}).get("state")
    print("[iv] +%ds tasks/get -> %s" % ((i + 1) * 5, st))
    if st in ("completed", "failed", "canceled"):
        break
