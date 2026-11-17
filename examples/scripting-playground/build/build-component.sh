#!/bin/sh
# build-component.sh
# Wraps bin/app-release.wasm into a WebAssembly Component using wasm-tools 1.245.1.
#
# The app-release.wasm has no WASI imports (the reactor's __wasm_call_ctors is
# statically linked; all defined functions are self-contained), so no WASI
# preview1 adapter is needed. wasm-tools component new wraps the core module
# in a component shell and lifts its exports.
#
# Output: bin/component.wasm  (~1 KB)
# Requires: wasm-tools 1.245.1 at ${WASM_TOOLS:-wasm-tools}
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
BIN="$ROOT/bin"

WASM_TOOLS="${WASM_TOOLS:-wasm-tools}"

if [ ! -f "$BIN/app-release.wasm" ]; then
  echo "ERROR: $BIN/app-release.wasm not found; run build-app.sh first" >&2
  exit 1
fi

echo "==> Building component.wasm"
"$WASM_TOOLS" component new "$BIN/app-release.wasm" -o "$BIN/component.wasm"
echo "    Size: $(wc -c < "$BIN/component.wasm") bytes"

echo "==> Validating component"
"$WASM_TOOLS" validate "$BIN/component.wasm" && echo "    OK" || echo "    FAIL"

echo "==> Done: bin/component.wasm"
