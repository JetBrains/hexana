# Webcil fixtures corrupted by a UTF-8 round-trip (2026-11-23)

**Symptom.** 15 Webcil tests failed in Patronus Core (`WebcilParserTest`, `WebcilAssemblyTest`,
`WebcilPdbCompanionTest`, `WebcilTableTabsTest`, `WebcilDiffTest`). The bare `sample-clr.webcil` test passed, but
everything reading the Wasm-wrapped `sample-clr*.webcil.wasm` failed: the `WbIL` header decoded, while the section
table right after it was garbage, so `ClrMetadataParser.parse` and the CodeView lookup returned null.

**Cause.** The four `*.webcil.wasm` blobs had been committed (since the first Webcil commit, not the rebase) after a
text decode/encode: every byte that was not valid UTF-8 became `EF BF BD` (U+FFFD). This is lossy, so it also broke the
wrapper's own code section (`fc 08` memory.init). Git did not cause it: the repo has no text/eol attributes for these files.

**Fix.** Regenerate them with the README recipe (`parsing/native-fixtures/README.md`, "Webcil (Blazor WebAssembly)
fixtures"; `dotnet run` in `src/clr/webcil`). The converter is deterministic: the bare `.webcil` came out byte-identical.

**Check for it.** If a binary fixture decodes partway and then turns to garbage, count `EF BF BD` sequences:
`python3 -c 'import sys;print(open(sys.argv[1],"rb").read().count(b"\xef\xbf\xbd"))' <file>`. Never copy binary
fixtures through text-based tools (editor buffers, agent Write tools, `$(...)` capture).
