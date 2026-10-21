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
- [[entities/binary-opened-format-analytics]] — how `binary_opened.format` is derived, the
  `generic_ext` / `detected_format` / `generic_header` breakdown, and supported formats misreported as `generic`.

- [[entities/binary-inspections]] — the shared problems model (`BinaryInspection` →
  `BinaryProblem`) behind the Problems tab, the toolbar count badge, and `inspect-binary`;
  what was migrated into it and why unresolved native records are excluded.
- [[entities/binary-diff-provider]] — the shared diff model (`BinaryDiffProvider`): tabs,
  detail pane, navigation requests, how IntelliJ and VS Code host it, and the hex viewer in the
  IntelliJ diff window (`diff.DiffTool`).
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
- [[entities/pdb-support-feasibility]] — what DWARF-parity for Windows PDB means (native line info
  only), the seams, MSF/DBI/C13 work breakdown, matched `sample.exe`/`sample.pdb` fixture, risks.
- [[entities/pdb-symbols]] — Windows PDB (`debugInfo/pdb`, MSF) and Portable PDB (CLR metadata + `#Pdb`):
  providers, content-only routing, tabs; PE ↔ PDB matching, companion loading, source lines in disassembly/IL.

## Sources

- [[sources/hexana-0-20-whatsnew]] — 0.20 card selection, validated claims,
  capture policy, and generated-page runtime contract.
- [[sources/webassembly-features-json]] — WebAssembly's `features.json`: shape, the four-way
  `status` union, version-string quirks, and its HTTP/ETag characteristics.
