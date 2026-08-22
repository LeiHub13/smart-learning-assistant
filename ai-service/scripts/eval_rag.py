"""RAG 评估脚本：读取 JSONL 测试集，调用 ai-service /ai/complete，
检查答案中是否命中期望关键词。"""
import argparse
import json
import urllib.request


def complete(base_url, question, chunks):
    req = urllib.request.Request(
        f"{base_url}/ai/complete",
        data=json.dumps({"scene": "rag_qa", "question": question, "chunks": chunks}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))["content"]


def evaluate(test_path, base_url):
    total = 0
    hit = 0
    with open(test_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            item = json.loads(line)
            total += 1
            answer = complete(base_url, item["question"], item.get("chunks", []))
            keywords = item.get("keywords", [])
            ok = all(kw in answer for kw in keywords)
            if ok:
                hit += 1
            print(f"[{('OK' if ok else 'FAIL')}] Q: {item['question'][:40]}...")
    print(f"\n总题数: {total}, 命中: {hit}, 命中率: {hit / total * 100:.1f}%")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", default="scripts/eval_rag.jsonl")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    evaluate(args.test, args.base_url)
