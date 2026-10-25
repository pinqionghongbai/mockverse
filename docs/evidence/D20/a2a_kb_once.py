"""D20 取证对照：同一条 A2A 通道发 knowledge 请求（跨进程），观察 SDK 消费生命周期。

用法：PYTHONUTF8=1 python -I a2a_kb_once.py
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
    "parts": [{"kind": "text", "text": "这是一个知识问题，请查知识库：Redis 缓存穿透怎么解决？"}],
    "messageId": "probe-kb-once"}}, "send-kb")
task = d.get("result", {})
state = (task.get("status") or {}).get("state")
kinds = []
for a in (task.get("artifacts") or []):
    for p in (a.get("parts") or []):
        kinds.append("text" if p.get("kind") == "text" else (p.get("data") or {}).get("name"))
print("[kb] send %.1fs state=%s parts=%s task=%s" % (time.time() - t0, state, kinds, task.get("id")))
