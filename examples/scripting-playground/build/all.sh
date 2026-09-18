#!/bin/sh
# all.sh
# Builds all committed binaries in examples/scripting-playground/bin/ (except
# many-functions.wasm, which is generated on demand via gen-many-functions.sh).
#
# Re-runnable: deterministic for hello.wasm and component.wasm; app-* binaries
# embed source file paths in DWARF via -ffile-prefix-map (mapped to relative
# paths) so the bytes are stable for a given toolchain version and source tree.
# See MANIFEST.md for nondeterminism notes.
#
# Usage: ./build/all.sh [--verify]
#   --verify  run sha256 twice and diff to assert determinism
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"

VERIFY=0
for arg in "$@"; do
  case "$arg" in --verify) VERIFY=1 ;; esac
done

run() {
  echo ""
  echo ">>> $1"
  bash "$SCRIPT_DIR/$1"
}

run build-hello.sh
run build-app.sh
run build-component.sh
run gen-broken.sh

echo ""
echo "=== Validation pass ==="
WASM_TOOLS="${WASM_TOOLS:-/Users/user/.cargo/bin/wasm-tools}"
for f in hello.wasm app-debug.wasm app-release.wasm component.wasm; do
  "$WASM_TOOLS" validate "$ROOT/bin/$f" && echo "  OK: $f" || echo "  FAIL: $f"
done
echo "  Asserting broken.wasm FAILS validation..."
if "$WASM_TOOLS" validate "$ROOT/bin/broken.wasm" 2>/dev/null; then
  echo "  UNEXPECTED PASS: broken.wasm passed validation -- check gen-broken.sh"
else
  echo "  OK: broken.wasm correctly fails validation"
fi

echo ""
if [ "$VERIFY" -eq 1 ]; then
  echo "=== Determinism check (building twice) ==="
  FIRST_SUMS="$("$WASM_TOOLS" --version && sha256sum "$ROOT/bin/"*.wasm | grep -v many-functions)"
  bash "$SCRIPT_DIR/build-hello.sh" > /dev/null 2>&1
  bash "$SCRIPT_DIR/build-app.sh" > /dev/null 2>&1
  bash "$SCRIPT_DIR/build-component.sh" > /dev/null 2>&1
  bash "$SCRIPT_DIR/gen-broken.sh" > /dev/null 2>&1
  SECOND_SUMS="$(sha256sum "$ROOT/bin/"*.wasm | grep -v many-functions)"
  if [ "$FIRST_SUMS" = "$SECOND_SUMS" ]; then
    echo "  PASS: all committed binaries are byte-for-byte reproducible"
  else
    echo "  DIFFERENCES detected (see MANIFEST.md nondeterminism section):"
    diff <(echo "$FIRST_SUMS") <(echo "$SECOND_SUMS") || true
  fi
fi

echo ""
echo "=== all.sh complete ==="
