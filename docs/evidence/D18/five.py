"""D18 回归：HTTP 五轮面试（含第 1 轮带代码触发 judge_code）。

用法：PYTHONUTF8=1 python -I five.py <out.txt>
"""
import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://localhost:8081/api/interview"

ANSWERS = [
    "String 不可变是因为 final，我写个例子：\n\n"
    "public class Demo {\n"
    "    public static void main(String[] a) {\n"
    "        String s = \"abc\";\n"
    "        s.concat(\"def\");\n"
    "        System.out.println(s);\n"
    "    }\n"
    "}",
    "因为常量池要复用同一个对象，如果可变就会互相污染。",
    "hashCode 可以缓存，不用每次重算。",
    "作为 HashMap 的 key 时不会因为内容变化而找不到。",
    "线程安全，多线程共享不用加锁。",
]


def post(url, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else b""
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=240) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    q = urllib.parse.urlencode({"userId": "0", "category": "JAVA_BASIC"})
    start = post(BASE + "/start?" + q)
    lines = ["=" * 70, "CASE: HTTP 五轮回归", "sessionId: %s" % start["sessionId"],
             "开场: %s" % start["agentMessage"], "-" * 70]
    for i, ans in enumerate(ANSWERS, 1):
        t0 = time.time()
        r = post(BASE + "/%s/answer" % start["sessionId"], {"answer": ans})
        lines.append("[round %d] %.2fs finished=%s" % (r["round"], time.time() - t0, r["finished"]))
        lines.append(r["agentMessage"])
        lines.append("")
    open(sys.argv[1], "w", encoding="utf-8").write("\n".join(lines))
    print("done")


if __name__ == "__main__":
    main()
