"""D22：A2A `message/stream` 信封验证（D21 只验了 `message/send`）。

D19 记录过"两种信封都挂"，D21 修完后只验了 `message/send`。这里补上流式信封。

流式响应的报文形状与 `message/send` 不同（D22 实测）：
- `message/send` 回一个 **Task 快照**，artifact 在 `result.artifacts[]`
- `message/stream` 回一串事件：`status-update`（submitted/working/completed）
  与 `artifact-update`（artifact 在 `result.artifact.parts`，**不在** `result.artifacts`）
两种形状都要处理，否则会误判成"流里没有文本"。

用法：PYTHONUTF8=1 python -I a2a_stream_once.py [iv|kb]
"""
import json
import sys
import time
import urllib.request

BASE = "http://localhost:8081/a2a/v1"
KIND = sys.argv[1] if len(sys.argv) > 1 else "iv"
TEXTS = {
    "iv": "开始一场 Java 后端面试",
    "kb": "这是一个知识问题，请查知识库：Redis 缓存穿透怎么解决？",
}


def collect_parts(container):
    """从 artifact / artifact-update 里取 part 类型与文本长度。"""
    kinds, text_len = [], 0
    for p in (container or []):
        if p.get("kind") == "text":
            kinds.append("text")
            text_len += len(p.get("text") or "")
        else:
            kinds.append((p.get("data") or {}).get("name"))
    return kinds, text_len


def stream(text):
    body = json.dumps({"jsonrpc": "2.0", "id": "stream-1", "method": "message/stream", "params": {
        "message": {"kind": "message", "role": "user",
                    "parts": [{"kind": "text", "text": text}],
                    "messageId": "probe-stream-" + KIND}}}).encode()
    req = urllib.request.Request(BASE, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream")

    t0 = time.time()
    states, kinds, text_len, events = [], [], 0, []
    with urllib.request.urlopen(req, timeout=180) as r:
        for raw in r:
            line = raw.decode("utf-8").strip()
            if not line.startswith("data:"):
                continue
            try:
                evt = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            res = evt.get("result") or {}
            kind = res.get("kind")
            events.append(kind)

            status = (res.get("status") or {}).get("state")
            if status:
                states.append(status)

            if kind == "artifact-update":
                # 流式：artifact 在 result.artifact.parts
                k, n = collect_parts((res.get("artifact") or {}).get("parts"))
            else:
                # 非流式快照 / 兜底：artifact 在 result.artifacts[].parts
                k, n = [], 0
                for a in (res.get("artifacts") or []):
                    kk, nn = collect_parts(a.get("parts"))
                    k += kk
                    n += nn
            kinds += k
            text_len += n

            if status in ("completed", "failed", "canceled"):
                break
    return time.time() - t0, states, kinds, text_len, events


secs, states, kinds, text_len, events = stream(TEXTS[KIND])
print("[%s] stream %.1fs states=%s" % (KIND, secs, states))
print("[%s] event kinds=%s" % (KIND, sorted(set(events))))
print("[%s] parts=%s text_len=%d" % (KIND, kinds, text_len))
final = states[-1] if states else "NONE"
print("[%s] final=%s %s" % (KIND, final, "OK" if final == "completed" else "FAIL"))
