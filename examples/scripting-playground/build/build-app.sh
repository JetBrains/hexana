#!/bin/sh
# build-app.sh
# Compiles src/app/{main,fib,strings}.c -> bin/app-debug.wasm (with DWARF + name section).
# Then produces bin/app-release.wasm (stripped) and bin/app-release.debug.wasm (DWARF sidecar).
#
# Toolchain required:
#   wasi-sdk 27.0  at ${WASI_SDK:-/Users/user/wasi-sdk}  (clang 20.1.8, wasm32-wasi)
#   wasm-tools 1.245.1 at ${WASM_TOOLS:-/Users/user/.cargo/bin/wasm-tools}
#
# Nondeterminism note: clang embeds the absolute source path in DWARF DW_AT_comp_dir /
# DW_AT_name unless -ffile-prefix-map is used. We remap ${SCRIPT_DIR}/.. -> . so the
# DWARF paths are relative to the project root. Binary bytes are then deterministic
# for a given toolchain version and source tree.
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
SRC="$ROOT/src/app"
BIN="$ROOT/bin"

WASI_SDK="${WASI_SDK:-/Users/user/wasi-sdk}"
CLANG="$WASI_SDK/bin/clang"
OBJCOPY="$WASI_SDK/bin/llvm-objcopy"
WASM_TOOLS="${WASM_TOOLS:-/Users/user/.cargo/bin/wasm-tools}"

# --no-wasm-opt: the wasi-sdk driver runs binaryen wasm-opt after the link, and
# wasm-opt's DWARF rewriting drops every .debug_line program (prologue-only units
# survive). Skipping it keeps the full line tables that source-location tests need.
CFLAGS="-target wasm32-wasi -mexec-model=reactor -O1 -g --no-wasm-opt \
  -ffile-prefix-map=${ROOT}=. \
  -Wl,--export=greeting_len \
  -Wl,--export=greeting_byte \
  -Wl,--export=compute_fib \
  -Wl,--no-entry"

echo "==> Building app-debug.wasm (O1, DWARF, name section)"
# cd to ROOT so DW_AT_comp_dir is set by clang to ROOT regardless of caller cwd.
(cd "$ROOT" && "$CLANG" $CFLAGS \
  "src/app/main.c" "src/app/fib.c" "src/app/strings.c" \
  -o "bin/app-debug.wasm")
echo "    Size: $(wc -c < "$BIN/app-debug.wasm") bytes"

echo "==> Building app-release.debug.wasm (DWARF sidecar)"
"$OBJCOPY" \
  --only-keep-debug \
  "$BIN/app-debug.wasm" \
  "$BIN/app-release.debug.wasm"
echo "    Sidecar size: $(wc -c < "$BIN/app-release.debug.wasm") bytes"

echo "==> Validating sidecar (wasm-tools print -- wasm-tools validate may reject debug-only)"
# wasm-tools validate may reject a file that has no code/func sections.
# Use wasm-tools print as a parse sanity check instead.
"$WASM_TOOLS" print "$BIN/app-release.debug.wasm" > /dev/null && \
  echo "    Sidecar parses OK (wasm-tools print)" || \
  echo "    Sidecar: wasm-tools print failed (known: debug-only wasm may lack required sections)"

echo "==> Building app-release.wasm (stripped)"
# --remove-section=name: with --no-wasm-opt the debug build keeps its name section,
# so --strip-debug alone no longer yields a fully symbol-stripped release.
"$OBJCOPY" \
  --strip-debug \
  --remove-section=name \
  "$BIN/app-debug.wasm" \
  "$BIN/app-release.wasm"
echo "    Release size: $(wc -c < "$BIN/app-release.wasm") bytes"

echo "==> Verifying stripped: no name/DWARF sections"
if "$WASM_TOOLS" objdump "$BIN/app-release.wasm" | grep -E 'custom "(\.debug|name)"' > /dev/null 2>&1; then
  echo "    WARNING: stripped binary still has debug/name sections"
else
  echo "    OK: no debug/name sections in app-release.wasm"
fi

echo "==> Done"
