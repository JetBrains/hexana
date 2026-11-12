#!/bin/sh
# gen-many-functions.sh
# Generates a WAT file with N functions (default 15000), compiles it to
# bin/many-functions.wasm. The output is NOT committed (see bin/.gitignore).
#
# Function naming strategy:
#   - Even-indexed functions get a $named_<i> id (appear in name section).
#   - Odd-indexed functions have no $id (anonymous; no name-section entry).
#   - Because WAT anonymous funcs cannot be referenced by id, all calls use
#     explicit function indices (not possible in WAT without ids); instead,
#     each function body is self-contained (no cross-calls) and varies its
#     instruction count by index to give different body sizes.
#
# One export (func 0) is emitted so the module has a non-empty export section.
# A data segment with a label string is included for the Strings tab demo.
#
# Requires: wat2wasm 1.0.36 on PATH
#           wasm-tools 1.245.1 on PATH (for post-compile validation)
#
# Usage:
#   ./gen-many-functions.sh          # N=15000
#   ./gen-many-functions.sh 500      # N=500
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
BIN="$ROOT/bin"
TMP_WAT="$BIN/many-functions.wat"

N="${1:-15000}"
WAT2WASM="${WAT2WASM:-/opt/homebrew/bin/wat2wasm}"
WASM_TOOLS="${WASM_TOOLS:-wasm-tools}"

echo "==> Generating WAT with N=$N functions"
T0="$(date +%s)"

{
  echo "(module"
  echo "  (memory 1)"
  echo '  (data (i32.const 0) "many-functions scale demo")'
  echo "  (export \"first\" (func 0))"

  i=0
  while [ "$i" -lt "$N" ]; do
    if [ $(( i % 2 )) -eq 0 ]; then
      # Named function (even index): $named_<i>
      printf '  (func $named_%d (result i32)\n' "$i"
    else
      # Anonymous function (odd index): no $id
      printf '  (func (result i32)\n'
    fi

    # Body: vary instruction count by index (1 + i%8 additions of i32.const)
    depth=$(( (i % 8) + 1 ))
    printf '    i32.const %d\n' "$i"
    j=1
    while [ "$j" -lt "$depth" ]; do
      printf '    i32.const %d\n' "$j"
      printf '    i32.add\n'
      j=$(( j + 1 ))
    done
    echo "  )"

    i=$(( i + 1 ))
  done

  echo ")"
} > "$TMP_WAT"

T1="$(date +%s)"
ELAPSED_GEN=$(( T1 - T0 ))
echo "    WAT generation: ${ELAPSED_GEN}s"

echo "==> Compiling with wat2wasm"
"$WAT2WASM" "$TMP_WAT" -o "$BIN/many-functions.wasm" --debug-names
rm -f "$TMP_WAT"

T2="$(date +%s)"
ELAPSED_COMPILE=$(( T2 - T1 ))
SIZE="$(wc -c < "$BIN/many-functions.wasm")"

echo "    Compile: ${ELAPSED_COMPILE}s"
echo "    Output: bin/many-functions.wasm  $SIZE bytes  (N=$N)"

echo "==> Validating"
"$WASM_TOOLS" validate "$BIN/many-functions.wasm" && echo "    OK" || echo "    FAIL"

echo "==> Done  total $((T2 - T0))s"
