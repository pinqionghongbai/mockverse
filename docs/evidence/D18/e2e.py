"""D18 端到端验证：起会话 → 发一条带代码的回答 → 打印面试官回复。

只依赖标准库。输出写文件（GBK 控制台打印中文会炸）。
用法：PYTHONUTF8=1 python -I e2e.py <case-name> <answer.json> <out.txt>
"""
import json
import sys
import urllib.parse
import urllib.request

BASE = "http://localhost:8081/api/interview"


def post(url, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else b""
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    name, answer_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    answer = json.load(open(answer_path, encoding="utf-8"))["answer"]

    query = urllib.parse.urlencode({"userId": "0", "category": "JAVA_BASIC"})
    start = post(BASE + "/start?" + query)
    ans = post(BASE + "/%s/answer" % start["sessionId"], {"answer": answer})

    lines = [
        "=" * 70,
        "CASE: " + name,
        "sessionId: %s" % start["sessionId"],
        "question : %s" % start["question"],
        "-" * 70,
        ans["agentMessage"],
        "",
    ]
    open(out_path, "w", encoding="utf-8").write("\n".join(lines))
    print("done", start["sessionId"])


if __name__ == "__main__":
    main()
