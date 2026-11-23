# Knowledge wiki index

Durable, cross-session knowledge for this repository. See the root `AGENTS.md` for the
consult-first / write-back rules.

## Entities

- [[entities/ui-driver-remote-classloaders]] — how the IDE-side Driver picks a classloader for
  `@Remote` refs, and why run/debug UI tests resolve `RunContentManager` through the Hexana plugin.
- [[entities/hexana-scripting-api]] — the custom script tab surface (`binary`, `print`,
  `table`, `row`) and how each host binds it.
- [[entities/wasm-runtime-compatibility]] — the Wasm runtime compatibility table: proposal
  detection, `.hexana/runtimes.json`, the runtime-compatibility problems, and the Compatibility tab.
- [[entities/dwarf-outline-model]] — the shared row model behind the `.debug_*` editor tabs:
  node ids, deferred children, whole-tree search, and the grouping rule both editors follow.
- [[entities/pcap-http-replay]] — re-sending captured HTTP requests: request reassembly and
  `.http` rendering in `parsing/pcap`, IntelliJ replay dialog and HTTP Client scratch export.
- [[entities/binary-provider-selection]] — the one factory that turns bytes plus a name into a
  `BinaryProvider`, why gzip stays outside it, and the per-host lists that decide which files
  reach the editor in the first place.
- [[entities/scripted-binary-providers]] — `hexana.defineProvider` / `defineTab` from
  `.hexana/providers/*.js`: base+overlay model, `ParserRef`, trust gating, both runtimes.
- [[entities/file-parser-associations]] — per-file parser override (`.hexana/file_associations.json`):
  `BinaryParser` ids, the format-badge picker in both editors, settings pages, live reload.
- [[entities/binary-opened-format-analytics]] — how `binary_opened.format` is derived, the
  `generic_ext` / `detected_format` / `generic_header` breakdown, and supported formats misreported as `generic`.
- [[entities/partial-views]] — files of any size: `LargeBytes` + chunked mmap, 64-bit ranges/selections
  (`NativeByteRange` Int views throw past 2 GiB), which parsers read the whole file, VS Code paged hex
  view and scaled scroll model, `ParseCoverage` (when a large file warns), VS Code fetch rounds
  (`SparseLargeBytes`), read-only rule, sparse-file tests.
- [[entities/ml-model-formats]] — GGUF and safetensors: `parsing/mlmodel` parsers, content detection
  and host routing, reference-writer fixtures, why `GgufValue` is flat, and the >2 GB limitation.

- [[entities/binary-inspections]] — the shared problems model (`BinaryInspection` →
  `BinaryProblem`) behind the Problems tab, the toolbar count badge, and `inspect-binary`;
  what was migrated into it and why unresolved native records are excluded.
- [[entities/editor-tabs-dock]] — IntelliJ and VS Code editors: analysis tabs docked below or right of the hex
  view (global setting, vertical tab list, expand), and narrow-pane fixes for tables and virtualized listings.
- [[entities/binary-diff-provider]] — the shared diff model (`BinaryDiffProvider`): tabs,
  detail pane, navigation requests, how IntelliJ and VS Code host it, the hex viewer in the
  IntelliJ diff window (`diff.DiffTool`), native per-function and .NET per-method IL diff.
- [[entities/apk-emulator-run]] — the trivial APK Run button: SDK/AVD detection gate, boot →
  install → launch flow shared by IntelliJ and VS Code via `AndroidEmulatorRun`, and what it
  deliberately does not do.
- [[entities/dex-reveal-in-smali]] — DEX Classes → Smali navigation: shared
  `withDexRevealInSmali`/`withExtraContextMenuItems`, IntelliJ state-flow and web
  composition-local host bindings, top-level-only tree-table context menus.
- [[entities/apk-classes-tab]] — APK Classes tab + "Reveal in DEX" in IntelliJ and VS Code:
- [[entities/clr-metadata]] — .NET assemblies: ECMA-335 metadata decoding in `parsing/pe/clr`, the hardening
  the mutation test forced, the `.NET` / Types / Methods / `.NET Metadata` tabs, the virtualized IL
  view, when native Disassembly tabs are hidden, and ReadyToRun code → method attribution behind the
  managed Top tab.
  shared async collection, lazy tabs, entry-editor reuse, and the cross-host "select table row" request.
- [[entities/blazor-wasm-feasibility]] — Blazor WASM publish anatomy (Webcil-wrapped assemblies,
  Mono `dotnet.native.wasm` + `.js.symbols`, .NET 10 boot JSON); Webcil → CLR tabs, symbol-map names, the Blazor App and .NET AOT tabs and the Webcil diff implemented,
  what remains (Top tab, MCP/CLI, bare .webcil).
- [[entities/pdb-support-feasibility]] — what DWARF-parity for Windows PDB means (native line info
  only), the seams, MSF/DBI/C13 work breakdown, matched `sample.exe`/`sample.pdb` fixture, risks.
- [[entities/pdb-symbols]] — Windows PDB (`debugInfo/pdb`, MSF) and Portable PDB (CLR metadata + `#Pdb`):
  providers, content-only routing, tabs; PE ↔ PDB matching, companion loading, source lines in disassembly/IL.
- [[entities/dependency-security-audit]] — where external dependency versions live, how to
  audit them (npm audit + OSV batch API), and the 2026-10-27 audit results.
- [[entities/ci-env-parameters]] — which `HEXANA_*` env variables each CI script requires instead of hardcoded internal
  targets, and what each does when one is missing.
- [[entities/analytics-build-key]] — how the PostHog key is injected at build time, the blank-key = analytics-off rule, the
  opt-in release guard, and the TeamCity params.
- [[entities/third-party-notices]] — the shared notices generator, per-artifact inputs (IDEA ZIP, VSIX, webapp, MCP/CLI),
  where licence texts come from, and what fails when a dependency has no reviewed text.
- [[entities/leak-guard]] — what a clean-machine build of the public part needs and why (`CONTRIBUTING.md`), and the
  two leak-guard checks (private TeamCity/Patronus, public GitHub Action).
- [[entities/top-size-tab]] — the shared Top (size) tab: nested modules/components expand under their parent, size-based
  treemap depth, absolute offsets, the shared-slice-cursor gotcha, and the Components tab listing every nested component.

## Sources

- [[sources/hexana-0-20-whatsnew]] — 0.20 card selection, validated claims,
  capture policy, and generated-page runtime contract.
- [[sources/webassembly-features-json]] — WebAssembly's `features.json`: shape, the four-way
  `status` union, version-string quirks, and its HTTP/ETag characteristics.
- [[sources/public-tip-hygiene]] — rules for keeping the tip publishable: env-var test locations, allowed
  placeholder paths, no internal references or partner names, no derived dumps or build output in git.
- [[sources/webcil-fixture-utf8-corruption]] — why the wrapped Webcil fixtures broke (U+FFFD round-trip), how to detect and regenerate.
- [[sources/third-party-fixture-provenance]] — where the third-party Wasm/ELF/USD fixtures came from,
  how provenance was established (hash identity, strings, rate limits) and the open licence findings (HEXANA-33).
