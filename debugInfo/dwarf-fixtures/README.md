# DWARF Fixtures

This directory contains reproducible source inputs and checked-in DWARF parser fixtures.

Fixture binaries live in `debugInfo/dwarf-fixtures/test-data/` and are committed so parser tests can run without Docker or native toolchains. Use the generator only when intentionally refreshing the fixture set.

## Docker Build

Run from the repository root:

```bash
docker build -t hexana-dwarf-fixtures:build debugInfo/dwarf-fixtures
mkdir -p debugInfo/dwarf-fixtures/test-data
docker run --rm \
  -v "$PWD/debugInfo/dwarf-fixtures":/src:ro \
  -v "$PWD/debugInfo/dwarf-fixtures/test-data":/out \
  hexana-dwarf-fixtures:build
```

Expected outputs include:

- `test-data/elf/line-table-64.o`
- `test-data/elf/line-table-64.elf`
- `test-data/elf/line-table-64.elf.debug_line.bin`
- `test-data/elf/line-table-64.elf.dwarfdump.txt`
- `test-data/manifest.txt`

The executable `.debug_line` section is dumped separately so `debugInfo:dwarf` tests can exercise line-table parsing without depending on ELF parsing. The executable fixture is linked, not just compiled, so `DW_LNE_set_address` contains a real 64-bit address payload rather than object-file relocation placeholders.

## Local Build

The same script can be run locally when `clang`, `llvm-objcopy`, and `llvm-dwarfdump` are available:

```bash
debugInfo/dwarf-fixtures/build-dwarf-fixtures.sh
```

Set `SRC_DIR` and `OUT_DIR` to override source and output directories.
