# Native Disassembly Fixtures

This directory contains reproducible `llvm-objdump` snapshot fixtures generated
from the committed parser fixture binaries in `parsing/native-fixtures/test-data`.

The generated text snapshots are committed so normal tests can parse realistic
tool output without requiring `llvm-objdump` on every developer or CI machine.

## Docker Build

Run from the repository root:

```bash
docker build -t hexana-disassembly-fixtures:build disassembly/native-fixtures
mkdir -p disassembly/native-fixtures/test-data
docker run --rm \
  -v "$PWD":/src:ro \
  -v "$PWD/disassembly/native-fixtures/test-data":/out \
  hexana-disassembly-fixtures:build
```

Expected outputs include:

- `test-data/llvm-objdump/elf/sample.elf.disassemble.txt`
- `test-data/llvm-objdump/elf/sample.o.disassemble.txt`
- `test-data/llvm-objdump/macho/sample-x86_64.o.disassemble.txt`
- `test-data/llvm-objdump/pe/sample.coff.disassemble.txt`
- `test-data/manifest.txt`

## Local Build

The same script can be run locally if `llvm-objdump` is installed:

```bash
disassembly/native-fixtures/build-disassembly-fixtures.sh
```

Set `SRC_DIR` to the repository root and `OUT_DIR` to override output.
