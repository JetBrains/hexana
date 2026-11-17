#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEFAULT_SRC_DIR=$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)
DEFAULT_OUT_DIR="${SCRIPT_DIR}/test-data"
if [ -d /src/parsing/native-fixtures/test-data ]; then
  DEFAULT_SRC_DIR=/src
fi
if [ -d /out ]; then
  DEFAULT_OUT_DIR=/out
fi
SRC_DIR="${SRC_DIR:-${DEFAULT_SRC_DIR}}"
OUT_DIR="${OUT_DIR:-${DEFAULT_OUT_DIR}}"

LLVM_OBJDUMP="${LLVM_OBJDUMP:-llvm-objdump}"
INPUT_DIR="${SRC_DIR}/parsing/native-fixtures/test-data"
SNAPSHOT_DIR="${OUT_DIR}/llvm-objdump"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1. Install LLVM or set LLVM_OBJDUMP to llvm-objdump." >&2
    exit 1
  fi
}

dump_disassembly() {
  input="$1"
  output="$2"
  if [ ! -f "${input}" ]; then
    echo "missing input: ${input}" >&2
    return 1
  fi

  mkdir -p "$(dirname "${output}")"
  tmp_output="${output}.tmp"
  tmp_error="${output}.err"
  if "${LLVM_OBJDUMP}" --disassemble "${input}" > "${tmp_output}" 2> "${tmp_error}"; then
    mv "${tmp_output}" "${output}"
    rm -f "${tmp_error}"
  else
    echo "llvm-objdump failed for ${input}; snapshot was not updated" >&2
    cat "${tmp_error}" >&2
    rm -f "${tmp_output}" "${tmp_error}"
    return 1
  fi
}

require_command "${LLVM_OBJDUMP}"

if [ ! -d "${INPUT_DIR}" ]; then
  echo "Native parser fixtures not found: ${INPUT_DIR}" >&2
  exit 1
fi

mkdir -p "${SNAPSHOT_DIR}/elf" "${SNAPSHOT_DIR}/macho" "${SNAPSHOT_DIR}/pe"

dump_disassembly "${INPUT_DIR}/elf/sample.elf" "${SNAPSHOT_DIR}/elf/sample.elf.disassemble.txt"
dump_disassembly "${INPUT_DIR}/elf/sample.o" "${SNAPSHOT_DIR}/elf/sample.o.disassemble.txt"
dump_disassembly "${INPUT_DIR}/macho/sample-x86_64.o" "${SNAPSHOT_DIR}/macho/sample-x86_64.o.disassemble.txt"
dump_disassembly "${INPUT_DIR}/pe/sample.coff" "${SNAPSHOT_DIR}/pe/sample.coff.disassemble.txt"

manifest_tmp="${OUT_DIR}/manifest.txt.tmp"
{
  echo "Hexana native disassembly fixture manifest"
  echo "llvm-objdump: $(${LLVM_OBJDUMP} --version | sed -n '1p')"
  echo "input: ${INPUT_DIR}"
  echo
  find "${OUT_DIR}" -type f ! -name "manifest.txt" ! -name "manifest.txt.tmp" | sort | sed "s#${OUT_DIR}/##"
} > "${manifest_tmp}"
mv "${manifest_tmp}" "${OUT_DIR}/manifest.txt"

echo "fixture output: ${OUT_DIR}"
