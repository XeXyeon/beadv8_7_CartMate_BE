import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from itertools import combinations
from pathlib import Path

HERE = Path(__file__).resolve().parent
API = "https://api.openai.com/v1"
EMBED_MODEL = os.environ.get("EMBED_MODEL", "text-embedding-3-small")
LABEL_MODELS = os.environ.get("LABEL_MODELS", "gpt-4.1-mini,gpt-5-mini,gpt-5-nano").split(",")
KEY_FILE = Path.home() / ".config" / "cartmate" / "openai.env"
EMBED_CACHE = HERE / "embeddings-cache.json"
LABEL_CACHE = HERE / "labels-cache.json"
PROMPT_VERSION = "v3"

LABEL_PROMPT = """각 상품의 '상품 종류'를 공동구매로 묶을 수 있는 수준의 짧은 한국어 명사구로 써라.
브랜드, 모델명, 크기, 용량, 연령(성인/아동), 색상, 홍보 문구는 빼라.
단, 구단 · 국가대표 유니폼은 구단 · 팀 이름을 남겨라(예: "레알 마드리드 유니폼").
풋살공과 축구공은 다른 종류다. 축구화는 바닥 종류(FG · TF · AG · HG · IC)와 상관없이 "축구화"로 쓴다. 풋살화 · 터프화 · 실내화도 "축구화"로 쓴다.
labels에 상품마다 하나씩, 상품 번호(no)와 종류(type)를 써라.
상품:
%s"""

LABEL_SCHEMA = {
    "type": "json_schema",
    "json_schema": {
        "name": "product_types",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {"labels": {"type": "array", "items": {
                "type": "object",
                "properties": {"no": {"type": "integer"}, "type": {"type": "string"}},
                "required": ["no", "type"],
                "additionalProperties": False,
            }}},
            "required": ["labels"],
            "additionalProperties": False,
        },
    },
}


def api_key():
    if os.environ.get("OPENAI_API_KEY"):
        return os.environ["OPENAI_API_KEY"].strip()
    if KEY_FILE.exists():
        for line in KEY_FILE.read_text().splitlines():
            if line.startswith("OPENAI_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    sys.exit(f"OPENAI_API_KEY not set (env or {KEY_FILE})")


def post(path, body):
    req = urllib.request.Request(API + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key()}"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=180) as res:
                return json.load(res)
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503) or attempt == 2:
                sys.exit(f"{path} failed: {e.code} {e.read().decode(errors='replace')[:500]}")
            print(f"{path} {e.code}, retrying in 20s", file=sys.stderr)
            time.sleep(20)


def label_body(model, names):
    listing = "\n".join(f"{i + 1}. {n}" for i, n in enumerate(names))
    body = {"model": model, "messages": [{"role": "user", "content": LABEL_PROMPT % listing}],
            "response_format": LABEL_SCHEMA}
    if model.startswith("gpt-5"):
        body["reasoning_effort"] = "minimal"
    else:
        body["temperature"] = 0
    return body


def labels(model, names):
    cache = json.loads(LABEL_CACHE.read_text()) if LABEL_CACHE.exists() else {}
    key = PROMPT_VERSION + "\t" + model + "\t" + "\n".join(names)
    if key in cache:
        return cache[key]
    started = time.time()
    res = post("/chat/completions", label_body(model, names))
    by_no = {item["no"]: item["type"] for item in json.loads(res["choices"][0]["message"]["content"])["labels"]}
    missing = [i for i in range(1, len(names) + 1) if i not in by_no]
    if missing:
        sys.exit(f"{model} missed products {missing}")
    out = [by_no[i] for i in range(1, len(names) + 1)]
    usage = res.get("usage", {})
    entry = {"labels": out, "seconds": round(time.time() - started, 1),
             "input_tokens": usage.get("prompt_tokens"), "output_tokens": usage.get("completion_tokens"),
             "reasoning_tokens": (usage.get("completion_tokens_details") or {}).get("reasoning_tokens")}
    cache[key] = entry
    LABEL_CACHE.write_text(json.dumps(cache, ensure_ascii=False))
    print(f"labeled {len(names)} names ({model}, {entry['seconds']}s)", file=sys.stderr)
    return entry


def embed(texts):
    cache = json.loads(EMBED_CACHE.read_text()) if EMBED_CACHE.exists() else {}
    missing = [t for t in dict.fromkeys(texts) if f"{EMBED_MODEL}\t{t}" not in cache]
    if missing:
        res = post("/embeddings", {"model": EMBED_MODEL, "input": missing})
        data = sorted(res["data"], key=lambda d: d["index"])
        if len(data) != len(missing):
            sys.exit("embedding count mismatch")
        for t, d in zip(missing, data):
            cache[f"{EMBED_MODEL}\t{t}"] = d["embedding"]
        EMBED_CACHE.write_text(json.dumps(cache))
        print(f"embedded {len(missing)} texts ({EMBED_MODEL})", file=sys.stderr)
    return {t: cache[f"{EMBED_MODEL}\t{t}"] for t in texts}


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def scores(products, texts, vectors):
    rows = []
    for p, q in combinations(products, 2):
        if p["categoryId"] != q["categoryId"]:
            continue
        rows.append((cosine(vectors[texts[p["id"]]], vectors[texts[q["id"]]]), p["group"] == q["group"], p, q))
    return rows


def metrics(rows, t):
    tp = sum(1 for s, pos, *_ in rows if s >= t and pos)
    fp = sum(1 for s, pos, *_ in rows if s >= t and not pos)
    fn = sum(1 for s, pos, *_ in rows if s < t and pos)
    prec = tp / (tp + fp) if tp + fp else 1.0
    rec = tp / (tp + fn) if tp + fn else 1.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    return prec, rec, f1, tp, fp, fn


def errors(rows, t, texts):
    line = lambda s, p, q: f"- {s:.3f} {p['name']} ↔ {q['name']} (`{texts[p['id']]}` ↔ `{texts[q['id']]}`)"
    out = ["", "FP(잘못 묶음):", ""]
    out += [line(s, p, q) for s, pos, p, q in sorted(rows, key=lambda r: -r[0]) if s >= t and not pos] or ["- 없음"]
    out += ["", "FN(놓침):", ""]
    out += [line(s, p, q) for s, pos, p, q in sorted(rows, key=lambda r: r[0]) if s < t and pos] or ["- 없음"]
    return out


def main():
    products = json.loads((HERE / "products.json").read_text())
    names = [p["name"] for p in products]
    out = [f"# 유사도 기준값 측정 (임베딩 {EMBED_MODEL}, LLM 종류 라벨, 같은 카테고리 사전 필터)", "",
           f"상품 {len(products)}개, 같은 카테고리 쌍만 비교. 정답 = 같은 group. 라벨 모델당 1회.", ""]
    runs = {}
    for model in LABEL_MODELS:
        entry = labels(model, names)
        runs[model] = (entry, {p["id"]: label.strip() or p["name"] for p, label in zip(products, entry["labels"])})
    vectors = embed([t for _, texts in runs.values() for t in texts.values()])
    grid = [round(0.40 + i * 0.01, 2) for i in range(59)]
    summary = ["| 라벨 모델 | 최고 F1 @ 기준값 | 정밀도 · 재현율 | F1 @ 0.90 | 라벨 시간 | 입력 · 출력(추론) 토큰 |",
               "|---|---|---|---|---|---|"]
    detail = []
    for model, (entry, texts) in runs.items():
        rows = scores(products, texts, vectors)
        results = [(t, *metrics(rows, t)) for t in grid]
        top = max(results, key=lambda r: (r[3], r[1]))
        at90 = next(r for r in results if r[0] == 0.90)
        summary.append(f"| {model} | {top[3]:.3f} @ {top[0]:.2f} | {top[1]:.3f} · {top[2]:.3f} | {at90[3]:.3f} "
                       f"| {entry['seconds']}s | {entry['input_tokens']} · {entry['output_tokens']}"
                       f"({entry['reasoning_tokens'] or 0}) |")
        detail += [f"## {model}", "", f"쌍 {len(rows)}개(정답 {sum(1 for r in rows if r[1])}개).", "",
                   "| 기준값 | 정밀도 | 재현율 | F1 | TP | FP | FN |", "|---|---|---|---|---|---|---|"]
        detail += [f"| {t:.2f} | {p:.3f} | {r:.3f} | {f:.3f} | {tp} | {fp} | {fn} |"
                   for t, p, r, f, tp, fp, fn in results if round(t * 100) % 2 == 0]
        detail += [""] + [f"0.90 기준 오답:"] + errors(rows, 0.90, texts) + [""]
    out += summary + [""] + detail
    (HERE / "results.md").write_text("\n".join(out) + "\n")
    print("\n".join(summary))


if __name__ == "__main__":
    main()
