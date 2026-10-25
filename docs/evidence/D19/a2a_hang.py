"""D19 四查发现的可复现问题：interview-service 的 A2A 端点路由到**进程内** interviewer_agent 时永不完成。

对照：
  - 同一端点、同一 Agent 树，路由到跨进程 knowledge_agent → completed（4.3~4.9s）
  - 同一 interviewer_agent，走 HTTP /api/interview/start → 正常（D19 五轮全绿）
  - 走 A2A → state 永远停在 working，artifacts 只有 transfer_to_agent 的 call/response

用法：PYTHONUTF8=1 python -I a2a_hang.py
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


def send(text, tag):
    t0 = time.time()
    d = rpc("message/send", {"message": {
        "kind": "message", "role": "user",
        "parts": [{"kind": "text", "text": text}],
        "messageId": "probe-" + tag}}, "send-" + tag)
    task = d.get("result", {})
    state = (task.get("status") or {}).get("state")
    kinds = []
    for a in (task.get("artifacts") or []):
        for p in (a.get("parts") or []):
            if p.get("kind") == "text":
                kinds.append("text")
            else:
                kinds.append((p.get("data") or {}).get("name"))
    return task.get("id"), state, kinds, time.time() - t0


def poll(task_id, seconds):
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        d = rpc("tasks/get", {"id": task_id}, "get")
        last = (d.get("result", {}).get("status") or {}).get("state")
        if last in ("completed", "failed", "canceled"):
            return last
        time.sleep(3)
    return last


for text, tag, expect in [
        ("开始一场 Java 后端面试", "iv", "interviewer_agent"),
        ("这是一个知识问题，请查知识库：Redis 缓存穿透怎么解决？", "kb", "knowledge_agent")]:
    tid, state, kinds, secs = send(text, tag)
    print("[%s] send %.1fs state=%s parts=%s task=%s" % (tag, secs, state, kinds, tid))
    final = poll(tid, 40)
    print("[%s] 40s 后 tasks/get → %s  （期望 %s）" % (tag, final, expect))
