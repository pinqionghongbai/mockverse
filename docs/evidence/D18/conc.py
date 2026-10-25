"""D18 并发首轮：两个会话同时 start+answer，检验首轮加载是否只建一条 MCP 会话。

用法：PYTHONUTF8=1 python -I conc.py <out.txt>
"""
import json
import sys
import threading
import urllib.parse
import urllib.request

BASE = "http://localhost:8081/api/interview"
ANSWER = ("我觉得 String 不可变是因为 final，写个例子给你看看：\n\n"
          "public class Demo {\n"
          "    public static void main(String[] a) {\n"
          "        String s = \"abc\";\n"
          "        s.concat(\"def\");\n"
          "        System.out.println(s);\n"
          "    }\n"
          "}")

results = {}
lock = threading.Lock()


def post(url, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else b""
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=240) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run(tag):
    q = urllib.parse.urlencode({"userId": "0", "category": "JAVA_BASIC"})
    start = post(BASE + "/start?" + q)
    ans = post(BASE + "/%s/answer" % start["sessionId"], {"answer": ANSWER})
    with lock:
        results[tag] = ans["agentMessage"]


def main():
    threads = [threading.Thread(target=run, args=("A",)),
               threading.Thread(target=run, args=("B",))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    lines = []
    for tag in sorted(results):
        lines += ["=" * 70, "CASE: 并发首轮 " + tag, "-" * 70, results[tag], ""]
    open(sys.argv[1], "w", encoding="utf-8").write("\n".join(lines))
    print("done")


if __name__ == "__main__":
    main()
