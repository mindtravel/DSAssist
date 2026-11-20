#!/usr/bin/env bash
# Fetches what this repository does not carry: the speech library and the local models. Run once
# after cloning. Every download is checked against the checksum written here, so what lands in the
# tree is exactly what upstream published; a mismatch stops the script.
#
#   tools/models/fetch-models.sh            fetch what is missing
#   tools/models/fetch-models.sh --check    only report what is missing or does not match
#
# ── configuration ────────────────────────────────────────────────────────────────────────────────────
# To use another build of a model — a different keyword set, a newer release — change its block here.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE="${REDUCTION_MODEL_CACHE:-$ROOT/.models}"
RELEASES=https://github.com/k2-fsa/sherpa-onnx/releases/download

# The speech library behind the wake word, the end word and the voice detector. Only its arm64 part
# is used; the app drops the other ABIs' libraries.
LIBRARY_URL=$RELEASES/v2.0.1/sherpa-onnx-2.0.1.aar
LIBRARY_SHA=a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7

# Wake phrase "Hi Reduction": English keyword model, given in lowercase and uppercase spellings of
# "hi" and with the name alone, so the phrase is heard with or without a pause.
ENGLISH_URL=$RELEASES/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
ENGLISH_SHA=f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a
ENGLISH_DIR=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
ENGLISH_KEYWORDS='▁HI ▁RE D U CTION @HI_REDUCTION
▁HIGH ▁RE D U CTION @HI_REDUCTION
▁RE D U CTION @REDUCTION'

# End word "完毕": Chinese keyword model. The mobile build: made for phones, smaller, and on 111
# recorded turns it hears the word as often as the full build.
CHINESE_URL=$RELEASES/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2
CHINESE_SHA=b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b
CHINESE_DIR=sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile
CHINESE_KEYWORDS='w án b ì @完毕'

# Speech or noise: Silero VAD v4.
VAD_URL=$RELEASES/asr-models/silero_vad_v4.onnx
VAD_SHA=a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28
# AISHELL-3 low VITS, Mandarin, from icefall. Only the small text-normalisation FSTs are used.
TTS_NAME=vits-icefall-zh-aishell3
TTS_SHA=ab468db3a3308cdd861495e0db2f25d79418a0c00639f74944c7cdf5dd8c6ec1
# ── end of configuration ─────────────────────────────────────────────────────────────────────────────

check_only=false
[ "${1:-}" = "--check" ] && check_only=true
missing=0
mkdir -p "$CACHE"

# Downloads url into the cache unless a file with the right checksum is already there. Fails when
# the file cannot be had or is not what upstream published.
obtain() {                                  # url, cache name, sha256
  local url=$1 name=$2 want=$3 path="$CACHE/$2"
  if [ -f "$path" ] && [ "$(sha256sum "$path" | cut -d' ' -f1)" = "$want" ]; then return 0; fi
  if $check_only; then echo "missing $name"; missing=1; return 1; fi
  echo "getting $name"
  curl -L --fail --retry 3 -o "$path.part" "$url" || { echo "!! $name could not be downloaded"; missing=1; return 1; }
  mv "$path.part" "$path"
  if [ "$(sha256sum "$path" | cut -d' ' -f1)" != "$want" ]; then
    echo "!! $name does not match its checksum"; rm -f "$path"; missing=1; return 1
  fi
}

# Unpacks a keyword model into the app's assets under the names the app loads, and writes its
# keywords file. The models' file names carry their training epochs; the app's do not.
place_keywords() {                          # cache name, directory inside it, destination, keywords
  local tar="$CACHE/$1" dir="$CACHE/unpacked/$2" dest="$ROOT/$3" kind from
  rm -rf "$CACHE/unpacked"
  mkdir -p "$CACHE/unpacked" "$dest"
  tar xjf "$tar" -C "$CACHE/unpacked" || { echo "!! $1 does not unpack"; missing=1; return 1; }
  for kind in encoder decoder joiner; do
    # The decoder of the mobile Chinese build is not quantised, so files are matched by prefix,
    # with the int8 build preferred where there is a choice.
    from=$(find "$dir" -maxdepth 1 -name "$kind*.int8.onnx" | sort | head -1)
    [ -n "$from" ] || from=$(find "$dir" -maxdepth 1 -name "$kind*.onnx" | sort | head -1)
    [ -n "$from" ] || { echo "!! $2 has no $kind model"; missing=1; return 1; }
    cp -f "$from" "$dest/$kind.onnx"
  done
  cp -f "$dir/tokens.txt" "$dest/tokens.txt"
  printf '%s\n' "$4" > "$dest/keywords.txt"
  echo "placed  $3"
}

place_tts() {
  local dest=$1
  obtain "$RELEASES/tts-models/$TTS_NAME.tar.bz2" "$TTS_NAME.tar.bz2" "$TTS_SHA" || return 1
  mkdir -p "$dest"
  tar -xjf "$CACHE/$TTS_NAME.tar.bz2" -C "$dest" --strip-components=1 \
    "$TTS_NAME"/{model.onnx,lexicon.txt,tokens.txt,phone.fst,date.fst,number.fst}
}

place_tts "$ROOT/app/src/main/assets/tts-aishell3" || missing=1

obtain "$LIBRARY_URL" sherpa-onnx-2.0.1.aar "$LIBRARY_SHA" &&
  { mkdir -p "$ROOT/app/libs"; cp -f "$CACHE/sherpa-onnx-2.0.1.aar" "$ROOT/app/libs/sherpa-onnx-arm64.aar"; } &&
  echo "placed  app/libs/sherpa-onnx-arm64.aar"

obtain "$VAD_URL" silero_vad.onnx "$VAD_SHA" &&
  { mkdir -p "$ROOT/app/src/main/assets/vad"; cp -f "$CACHE/silero_vad.onnx" "$ROOT/app/src/main/assets/vad/silero_vad.onnx"; } &&
  echo "placed  app/src/main/assets/vad/silero_vad.onnx"

obtain "$ENGLISH_URL" kws-english.tar.bz2 "$ENGLISH_SHA" &&
  place_keywords kws-english.tar.bz2 "$ENGLISH_DIR" app/src/main/assets/kws-en "$ENGLISH_KEYWORDS"

obtain "$CHINESE_URL" kws-chinese.tar.bz2 "$CHINESE_SHA" &&
  place_keywords kws-chinese.tar.bz2 "$CHINESE_DIR" app/src/main/assets/kws "$CHINESE_KEYWORDS"

[ "$missing" = 0 ] && echo "all there" || { echo "run without --check to fetch what is missing"; exit 1; }
