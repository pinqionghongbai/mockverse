"""D20 异步判题端到端验证：提交立即返回 → SSE 收到结果 → 轮询兜底一致。

用法：PYTHONUTF8=1 python -I d20_async_judge.py
"""
import json
import threading
import time
import urllib.request

BASE = "http://localhost:8081/api/interview"
SESSION = 990001

FIB_OK = """import java.util.Scanner;
public class Fib {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long a = 1, b = 1;
        for (int i = 3; i <= n; i++) { long t = a + b; a = b; b = t; }
        System.out.println(n <= 2 ? 1 : b);
    }
}"""


def post_json(path, body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8")), (time.time() - t0) * 1000


def get_json(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


# 1) 先开 SSE 流，再提交
events = []


def listen():
    req = urllib.request.Request(BASE + "/%d/judge/stream" % SESSION)
    req.add_header("Accept", "text/event-stream")
    with urllib.request.urlopen(req, timeout=60) as r:
        for raw in r:
            line = raw.decode("utf-8").strip()
            if line.startswith("data:"):
                events.append(line[5:].strip())
                return


t = threading.Thread(target=listen, daemon=True)
t.start()
time.sleep(0.8)

# 2) 提交
sub, submit_ms = post_json("/%d/judge" % SESSION, {
    "code": FIB_OK, "stdin": "10", "expectedOutput": "55"})
print("[submit] %.1fms -> status=%s id=%s" % (submit_ms, sub["status"], sub["submissionId"]))

# 3) 等 SSE
t.join(timeout=25)
if events:
    evt = json.loads(events[0])
    r = evt.get("result") or {}
    print("[sse] status=%s compiled=%s matched=%s stdout=%r" % (
        evt.get("status"), r.get("compiled"), r.get("outputMatched"),
        (r.get("stdout") or "").strip()))
else:
    print("[sse] 超时未收到事件")

# 4) 轮询兜底
poll = get_json("/%d/judge/%s" % (SESSION, sub["submissionId"]))
print("[poll] status=%s matched=%s" % (poll["status"], (poll.get("result") or {}).get("outputMatched")))
