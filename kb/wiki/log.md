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
