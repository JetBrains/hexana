# Native Parser Fixtures

This directory contains reproducible source inputs and checked-in native binary parser fixtures.

Fixture binaries live in `parsing/native-fixtures/test-data/` and are committed so parser tests can run without regenerating native binaries on every machine. Use the generator only when intentionally refreshing the fixture set.

## Docker Build

Run from the repository root:

```bash
docker build -t hexana-native-fixtures:build parsing/native-fixtures
mkdir -p parsing/native-fixtures/test-data
docker run --rm \
  -v "$PWD/parsing/native-fixtures":/src:ro \
  -v "$PWD/parsing/native-fixtures/test-data":/out \
  hexana-native-fixtures:build
```

Expected outputs include:

- `test-data/elf/sample.o`
- `test-data/elf/sample.elf`
- `test-data/elf/libsample.so`
- `test-data/elf/sample_cpp.o`
- `test-data/macho/sample-x86_64.o`
- `test-data/macho/sample-arm64.o`
- `test-data/macho/sample_cpp-x86_64.o`
- `test-data/pe/sample.coff`
- `test-data/pe/sample.exe`
- `test-data/pe/sample.lib`
- `test-data/pe/sample.pdb`
- `test-data/pe/sample_cpp.coff`
- `test-data/manifest.txt`

When LLVM inspection tools are available, the build also writes `*.llvm-readobj.txt` reference dumps next to the generated binaries.

## Local Build

The same script can be run locally:

```bash
parsing/native-fixtures/build-native-fixtures.sh
```

Set `SRC_DIR` and `OUT_DIR` to override source and output directories.
