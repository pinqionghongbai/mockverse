#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
D24 SSE 时序验证：证明流式是"逐字到达"，不是被 nginx 缓冲后一次性吐出。

用法（在 docs/evidence/D24 目录下）：
    PYTHONUTF8=1 python -I sse_timing.py <sessionId> [base_url]

示例：
    PYTHONUTF8=1 python -I sse_timing.py 2114694600629600257 http://localhost
    PYTHONUTF8=1 python -I sse_timing.py 2114694600629600257 http://localhost:8081

前置：
    会话已通过 POST /api/interview/start 创建且未结束。

为什么不能只看"收到了 N 个 chunk"：
    一次性吐出也是 N 个 chunk。判据是**到达时刻的分布**——
    若被缓冲，所有 chunk 会挤在最后很短的时间窗内到达。

判据（D23/D24 实测基准）：
    - 直连 8081: 52 chunk / spread 0.33s / 最后 0.1s 到达 27%  → 逐字
    - 经 nginx : 53 chunk / spread 0.45s / 最后 0.1s 到达 19%  → 逐字
    - 若被缓冲:  spread 趋近 0 且最后 0.1s 内到达 > 80%
"""

import json
import sys
import time
import urllib.request

sid = sys.argv[1] if len(sys.argv) > 1 else None
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost"
if not sid:
    print("用法: python sse_timing.py <sessionId> [base_url]")
    sys.exit(2)

body = json.dumps(
    {"answer": "不可变是因为常量池可以共享、hash 可缓存、线程安全"},
    ensure_ascii=False,
).encode("utf-8")

req = urllib.request.Request(f"{base}/api/interview/{sid}/answer/stream", data=body, method="POST")
req.add_header("Content-Type", "application/json; charset=utf-8")

t0 = time.time()
stamps = []
with urllib.request.urlopen(req, timeout=120) as r:
    for line in r:
        if line.startswith(b"data:"):
            stamps.append(time.time() - t0)

print(f"base={base}")
print(f"chunks={len(stamps)} total={stamps[-1]:.2f}s")
if len(stamps) > 2:
    spread = stamps[-1] - stamps[0]
    tail = sum(1 for s in stamps if s > stamps[-1] - 0.1)
    verdict = "★ 被缓冲了" if tail > len(stamps) * 0.8 else "逐字到达 OK"
    print(f"first={stamps[0]:.2f}s last={stamps[-1]:.2f}s spread={spread:.2f}s")
    print(f"最后 0.1s 内到达 {tail}/{len(stamps)} -> {verdict}")
