#!/usr/bin/env bash
# Milestone-1 native smoke test:
#   prove the pinned LFM2.5 GGUF loads + answers on the pinned llama.cpp revision.
# Run on CI (ubuntu-latest). Exits non-zero on any failed assertion.
set -euo pipefail

# llama.cpp revision this binding is pinned to.
LLAMA_REF="${LLAMA_REF:-358a420ec4ed59b6b0be08b1d1f71dd478b99a99}"

# Model under test. Defaults to the bundled LFM2.5-350M; override via env to smoke
# a candidate model (e.g. Qwen3.5-0.8B) without editing this file.
LABEL="${LABEL:-lfm}"
MODEL_NAME="${MODEL_NAME:-LFM2.5-350M-Q4_K_M.gguf}"
MODEL_URL="${MODEL_URL:-https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/${MODEL_NAME}}"
MODEL_SHA256="${MODEL_SHA256:-7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4}"
EXPECTED_BYTES="${EXPECTED_BYTES:-229312224}"
# Extra comma-separated substrings that must never leak into a spoken answer
# (e.g. reasoning/vision template artifacts for a specific model family).
EXTRA_BANNED="${EXTRA_BANNED:-}"

HERE="$(cd "$(dirname "$0")" && pwd)"
AI_CHAT="$HERE/../llama/src/main/cpp/ai_chat.cpp"

echo "::group::static guard: no writes to a borrowed batch.logits"
if [ -f "$AI_CHAT" ]; then
  # Ignore // comment lines so prose about the bug does not trip the guard.
  CODE="$(grep -vE '^[[:space:]]*//' "$AI_CHAT")"
  # decode_tokens used get_one() (logits == nullptr) and wrote batch.logits[n-1]
  # -> SEGV at 0x43. Fail the build if that anti-pattern ever comes back.
  if printf '%s\n' "$CODE" | grep -nE '\.logits\[[^]]*\] *='; then
    echo "FAIL: write to batch.logits -- get_one leaves it nullptr (this was the SIGSEGV)"
    exit 1
  fi
  # llama_batch_free only applies to llama_batch_init batches. If the code frees
  # batches but never inits any, it is freeing a borrowed get_one batch.
  if printf '%s\n' "$CODE" | grep -q 'llama_batch_free' \
     && ! printf '%s\n' "$CODE" | grep -q 'llama_batch_init'; then
    echo "FAIL: llama_batch_free without llama_batch_init (freeing a borrowed batch)"
    exit 1
  fi
  echo "ok: $AI_CHAT"
else
  echo "skip: $AI_CHAT not present"
fi
echo "::endgroup::"

WORK="${TMPDIR:-/tmp}/broexe-smoke"
rm -rf "$WORK"
mkdir -p "$WORK/artifacts"
cd "$WORK"

echo "::group::pin llama.cpp"
git clone -q --no-checkout --filter=blob:none https://github.com/ggml-org/llama.cpp src
git -C src fetch -q --depth 1 origin "$LLAMA_REF"
git -C src checkout -q "$LLAMA_REF"
echo "pinned $LLAMA_REF"
echo "::endgroup::"

echo "::group::download + verify model"
curl -sSL --retry 3 -o "artifacts/$MODEL_NAME" "$MODEL_URL"
ACTUAL_BYTES="$(stat -c%s "artifacts/$MODEL_NAME")"
ACTUAL_SHA="$(sha256sum "artifacts/$MODEL_NAME" | awk '{print $1}')"
echo "bytes=$ACTUAL_BYTES (expected $EXPECTED_BYTES)"
echo "sha256=$ACTUAL_SHA"
[ "$ACTUAL_BYTES" = "$EXPECTED_BYTES" ] || { echo "FAIL size mismatch"; exit 1; }
[ "$ACTUAL_SHA"   = "$MODEL_SHA256"   ] || { echo "FAIL sha256 mismatch"; exit 1; }
echo "::endgroup::"

echo "::group::build llama.cpp (host, shared)"
cmake -S src -B build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
      -DGGML_NATIVE=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF \
      -DLLAMA_BUILD_TOOLS=OFF -DGGML_OPENMP=OFF >/dev/null
cmake --build build --target llama -j2 >/dev/null
# Locate the built library wherever cmake decided to place it.
LIBDIR="$(dirname "$(find build -name 'libllama.so' 2>/dev/null | head -1)")"
if [ -z "$LIBDIR" ]; then
  echo "FAIL: libllama.so not found under build/"
  find build -name 'libllama*' -maxdepth 4
  exit 1
fi
echo "libllama at: $LIBDIR"
g++ -std=c++17 -O2 "$HERE/llm_smoke.cpp" \
    -Isrc/include -Isrc/ggml/include -L"$LIBDIR" -lllama \
    -Wl,-rpath,"$LIBDIR" \
    -o llm_smoke
echo "::endgroup::"

echo "::group::batch ownership + decode regression (fault 0x43)"
# Guards the llama_batch_get_one contract that decode_tokens/benchModel/
# generateNextToken depend on: logits is nullptr (never write it) and the batch
# borrows the token buffer (never llama_batch_free it). Also exercises decode at
# 1/68/511/512/513 tokens, repeats, generation and a benchmark-style pass.
g++ -std=c++17 -O2 "$HERE/llama_batch_regression.cpp" \
    -Isrc/include -Isrc/ggml/include -L"$LIBDIR" -lllama \
    -Wl,-rpath,"$LIBDIR" \
    -o llama_batch_regression
./llama_batch_regression "artifacts/$MODEL_NAME"
echo "::endgroup::"

echo "::group::run generation matrix"
for pmode in tmpl_auto tmpl_special bos_special; do
  for smode in greedy temp; do
    key="${pmode}__${smode}"
    ./llm_smoke "artifacts/$MODEL_NAME" 96 "out_${LABEL}_${key}.json" "$pmode" "$smode" >/dev/null || true
  done
done
for f in out_*.json; do
  echo "--- $f ---"
  cat "$f"
  echo
done
echo "::endgroup::"

python3 - "$PWD" "$LABEL" "$EXTRA_BANNED" <<'PY'
import json, os, sys
work, label, extra = sys.argv[1], sys.argv[2], sys.argv[3]
banned = ["<|im_start|>", "<|im_end|>", "<|startoftext|>", "thought", "tool_call",
          "```", "To use a tool", "human", "<tool", "response is"]
banned += [t for t in (s.strip().lower() for s in extra.split(",")) if t]
def check(path):
    r = json.load(open(path))
    if r.get("gen_tokens", 0) < 4:
        return ["fewer than 4 generated tokens"], r
    txt = r["text"]
    low = txt.lower()
    fails = []
    if len(txt) < 15:
        fails.append(f"answer too short: {len(txt)} chars")
    # a clean spoken answer must not contain template/role or reasoning artifacts
    for b in banned:
        if b in low:
            fails.append(f"artifact leaked: {b!r}")
    if r["first_token_ms"] < 0:
        fails.append("no first token recorded")
    return fails, r

passed, report = [], []
for pmode in ("tmpl_auto", "tmpl_special", "bos_special"):
    for smode in ("greedy", "temp"):
        key = f"{pmode}__{smode}"
        path = os.path.join(work, f"out_{label}_{key}.json")
        fails, r = check(path)
        if fails:
            report.append(f"{key}: FAIL -> " + "; ".join(fails))
        else:
            report.append(f"{key}: PASS -> text={r['text'][:90]!r} "
                          f"first_token_ms={r['first_token_ms']} total_ms={r['total_ms']}")
            passed.append(key)

for line in report:
    print("SMOKE " + line)

if not passed:
    print("SMOKE FAIL: no prompt x sampler combo produced a clean answer")
    for pmode in ("tmpl_auto", "tmpl_special", "bos_special"):
        for smode in ("greedy", "temp"):
            p = os.path.join(work, f"out_{label}_{pmode}__{smode}.json")
            if os.path.exists(p):
                r = json.load(open(p))
                print(f"trace {pmode}/{smode}: rendered={r['rendered_prompt']!r} text={r['text']!r}")
    sys.exit(1)
print(f"SMOKE PASS (winning combos: {', '.join(passed)})")
PY