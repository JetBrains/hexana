# Wiki log

Append one line per write-back: date, page, what changed.

- 2026-09-04 — `entities/wasm-runtime-compatibility`, `sources/webassembly-features-json`,
  `index` — created. Design and upstream-schema notes for the Wasm runtime compatibility
  table (features.json ingestion, `.hexana/runtimes.json`, badge warnings, Compatibility tab,
  seven new detected proposals).
- 2026-09-04 — `entities/wasm-runtime-compatibility` — recorded the Compatibility tab's
  supporting-runtimes column in both its forms: leading `Supported in` over the whole
  catalog with nothing declared, trailing `Other runtimes` excluding the declared ones
  otherwise; plus the label format and the sort key.
- 2026-09-04 — `entities/wasm-runtime-compatibility` — added `RowActionKind` (docs vs run
  icon per host) and recorded the web-UI row-action/ID-column decoupling it uncovered.
- 2026-09-04 — `entities/wasm-runtime-compatibility` — added `PrologueActionDescriptor`:
  the Compatibility prologue now links to each host's runtime settings page instead of
  naming the project file, with a path fallback for hosts that have no settings UI.
- 2026-09-04 — `entities/wasm-runtime-compatibility` — recorded live tracking of
  `.hexana/runtimes.json`: the pure `RuntimeTargetsTracker` policy, why a save announces
  itself, and why only the Compatibility tab is re-resolved.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — recorded `badgedProposals`: a
  component whose only proposal is the component model gets no proposals badge, since the
  file-type badge already says it.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — component binaries now get a
  Compatibility tab; recorded the `tableTabs(provider, context)` default-drops-context trap
  that had hidden it.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — dropped `componentModel` detection
  entirely (upstream has support data for two entries, so every table column read `?`);
  recorded the general lesson that detectability alone does not justify a proposal.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — Compatibility prologue now links to
  webassembly.org/features as the data source; `prologueAction` became a list.
- 2026-09-09 — `entities/wasm-runtime-compatibility` — compatibility is now reported only
  against browsers and runtimes; `WasmFeatureCatalog.executionRuntimes` drops the catalog's
  `Tools` entries (including Hexana's own) from the supporting-runtimes columns and the
  settings runtime list, while `runtimes` stays complete for already-declared targets.
- 2026-09-09 — `entities/wasm-runtime-compatibility` — a Compatibility row whose every
  verdict is `?` collapses to one `No data` label and sorts last; recorded that the collapse
  is faked column-wise because `TableTabDescriptor` has no cell spanning.
- 2026-09-10 — `entities/dwarf-outline-model` — extracted the DWARF tab row model into
  `plugins-shared` so the VS Code webview could render the same tabs as IntelliJ; recorded the
  un-memoized-children contract, the `groupPanels` naming rule, and three known gaps (eager
  `.debug_info`, per-byte JS buffer allocation, the webapp worker route).
- 2026-09-16 — `sources/hexana-0-20-whatsnew`, `index` — recorded the 0.20 card selection,
  checked capture policy, and the generated What's New page runtime contract.
- 2026-09-16 — `sources/hexana-0-20-whatsnew` — refined the 0.20 capture contract: dark
  breakpoint gutter, command-entry Console GIF, and editor-only structural-regions capture.
- 2026-09-10 — `entities/dwarf-outline-model` — code review pass: `.debug_info` attribute rows
  addressed `.debug_abbrev` (shared abbrev declaration), DWARF64 `strp` never resolved, and the
  IntelliJ search index was built on the EDT; all three fixed, with regression tests pinning
  that attribute offsets fall inside `.debug_info` and differ per DIE.
- 2026-09-15 — `entities/hexana-scripting-api` — added the `open(path)` script global
  (opens another local binary as the same wrapper object as `binary`); recorded the
  `scriptGlobals` assembly point, why a host-dependent global stays bound and throws
  rather than being omitted (`window.open`), and that `open` reuses `BinaryInputLoader`
  + `AllowedFilePolicy` instead of introducing a file-access path of its own.
- 2026-09-15 — `entities/hexana-scripting-api` — added `binary.path`/`binary.dir`
  (members, not globals, so they compose with `open(...)`); recorded that null means
  "no file behind this binary", that the CLI/MCP path is the resolved one via the new
  `LoadedBinary.resolvedPath`, and that `binary.name` is now derived from whichever of
  name/path the host supplied — which fixed VS Code reporting a full fsPath as the name.
- 2026-09-15 — `entities/hexana-scripting-api` — `open(path)` now works in the VS Code
  webview: the script's generator suspends at the call, the extension host reads the file,
  and the driver resumes with the parsed binary (a failure arrives as `iter.throw`, so the
  script's own catch still sees it). Recorded `ScriptOpenPolicy`, the `(yield open(p))`
  precedence trap, and the transform's new function-body skip — which also fixed printing
  from a helper failing to compile in the browser host.
