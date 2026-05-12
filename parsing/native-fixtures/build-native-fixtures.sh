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
CXX="${CXX:-clang++}"
LLVM_READOBJ="${LLVM_READOBJ:-llvm-readobj}"

mkdir -p "${OUT_DIR}/elf" "${OUT_DIR}/macho" "${OUT_DIR}/pe"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

try_build() {
  name="$1"
  shift
  if "$@"; then
    echo "built ${name}"
  else
    echo "skipped ${name}" >&2
  fi
}

dump_readobj() {
  input="$1"
  output="$2"
  if command -v "${LLVM_READOBJ}" >/dev/null 2>&1; then
    "${LLVM_READOBJ}" \
      --file-headers \
      --sections \
      --symbols \
      --relocations \
      --dynamic-table \
      --needed-libs \
      "${input}" > "${output}" 2>&1 || true
  fi
}

require_command "${CC}"

"${CC}" -g -O0 --target=x86_64-linux-gnu -fno-pie -c "${SRC_DIR}/src/sample.c" -o "${OUT_DIR}/elf/sample.o"
try_build "ELF executable" \
  "${CC}" -g -O0 --target=x86_64-linux-gnu -fuse-ld=lld -nostdlib -fno-pie -no-pie \
    -Wl,-e,main "${SRC_DIR}/src/sample.c" -o "${OUT_DIR}/elf/sample.elf"
try_build "ELF shared library" \
  "${CC}" -g -O0 --target=x86_64-linux-gnu -fuse-ld=lld -nostdlib -fPIC -shared \
    "${SRC_DIR}/src/sample.c" -o "${OUT_DIR}/elf/libsample.so"

if command -v "${CXX}" >/dev/null 2>&1; then
  "${CXX}" -g -O1 --target=x86_64-linux-gnu -fno-pie -c "${SRC_DIR}/src/sample_cpp.cpp" -o "${OUT_DIR}/elf/sample_cpp.o"
fi

try_build "Mach-O x86_64 object" \
  "${CC}" -g -O0 --target=x86_64-apple-macos11 -c "${SRC_DIR}/src/sample.c" -o "${OUT_DIR}/macho/sample-x86_64.o"
try_build "Mach-O arm64 object" \
  "${CC}" -g -O0 --target=arm64-apple-macos11 -c "${SRC_DIR}/src/sample.c" -o "${OUT_DIR}/macho/sample-arm64.o"
if command -v "${CXX}" >/dev/null 2>&1; then
  try_build "Mach-O x86_64 C++ object" \
    "${CXX}" -g -O1 --target=x86_64-apple-macos11 -c "${SRC_DIR}/src/sample_cpp.cpp" -o "${OUT_DIR}/macho/sample_cpp-x86_64.o"
fi

try_build "COFF object" \
  "${CC}" -g -O0 --target=x86_64-windows-msvc -c "${SRC_DIR}/src/sample.c" -o "${OUT_DIR}/pe/sample.coff"
try_build "PE executable" \
  "${CC}" -g -O0 --target=x86_64-windows-msvc -fuse-ld=lld-link -nostdlib \
    -Wl,/entry:mainCRTStartup -Wl,/subsystem:console \
    "${SRC_DIR}/src/sample.c" -o "${OUT_DIR}/pe/sample.exe"
if command -v "${CXX}" >/dev/null 2>&1; then
  try_build "COFF C++ object" \
    "${CXX}" -g -O1 --target=x86_64-windows-msvc -c "${SRC_DIR}/src/sample_cpp.cpp" -o "${OUT_DIR}/pe/sample_cpp.coff"
fi

find "${OUT_DIR}" -type f \( -name '*.o' -o -name '*.elf' -o -name '*.so' -o -name '*.coff' -o -name '*.exe' \) | sort | while read -r binary; do
  dump_readobj "${binary}" "${binary}.llvm-readobj.txt"
done

{
  echo "Hexana native parser fixture manifest"
  echo "compiler: $(${CC} --version | sed -n '1p')"
  if command -v "${CXX}" >/dev/null 2>&1; then
    echo "cxx compiler: $(${CXX} --version | sed -n '1p')"
  fi
  echo
  find "${OUT_DIR}" -type f | sort | sed "s#${OUT_DIR}/##"
} > "${OUT_DIR}/manifest.txt"

echo "fixture output: ${OUT_DIR}"
