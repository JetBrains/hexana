# Native Disassembly Modules

`disassembly/core` owns Hexana's UI-free native disassembly model:
functions, instructions, addresses, xrefs, relocation annotations, source
locations, and basic-block candidates.

`disassembly/llvm-objdump` owns the reusable `llvm-objdump` backend pieces:
command construction, candidate discovery, probing, process execution result
handling, output parsing, and conversion into `disassembly/core` models.
Its unit tests use checked-in objdump text snapshots generated from the parser
fixture binaries by `disassembly/native-fixtures`. A separate real-binary smoke
test can also run against `parsing/native-fixtures/test-data/elf/sample.elf`
when an `llvm-objdump` executable is available on `PATH`.

IntelliJ-specific behavior stays outside these modules:

- settings UI and persisted settings
- CLion toolchain XML discovery
- progress indicators and notifications
- Compose/Jewel rendering
- editor navigation

Run:

```bash
./gradlew :disassembly:core:jvmTest :disassembly:llvm-objdump:test
```
