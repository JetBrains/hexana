# Keeping the tip publishable

Conventions that came out of the clean-up before the public GitHub release. They apply to new code and docs too.

## Local paths and tools

- No machine-specific absolute path in a text source, test or script. A test that needs a local checkout or toolchain
  reads its location from an environment variable and skips when it is unset:
  `HEXANA_CORPUS_DIR` (spec testsuite corpus), `HEXANA_SAMPLE_COMPONENT_MODEL_DIR` (sample component-model project),
  `ZEPHYR_SDK_INSTALL_DIR` (Zephyr SDK, for the GDB DAP smoke test), `HEXANA_BLAZOR_FRAMEWORK_DIR` /
  `HEXANA_BLAZOR_RUNTIME_NATIVE_DIR` / `HEXANA_BLAZOR_AOT_FRAMEWORK_DIR` (Blazor WebAssembly publish output, runtime pack and
  an AOT publish, `BlazorPublishOutputTest`).
- Build scripts under `examples/scripting-playground/build/` default to `$HOME/wasi-sdk` and `wasm-tools` from `PATH`;
  `CLANG` and `LLD` configure `make_fdt.py`.
- Path-matching tests and KDoc use the generic `/Users/dev/...` placeholder. It is the only `/Users/<name>` form that
  may remain in text files.
- Text fixtures of the Zephyr corpus use `/home/user/...`. Binary fixtures keep whatever the toolchain embedded.

## Internal references

- Do not link chat threads or internal tracker items from code comments. State the reason the comment depends on.
- Do not name partners, customers or individuals in tests, fixture names or design specs. Describe the source as
  external feedback.

## Files that do not belong in git

- Derived text dumps of committed binaries (the old `skiko.wat` was a 93.7 MB print of `skiko.wasm` that no test read).
  Keep every tracked file well below GitHub's 100 MB limit.
- Build output (`target/`, `build/`) even when a `.gitignore` entry exists: check `git ls-files` before assuming.
- Specification PDFs: link the official DWARF and WebAssembly sources (see `doc/README.md`).
