#!/bin/sh
# gen-broken.sh
# Produces bin/broken.wasm by byte-mutating bin/hello.wasm.
#
# Mutation: overwrites byte at offset 20 (inside the Type section body, after the
# section-id byte at offset 1, size LEB128 at 2, and the vec count at offset 8;
# offset 20 is within a func type encoding) with 0xFF, which is not a valid
# valtype. This makes the file structurally invalid and exercises Hexana's
# error-recovery path.
#
# Offset arithmetic (hello.wasm layout):
#   0x00..0x07  magic (\0asm) + version (1)
#   0x08        section id 0x01 (Type)
#   0x09        section size LEB128
#   0x0A        vec count
#   0x0B..      type entries (0x60 func types)
#   0x14 (20)   inside a param/result encoding -- safe to corrupt
#
# The result MUST fail wasm-tools validate (checked in build/all.sh).
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
BIN="$ROOT/bin"

SRC="$BIN/hello.wasm"
DST="$BIN/broken.wasm"

if [ ! -f "$SRC" ]; then
  echo "ERROR: $SRC not found; run build-hello.sh first" >&2
  exit 1
fi

echo "==> Generating broken.wasm from hello.wasm"

cp "$SRC" "$DST"

# Overwrite byte at offset 20 (0x14) with 0xFF using printf + dd.
# dd bs=1 seek=20 count=1 conv=notrunc overwrites exactly one byte.
printf '\xff' | dd of="$DST" bs=1 seek=20 count=1 conv=notrunc 2>/dev/null

echo "    Mutated offset 20 (0x14) -> 0xFF (invalid valtype in Type section)"
echo "    Size: $(wc -c < "$DST") bytes  (same as hello.wasm; content mutated, not truncated)"
echo "==> Done: bin/broken.wasm"
