#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEFAULT_SRC_DIR="${SCRIPT_DIR}"
DEFAULT_OUT_DIR="${SCRIPT_DIR}/test-data"
if [ -d /src/src ]; then
  DEFAULT_SRC_DIR=/src
fi
if [ -d /out ]; then
  DEFAULT_OUT_DIR=/out
fi
SRC_DIR="${SRC_DIR:-${DEFAULT_SRC_DIR}}"
OUT_DIR="${OUT_DIR:-${DEFAULT_OUT_DIR}}"

CC="${CC:-clang}"
LLVM_OBJCOPY="${LLVM_OBJCOPY:-llvm-objcopy}"
LLVM_DWARFDUMP="${LLVM_DWARFDUMP:-llvm-dwarfdump}"

rm -rf "${OUT_DIR}/elf"
mkdir -p "${OUT_DIR}/elf"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_command "${CC}"
require_command "${LLVM_OBJCOPY}"
require_command "${LLVM_DWARFDUMP}"

"${CC}" \
  -gdwarf-5 \
  -O0 \
  --target=x86_64-linux-gnu \
  -fno-pie \
  -c "${SRC_DIR}/src/line_table_64.c" \
  -o "${OUT_DIR}/elf/line-table-64.o"

"${CC}" \
  -gdwarf-5 \
  -O0 \
  --target=x86_64-linux-gnu \
  -fuse-ld=lld \
  -nostdlib \
  -fno-pie \
  -no-pie \
  -Wl,-e,main \
  "${SRC_DIR}/src/line_table_64.c" \
  -o "${OUT_DIR}/elf/line-table-64.elf"

"${LLVM_OBJCOPY}" \
  --dump-section .debug_line="${OUT_DIR}/elf/line-table-64.elf.debug_line.bin" \
  "${OUT_DIR}/elf/line-table-64.elf"

"${LLVM_DWARFDUMP}" \
  --debug-line \
  -v \
  "${OUT_DIR}/elf/line-table-64.elf" \
  | awk '{ sub(/[[:space:]]+$/, ""); lines[NR] = $0 } END { end = NR; while (end > 0 && lines[end] == "") end--; for (i = 1; i <= end; i++) print lines[i] }' \
  > "${OUT_DIR}/elf/line-table-64.elf.dwarfdump.txt"

{
  echo "Hexana DWARF fixture manifest"
  echo "compiler: $(${CC} --version | sed -n '1p')"
  echo "llvm-objcopy: $(${LLVM_OBJCOPY} --version | sed -n '1p')"
  echo "llvm-dwarfdump: $(${LLVM_DWARFDUMP} --version | sed -n '1p')"
  echo
  find "${OUT_DIR}" -type f | sort | sed "s#${OUT_DIR}/##"
} > "${OUT_DIR}/manifest.txt"

echo "fixture output: ${OUT_DIR}"
