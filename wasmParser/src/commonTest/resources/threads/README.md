# threads fixtures

`shared-memory-import.wasm` is a minimal module written for this repository (no third-party code). It imports a
fixed-size shared memory (`i.memory`, 4096 pages, shared) and three functions, which is the import layout that
pthread-enabled toolchains emit. `WasmParserTests.parseThreadsSpecificImportTypes` uses it to check that the parser
reads the shared flag of a memory import.

## How it is built

The binary is the output of the repository's own `wat2wasm` (`:graalwasm:wat2wasm`, the embedded `wasm-tools`-based
converter) for `shared-memory-import.wat`, which is its only source. `ThreadsFixtureTest` (JVM) rebuilds it from the
`.wat` and fails if the committed `.wasm` differs, so change both together: edit the `.wat`, run
`wat2wasm(<text>)` from a JVM test and write the bytes to `shared-memory-import.wasm`.
