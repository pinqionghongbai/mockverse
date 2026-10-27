"""D26 实验：中间层（Cloudflare Tunnel）对 SSE 的转发行为。

**为什么需要这个脚本**：D26 在穿透下遇到"判题结果流 24 秒收到 0 字节，但服务端
推送成功、本地立刻收到"。要排除"我们代码的问题"，就得有一个**不依赖 MockVerse**
的最小 SSE 服务，单独挂一条隧道观察。

只回答一个问题：**响应体小、且之后长时间不再发数据时，中间层会不会把已发出的内容压住。**

四个端点，差别只在"发数据的节奏"：
    /small   首帧 12 字节，3s 后发一条，再静默 30s
    /big     首帧 4096 字节，3s 后发一条，再静默 30s（排除"体积阈值"假设）
    /burst   1s 内密集发 10 条，再静默 15s（排除"初始突发就够了"假设）
    /stream  每 200ms 发一条，共 20 条（对照组：持续有数据）

用法（两个终端）：
    PYTHONUTF8=1 python -I sse_probe_server.py 8099
    cloudflared tunnel --url http://localhost:8099
    # 然后：curl -sN --max-time 45 <隧道地址>/small

D26 实测结论（2026-11-16）：
    /stream → 立刻逐条到达
    /small  → 33.8s 后一次性到达
    /big    → 同样被压住（与体积无关）
    /burst  → 17s 后一次性到达（初始突发不够）
    → 中间层压的是"长时间静默的流"，不是"小的流"。
"""
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音，别把请求日志混进观察结果
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def chunk(payload: bytes):
            self.wfile.write(b"%x\r\n" % len(payload) + payload + b"\r\n")
            self.wfile.flush()

        if self.path == "/small":
            chunk(b":connected\n\n")
            time.sleep(3)
            chunk(b"data:{\"n\":1}\n\n")
            time.sleep(30)
        elif self.path == "/big":
            chunk(b":" + b"x" * 4096 + b"\n\n")
            time.sleep(3)
            chunk(b"data:{\"n\":1}\n\n")
            time.sleep(30)
        elif self.path == "/burst":
            # 先密集发 1 秒，再长时间静默——区分"初始突发"与"持续流动"
            for i in range(10):
                chunk(b"data:{\"n\":%d}\n\n" % i)
                time.sleep(0.1)
            time.sleep(15)
        elif self.path == "/stream":
            for i in range(20):
                chunk(b"data:{\"n\":%d}\n\n" % i)
                time.sleep(0.2)
        else:
            chunk(b":unknown\n\n")
        # 结束响应
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
