"""D19 验证：判题等待提示是不是"真"的——记录每个 SSE 事件的到达时刻。

判据：
  tool 事件到达 << 第一个 chunk 到达，且两者之差 ≈ judge_code 的耗时
  说明提示不是装饰，而是在工具真正跑起来之前就推给客户端了。

用法：PYTHONUTF8=1 python -I sse_timing.py <sessionId> <answer.json> <out.txt>
"""
import json
import sys
import time
import urllib.request

sid = sys.argv[1]
body = open(sys.argv[2], "rb").read()
req = urllib.request.Request(
    "http://localhost:8081/api/interview/%s/answer/stream" % sid,
    data=body, method="POST")
req.add_header("Content-Type", "application/json; charset=utf-8")

t0 = time.time()
rows = []
seen_chunk = False
with urllib.request.urlopen(req, timeout=300) as resp:
    for raw in resp:
        line = raw.decode("utf-8").strip()
        if not line.startswith("data:"):
            continue
        evt = json.loads(line[5:])
        ms = int((time.time() - t0) * 1000)
        if "tool" in evt:
            rows.append("%6d ms  TOOL        %s" % (ms, evt["tool"]))
        elif "delta" in evt:
            if not seen_chunk:
                seen_chunk = True
                rows.append("%6d ms  CHUNK(first) %s" % (ms, evt["delta"]))
        else:
            rows.append("%6d ms  DONE        %s" % (ms, json.dumps(evt, ensure_ascii=False)))

open(sys.argv[3], "w", encoding="utf-8").write("\n".join(rows) + "\n")
print("done")
