#!/bin/sh
# Folio's one-time setup, run by Folio.app (again after an update that needs new parts):
# uv → Python 3.12 → the pinned packages → the voice models. Every download is checked against a
# fixed SHA-256. Lines starting with "step: " are shown in the setup window.
#   setup.sh <support dir> <app Resources dir>
set -e
SUPPORT="$1"
RES="$2"
UV_VERSION=0.13.1
UV_SHA=cfe454573412ff7380d15fc7dae5997ab53ffce72fce0a78e91ddbc84001acd2
SHERPA=https://github.com/k2-fsa/sherpa-onnx/releases/download

fail() { echo "error: $*"; exit 1; }
trap '[ $? -eq 0 ] || echo "error: Setup stopped. Check your internet connection and try again."' EXIT
mkdir -p "$SUPPORT/models"
cd "$SUPPORT"

# get <url> <sha256> <dest>
get() {
  rm -f "$3.part"
  curl -fL --retry 3 --retry-delay 2 -sS -o "$3.part" "$1" || fail "Couldn't download $(basename "$3")."
  [ "$(shasum -a 256 "$3.part" | cut -d' ' -f1)" = "$2" ] || { rm -f "$3.part"; fail "$(basename "$3") didn't download correctly."; }
  mv "$3.part" "$3"
}

if [ "$(uv/uv --version 2>/dev/null | cut -d' ' -f2)" != "$UV_VERSION" ]; then
  echo "step: Downloading the installer"
  get "https://github.com/astral-sh/uv/releases/download/$UV_VERSION/uv-aarch64-apple-darwin.tar.gz" "$UV_SHA" uv.tgz
  rm -rf uv && mkdir uv && tar xzf uv.tgz -C uv --strip-components 1 && rm uv.tgz
fi

export UV_PYTHON_INSTALL_DIR="$SUPPORT/python" UV_PYTHON_PREFERENCE=only-managed UV_NO_PROGRESS=1
if [ ! -x venv/bin/python ]; then
  echo "step: Installing Python"
  uv/uv venv --quiet --python 3.12 venv
fi

echo "step: Installing the speech engine (about 1.5 GB, a few minutes)"
uv/uv pip sync --quiet --python venv/bin/python "$RES/requirements.lock"

echo "step: Downloading voice models"
model() {
  [ "$(shasum -a 256 "models/$1" 2>/dev/null | cut -d' ' -f1)" = "$3" ] || get "$2" "$3" "models/$1"
}
model silero_vad.onnx "$SHERPA/asr-models/silero_vad.onnx" 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6
model nemo_en_titanet_small.onnx "$SHERPA/speaker-recongition-models/nemo_en_titanet_small.onnx" ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e

cp "$RES/setup-stamp" ready
echo "step: Done"
