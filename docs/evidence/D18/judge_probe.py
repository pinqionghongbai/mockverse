"""D18 判题验证脚本：直接对 8084 的 MCP 端点发 tools/call。

只依赖标准库。响应是 Streamable HTTP 的 SSE 形态（event: message / data: {...}），
这里按行取最后一条 data 解析。
"""
import json
import sys
import urllib.request

BASE = "http://localhost:8084/mcp"


def rpc(payload, session=None):
    data = json.dumps(payload).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": "2025-06-18",
    }
    if session:
        headers["Mcp-Session-Id"] = session
    req = urllib.request.Request(BASE, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=120) as resp:
        sid = resp.headers.get("Mcp-Session-Id")
        body = resp.read().decode("utf-8")
    return sid, body


def parse(body):
    payloads = []
    for line in body.splitlines():
        if line.startswith("data:"):
            payloads.append(json.loads(line[5:].strip()))
    if payloads:
        return payloads[-1]
    return json.loads(body)


def main():
    sid, _ = rpc({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "d18-verify", "version": "1.0"}},
    })
    rpc({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)

    cases = json.load(open(sys.argv[1], encoding="utf-8"))
    out = open(sys.argv[2], "w", encoding="utf-8") if len(sys.argv) > 2 else sys.stdout
    for case in cases:
        _, body = rpc({"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                       "params": {"name": "judge_code", "arguments": case["args"]}}, sid)
        result = parse(body)["result"]
        text = "".join(c.get("text", "") for c in result.get("content", []))
        print("=" * 70, file=out)
        print("CASE:", case["name"], "| isError =", result.get("isError"), file=out)
        try:
            print(json.dumps(json.loads(text), ensure_ascii=False, indent=1), file=out)
        except json.JSONDecodeError:
            print(text, file=out)
    if out is not sys.stdout:
        out.close()


if __name__ == "__main__":
    main()
