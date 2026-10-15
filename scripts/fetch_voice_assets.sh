#!/usr/bin/env bash
# Fetch + verify the bundled voice assets (LLM, wake-word KWS, streaming ASR, TTS).
#
# Usage:
#   scripts/fetch_voice_assets.sh [OUT_DIR] [verify|generate]
#
#   verify   (default) download every asset, check it against scripts/voice_assets.lock,
#            fail on any byte/sha mismatch. Used by the release build.
#   generate download every asset and (re)write the lock file scripts/voice_assets.lock.
#            Run on CI when a model URL/version changes, then commit the lock.
#
# Wake-word keywords.txt is produced with `sherpa-onnx-cli text2token` when the CLI is
# available (CI installs it); otherwise the committed scripts/voice_keywords.txt is used.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

OUT_DIR="${1:-$REPO_ROOT/app/src/main/assets/voice}"
MODE="${2:-verify}"

MANIFEST="$SCRIPT_DIR/voice_assets.manifest"
LOCK="$SCRIPT_DIR/voice_assets.lock"
KEYWORDS_SRC="$SCRIPT_DIR/voice_keywords.txt"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log() { printf '==> %s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ -f "$MANIFEST" ] || die "manifest not found: $MANIFEST"
mkdir -p "$OUT_DIR"

declare -a RESULT=()

fetch_cached() {
  # fetch_cached URL -> prints local path (cached by basename)
  local url="$1" name dest
  name="$(basename "${url%%\?*}")"
  dest="$WORK/$name"
  if [ ! -f "$dest" ]; then
    log "download $(basename "$url")"
    curl -fsSL --retry 4 --retry-delay 3 --retry-all-errors -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  printf '%s' "$dest"
}

sha256_of() { sha256sum "$1" | awk '{print $1}'; }
bytes_of() { wc -c < "$1" | tr -d ' '; }

record() {
  # record DEST FILE
  local dest="$1" file="$2"
  RESULT+=("$dest|$(bytes_of "$file")|$(sha256_of "$file")")
}

while IFS='|' read -r kind dest url member; do
  case "$kind" in ''|'#'*) continue ;; esac
  [ -n "$dest" ] && [ -n "$url" ] || die "bad manifest line: $kind|$dest|$url|$member"
  target="$OUT_DIR/$dest"
  mkdir -p "$(dirname "$target")"
  case "$kind" in
    file)
      src="$(fetch_cached "$url")"
      cp -f "$src" "$target"
      [ -s "$target" ] || die "empty asset: $target"
      record "$dest" "$target"
      ;;
    tar)
      [ -n "$member" ] || die "tar entry without member: $dest"
      src="$(fetch_cached "$url")"
      tar -xjf "$src" -O "$member" > "$target" || die "extract failed: $member"
      [ -s "$target" ] || die "empty asset: $target"
      record "$dest" "$target"
      ;;
    tardir)
      [ -n "$member" ] || die "tardir entry without subtree: $dest"
      src="$(fetch_cached "$url")"
      mkdir -p "$target"
      tar -xjf "$src" -C "$target" --strip-components=1 "$member" \
        || die "extract failed: $member"
      # Record every regular file beneath the extracted subtree.
      while IFS= read -r f; do
        record "${f#"$OUT_DIR/"}" "$f"
      done < <(find "$target" -type f | sort)
      ;;
    *)
      die "unknown kind: $kind"
      ;;
  esac
done < "$MANIFEST"

# --- wake-word keywords.txt (multi-trigger) ----------------------------------
# phrase | boost score (:score) | trigger threshold (#threshold).
# Phrase activity is toggled at runtime in-app; the tokens must be baked here
# because the BPE tokenizer only exists at build time.
TRIGGERS=(
  "HEY BRO|2.0|0.25"
  "BRO|2.0|0.50"
  "YO BRO|2.0|0.35"
  "OKAY BRO|2.0|0.35"
)
KW_RAW="$WORK/voice_keywords_raw.txt"
KW_TOKENS="$WORK/voice_keywords_tokens.txt"
KW_OUT="$OUT_DIR/keywords.txt"
: > "$KW_RAW"
for t in "${TRIGGERS[@]}"; do printf '%s\n' "${t%%|*}" >> "$KW_RAW"; done

if command -v sherpa-onnx-cli >/dev/null 2>&1; then
  log "generate keywords.txt (text2token, ${#TRIGGERS[@]} triggers)"
  sherpa-onnx-cli text2token "$KW_RAW" "$KW_TOKENS" \
    --tokens "$OUT_DIR/models/kws/tokens.txt" \
    --tokens-type bpe \
    --bpe-model "$OUT_DIR/models/kws/bpe.model"
  : > "$KW_OUT"
  i=0
  while IFS= read -r tokens; do
    [ -n "$tokens" ] || die "text2token produced an empty line for '${TRIGGERS[$i]}'"
    IFS='|' read -r phrase score threshold <<< "${TRIGGERS[$i]}"
    printf '%s :%s #%s @%s\n' "$tokens" "$score" "$threshold" \
      "$(printf '%s' "$phrase" | tr -d ' ')" >> "$KW_OUT"
    i=$((i+1))
  done < "$KW_TOKENS"
  [ "$i" -eq "${#TRIGGERS[@]}" ] || die "text2token lines ($i) != triggers (${#TRIGGERS[@]})"
  cp -f "$KW_OUT" "$KEYWORDS_SRC"
else
  [ -f "$KEYWORDS_SRC" ] || die "sherpa-onnx-cli missing and no committed $KEYWORDS_SRC"
  log "copy committed keywords.txt (no sherpa-onnx-cli here)"
  cp -f "$KEYWORDS_SRC" "$KW_OUT"
fi
[ -s "$KW_OUT" ] || die "keywords.txt is empty"
record "keywords.txt" "$KW_OUT"

# --- lock handling -----------------------------------------------------------
sorted() { printf '%s\n' "$@" | sort; }

if [ "$MODE" = "generate" ]; then
  log "writing lock: $LOCK"
  sorted "${RESULT[@]}" > "$LOCK"
elif [ "$MODE" = "verify" ]; then
  [ -f "$LOCK" ] || die "lock missing: $LOCK (run with 'generate' and commit it)"
  if ! diff <(sorted "${RESULT[@]}") "$LOCK" >/dev/null; then
    echo "--- expected (lock) ---" >&2; cat "$LOCK" >&2
    echo "--- got ---" >&2; sorted "${RESULT[@]}" >&2
    die "asset verification failed"
  fi
  log "all assets verified against lock"
else
  die "unknown mode: $MODE (use verify|generate)"
fi

log "staged into: $OUT_DIR"
du -sh "$OUT_DIR" | awk '{print "    total: "$1}'
printf '%s\n' "${RESULT[@]}" | awk -F'|' '{n++; s+=$2} END {printf "    files: %d\n", n}'
printf '    keywords.txt: %s\n' "$(cat "$KW_OUT" 2>/dev/null | tr '\n' ' ')"
