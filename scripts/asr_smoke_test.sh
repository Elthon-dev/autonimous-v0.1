#!/usr/bin/env bash
# ASR accuracy smoke test (CI, ubuntu-latest).
#
# Evidence for the streaming-ASR upgrade: download BOTH the old 20M model
# (sherpa-onnx-streaming-zipformer-en-20M-2023-02-17) and the new, larger
# 2023-06-26 model with the SAME sherpa-onnx streaming-transducer engine, run
# them on the model repo's reference audio (test_wavs/0.wav + trans.txt) and
# compare word error rate. Fails unless the new model is at least as accurate as
# the old one and within a sane WER budget.
#
# Requires: python3 with `sherpa-onnx` + `numpy` (the CI job pip-installs them).
set -euo pipefail

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

OLD_REPO="${OLD_REPO:-csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17}"
NEW_REPO="${NEW_REPO:-csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26}"
HF="https://huggingface.co"

get() { # get URL DEST
  echo "  fetch $(basename "$1")"
  curl -fsSL --retry 4 --retry-delay 3 --retry-all-errors -o "$2" "$1"
}

echo "::group::download models + reference audio"
mkdir -p old new
# old (currently bundled): plain names
get "$HF/$OLD_REPO/resolve/main/encoder-epoch-99-avg-1.int8.onnx" old/encoder.onnx
get "$HF/$OLD_REPO/resolve/main/decoder-epoch-99-avg-1.int8.onnx" old/decoder.onnx
get "$HF/$OLD_REPO/resolve/main/joiner-epoch-99-avg-1.int8.onnx"  old/joiner.onnx
get "$HF/$OLD_REPO/resolve/main/tokens.txt"                      old/tokens.txt
# new (candidate): chunk-16-left-128 int8
get "$HF/$NEW_REPO/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" new/encoder.onnx
get "$HF/$NEW_REPO/resolve/main/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" new/decoder.onnx
get "$HF/$NEW_REPO/resolve/main/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"  new/joiner.onnx
get "$HF/$NEW_REPO/resolve/main/tokens.txt"                                         new/tokens.txt
get "$HF/$NEW_REPO/resolve/main/test_wavs/0.wav"     audio.wav
get "$HF/$NEW_REPO/resolve/main/test_wavs/trans.txt" trans.txt
echo "::endgroup::"

echo "::group::streaming WER comparison"
python3 - "$PWD" <<'PY'
import sys, wave, numpy as np, sherpa_onnx

work = sys.argv[1]

def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2, "expected 16-bit PCM"
        sr, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        raw = w.readframes(n)
    x = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if ch > 1:
        x = x.reshape(-1, ch).mean(axis=1)
    return sr, x

def make(d):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        encoder=f"{d}/encoder.onnx", decoder=f"{d}/decoder.onnx",
        joiner=f"{d}/joiner.onnx", tokens=f"{d}/tokens.txt",
        num_threads=2, decoding_method="greedy_search",
    )

def transcribe(rec, sr, x):
    s = rec.create_stream()
    s.accept_waveform(sr, x)
    s.accept_waveform(sr, np.zeros(int(0.5 * sr), dtype=np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    return rec.get_result(s).strip().upper()

def wer(ref, hyp):
    r, h = ref.split(), hyp.split()
    d = [[0] * (len(h) + 1) for _ in range(len(r) + 1)]
    for i in range(len(r) + 1): d[i][0] = i
    for j in range(len(h) + 1): d[0][j] = j
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            d[i][j] = min(d[i-1][j] + 1, d[i][j-1] + 1,
                          d[i-1][j-1] + (r[i-1] != h[j-1]))
    return d[len(r)][len(h)] / max(1, len(r))

ref = ""
for line in open(f"{work}/trans.txt"):
    if line.lower().startswith("0.wav"):
        ref = line.split(None, 1)[1].strip().upper()
        break
assert ref, "reference transcript for 0.wav not found"

sr, x = read_wav(f"{work}/audio.wav")
assert sr == 16000, f"expected 16 kHz, got {sr}"

old = transcribe(make(f"{work}/old"), sr, x)
new = transcribe(make(f"{work}/new"), sr, x)
old_wer, new_wer = wer(ref, old), wer(ref, new)

print(f"reference : {ref}")
print(f"old (20M) : {old}")
print(f"  WER = {old_wer:.3f}")
print(f"new(0626) : {new}")
print(f"  WER = {new_wer:.3f}")

ok = new_wer <= old_wer + 1e-9 and new_wer <= 0.15
print("ASR SMOKE " + ("PASS" if ok else "FAIL"))
if not ok:
    print(f"reason: new_wer={new_wer:.3f} (must be <= old_wer={old_wer:.3f} and <= 0.15)")
    sys.exit(1)
PY
echo "::endgroup::"
