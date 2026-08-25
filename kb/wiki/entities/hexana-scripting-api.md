# Hexana scripting API (custom script tabs)

The JavaScript surface a user writes a custom script tab against, and the
single-definition/four-adapters structure that keeps it consistent.

## Why it was rebuilt

Before this, the surface was two globals — `binary` and `print` — and it was
effectively unwritable without reading Kotlin sources:

- **`binary` had no stable shape.** It was the internal `BinaryProvider` sealed
  hierarchy, so its members changed per binary format and there was no
  format-agnostic entry point.
- **Four hosts disagreed about what `binary` was.** The sandboxed GraalJS runtime
  (`mcp:scripting`, used by the `run-script` CLI and the `run_binary_script` MCP
  tool) exposed only `bytes`/`strings`/`sizeAnalysis`; the IntelliJ script tab
  reflection-proxied *every* provider property; the VS Code webview and the
  webapp parser worker each hand-maintained their own per-format wrapper lists
  and exposed different subsets again.
- **The reference was a 275 KB reflection dump** of internal Kotlin types, and the
  two Add-Script-Tab dialogs hand-duplicated a four-line summary beside it.
- **Tabular output relied on undocumented CSV/TSV sniffing.**

## Shape

One definition in `plugins-shared/src/commonMain/kotlin/.../shared/scripting/`,
bound by thin per-host adapters:

| File | Role |
| --- | --- |
| `ScriptValue.kt` | Host-neutral lazy value tree (`Obj`/`Arr`/`Fn`/`Host`), plus `ScriptArgs` coercion. |
| `ScriptBinaryApi.kt` | Builds the `binary` global from a `BinaryProvider`. |
| `ScriptOutput.kt` | `print`/`table`/`row`, including TSV encoding and cell escaping. |
| `ScriptOutputBlocks.kt` | Segments the output line stream back into text/table blocks, and filters them. |
| `ScriptApiReference.kt` | Source of truth for docs: renders the `.d.ts`, both dialogs' API listing, the starter template, the example list. |
| `ScriptValueJs.kt` (jsMain) | `ScriptValueJsBinder` — binds the tree into plain JS objects with lazy, memoized getters. |

Adapters:

- **JVM/GraalJS** — `mcp/scripting/ScriptValueGraalProxy.kt` (+ `HostObjectProxy.kt`
  for `binary.raw`). Used by the CLI, the MCP tool, and — since this change — the
  IntelliJ script tab, which now delegates instead of building its own context.
- **Browser** — `ScriptValueJsBinder` in the VS Code webview (`web-ui/.../ScriptTab.kt`)
  and the webapp parser worker (`ParserWorkerMain.kt`), each supplying its own
  `wrapHost` for the raw escape hatch.

## Design decisions worth remembering

- **`binary.tab(name)` reuses `TableTabsProviderRegistry`** — the same registry the
  IDE and VS Code UIs render from. That is what makes "what can I read?" answerable
  as "whatever tab you can see", for every format, with the column names already on
  screen. It is also why laziness is mandatory: a `Functions` table can have
  hundreds of thousands of rows.
- **Curated names must not shadow provider members.** `sections`, `functions`,
  `imports`, `exports`, `header`, and `symbols` already exist on providers with
  format-specific shapes, so the curated surface deliberately does not claim them
  (`structure` and `tab("Functions")` are the equivalents). Claiming one would
  silently change its shape for an existing script.
- **Fall-through, not replacement.** `ScriptValue.Obj.hostFallback` resolves any
  uncurated member against the provider — via the prototype chain in JS, via
  `HostObjectProxy` on the JVM. This is what keeps pre-v1 scripts working, and it
  incidentally *gave* the CLI and VS Code the format-specific members they never
  had.
- **`table`/`row` emit tab-separated lines**, and the *renderers* segment that
  line stream back into blocks (`ScriptOutputBlocks.kt`) rather than each host
  sniffing the first two lines. The old per-host `detectDelimiter` chose one
  layout for the entire tab, so a script that printed a heading before its table
  got the heading and the tab-separated rows jumbled into a single column, and a
  text-only script got a table with an ID column. Segmentation is derived from
  lines, not carried beside them, because the webapp worker and the CLI/MCP
  result only ever transport lines.
- **Table blocks render through the product's own table control**, not a
  hand-rolled grid, so script tables get the sorting, per-row copy, and context
  menus every other tab has. That control assumes it owns its pane and sizes its
  virtualized body against a bounded parent height, so both hosts gained an
  opt-in content-sized mode (`Table(maxVisibleRows = …)` on the JVM,
  `SearchableTable(maxBodyHeight = …)` in the webview). Two things also had to be
  made opt-out once several tables share one pane: the JVM table's initial focus
  request, and the webview table's **window-level** Cmd+F binding — otherwise
  every table in the document opens its search at once.
- **Filtering matches content only.** The pane filter never matches column
  headers (a header match would keep every row and read as "the filter did
  nothing") and never removes them (a surviving table still has to read as a
  table).
- **Comma-separated output is still whole-output only.** The run-based rule
  applies to tabs; applying it to commas would turn any two consecutive prose
  lines that happen to share a comma count into a table. CSV keeps the
  pre-existing "first two lines agree → one table" behavior.
- **Docs cannot drift.** `ScriptApiReferenceTest` asserts, in both directions, that
  the documented shapes match what `scriptBinaryApi` exposes. Adding a member fails
  the build until it is documented.
- **The tool guidance is not shared.** `mcp/tools-generator` is deliberately a pure
  contract catalog with no `plugins-shared` dependency, so the `run_binary_script`
  guidance text is written out by hand in `ScriptingTools.kt` (its owner per that
  module's boundary) rather than generated. Changing it moves the reviewed
  `EXPECTED_SEMANTIC` fingerprint in `HexanaToolCompatibilityTest`.

## Security note

The IntelliJ script tab previously ran user `.js` with `HostAccess.ALL` and
`allowHostClassLookup { true }` — arbitrary Java from a script file. Delegating to
`mcp:scripting` closed that: no host class lookup, no IO, no process or thread
creation, no native access, a timeout, and bounded output. `binary.raw` is
read-only *data* exposure through `ProxyObject`s (public properties only, no
methods, no class handles), not host access.
