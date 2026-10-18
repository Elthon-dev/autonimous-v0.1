#!/usr/bin/env bash
# ASR accuracy smoke test (CI, ubuntu-latest).
#
# Evidence for the Parakeet-ASR upgrade: pull the SAME int8 transducer that ships
# in the APK (sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8, offline `nemo_transducer`
# engine), transcribe the model's own reference clip (test_wavs/0.wav, bundled in
# the release tarball) and assert the word error rate against a known transcript
# stays inside a sane budget. This is the model the app runs, so a regression in
# the model_type wiring or the bundled weights fails here before it ships.
#
# Requires: python3 with `sherpa-onnx` + `numpy` (the CI job pip-installs them).
set -euo pipefail

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

TARBALL="${TARBALL:-sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2}"
URL="${URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$TARBALL}"
DIR="${DIR:-sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8}"
CACHE_DIR="${ASR_CACHE_DIR:-}"

# Ground truth for the bundled 0.wav (House of the Seven Gables excerpt), from
# the model authors' reference sentence. Compared after case/punctuation
# normalization; the acoustic model renders the name as "Phebe" (1 sub in ~21
# words), so the budget below has to tolerate that single spelling slip.
REFERENCE="Well, I don't wish to see it any more, observed Phoebe, turning away her eyes. It is certainly very like the old portrait."

fetch() { # fetch URL DEST
  local dest="$1" cached
  if [[ -n "$CACHE_DIR" ]]; then
    cached="$CACHE_DIR/$(basename "$dest")"
    if [[ -s "$cached" ]]; then echo "  cache $(basename "$dest")"; cp "$cached" "$dest"; return; fi
  fi
  echo "  fetch $(basename "$dest")"
  curl -fsSL --retry 4 --retry-delay 3 --retry-all-errors -o "$dest.part" "$2"
  mv "$dest.part" "$dest"
  if [[ -n "$CACHE_DIR" ]]; then cp "$dest" "$CACHE_DIR/$(basename "$dest")"; fi
}

echo "::group::download Parakeet weights + reference audio"
fetch "$TARBALL" "$URL"
# One tarball carries the weights and the reference clip; extract just what we need.
tar -xjf "$TARBALL" -C "$WORK" \
  "$DIR/encoder.int8.onnx" "$DIR/decoder.int8.onnx" "$DIR/joiner.int8.onnx" \
  "$DIR/tokens.txt" "$DIR/test_wavs/0.wav"
echo "::endgroup::"

echo "::group::offline nemo_transducer WER"
python3 - "$WORK" "$DIR" "$REFERENCE" <<'PY'
import sys, wave, numpy as np, sherpa_onnx

work, d, reference = sys.argv[1], sys.argv[2], sys.argv[3]

def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2, "expected 16-bit PCM"
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        raw = w.readframes(n)
    x = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return sr, x

def norm(s):
    keep = " ".join(ch if (ch.isalnum() or ch == " ") else " " for ch in s)
    return " ".join(keep.upper().split())

def wer(ref, hyp):
    r, h = ref.split(), hyp.split()
    prev = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        cur = [i] + [0] * len(h)
        for j, hw in enumerate(h, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rw != hw))
        prev = cur
    return prev[len(h)] / max(1, len(r))

sr, x = read_wav(f"{work}/{d}/test_wavs/0.wav")
assert sr == 16000, f"expected 16 kHz reference clip, got {sr}"

rec = sherpa_onnx.OfflineRecognizer.from_transducer(
    encoder=f"{work}/{d}/encoder.int8.onnx", decoder=f"{work}/{d}/decoder.int8.onnx",
    joiner=f"{work}/{d}/joiner.int8.onnx", tokens=f"{work}/{d}/tokens.txt",
    num_threads=2, decoding_method="greedy_search",
    model_type="nemo_transducer",
)
s = rec.create_stream()
s.accept_waveform(sr, x)
rec.decode_stream(s)
hyp = rec.get_result(s).text

ref_n, hyp_n = norm(reference), norm(hyp)
score = wer(ref_n, hyp_n)

print(f"reference : {ref_n}")
print(f"parakeet  : {hyp_n}")
print(f"  WER = {score:.3f}")

ok = score <= 0.15
print("ASR SMOKE " + ("PASS" if ok else "FAIL"))
if not ok:
    print(f"reason: WER={score:.3f} exceeds 0.15 budget")
    sys.exit(1)
PY
echo "::endgroup::"
