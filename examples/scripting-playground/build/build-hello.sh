#!/bin/sh
# build-hello.sh
# Compiles src/hello.wat -> bin/hello.wasm using wabt wat2wasm 1.0.36.
# Re-runnable: produces the same bytes each time (wabt is deterministic for WAT).
#
# Requires: wat2wasm 1.0.36 on PATH (default: /opt/homebrew/bin/wat2wasm)
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"

WAT2WASM="${WAT2WASM:-/opt/homebrew/bin/wat2wasm}"

echo "==> Building hello.wasm"
"$WAT2WASM" \
  "$ROOT/src/hello.wat" \
  -o "$ROOT/bin/hello.wasm" \
  --debug-names

echo "    Size: $(wc -c < "$ROOT/bin/hello.wasm") bytes"
echo "==> Done: bin/hello.wasm"
