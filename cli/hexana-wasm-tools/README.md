# Hexana Wasm Tools

`hexana-wasm-tools` is a focused installed CLI for structural WebAssembly reports. Its three commands are
`validate-and-explain`, `metadata-audit`, and `component-wit-report`. It reuses the generated CLI product catalog,
the shared product frontend, and the existing workflow composers in `cli:wasm-tools-core`. It does not expose an MCP
transport or reconstruct WIT source.

Native release ZIPs are named `hexana-wasm-tools-native-macos-arm64-0.4.0.zip` and
`hexana-wasm-tools-native-linux-x64-0.4.0.zip`. Each contains `bin/hexana-wasm-tools`, `LICENSE.txt`, this README,
and `THIRD-PARTY-NOTICES.md`. Extract the archive for your platform and run `./bin/hexana-wasm-tools version`;
the native executable does not require a JDK.

Build and run the JVM layout with JDK 21:

```sh
./gradlew :cli:hexana-wasm-tools:installDist
cli/hexana-wasm-tools/build/install/hexana-wasm-tools/bin/hexana-wasm-tools tools
cli/hexana-wasm-tools/build/install/hexana-wasm-tools/bin/hexana-wasm-tools version
```

With GraalVM 25 native-image, the native executable uses the same product identity and shared `hexanaToolsVersion`:

```sh
./gradlew :cli:hexana-wasm-tools:nativeCompile
cli/hexana-wasm-tools/build/native/nativeCompile/hexana-wasm-tools version
```

Each workflow accepts exactly one `.wasm` path, positional `-`, or `--stdin`; stdin requires an explicit selection.
Results are compact JSON by default or `--format pretty`, with one trailing newline. The shared frontend handles
`--output`, `--allowed-root`, and the `HEXANA_CLI_*` resource and telemetry settings. Use `--max-input-bytes` to
bound path and stdin equally, `--max-items` to bound report items, and
`--max-output-bytes` to bound the rendered report. `--runtime` may repeat for `validate-and-explain`, and
`--dependency` may repeat for `component-wit-report`; each has a generated cap of 500 distinct values.
The shared frontend also retains the earlier `--max-file-bytes`, `--max-inline-bytes`, and `--max-candidate-count`
options.

Exit codes are 0 for a complete report, 1 for findings or truncation, 2 for usage, 3 for input or output refusal,
and 70 for an unexpected internal failure. The commands report structural evidence only;
`validate-and-explain` explicitly records that semantic validation was not performed.

Run `./gradlew :cli:hexana-wasm-tools:integrationTest` for installed JVM contracts. With GraalVM 25, run
`:cli:hexana-wasm-tools:nativeIntegrationTest`. The module's `check` task also verifies that the installed runtime
dependency graph contains no scripting or MCP transport libraries.
