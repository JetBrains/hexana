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
| `ScriptGlobals.kt` | Assembles the globals every host binds; defines `open(path)` over `ScriptOpenPolicy`. |
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
- **Editor types are the generated reference, copied next to the script.**
  `ScriptApiReference.dts()` is compiled into both plugins, so attaching types is
  a write, not a resource copy, and what a user completes against is exactly what
  the docs describe. The `jsconfig.json` sidecar is the part that actually makes
  it work in VS Code — a standalone `.js` file has no automatic association with a
  sibling `.d.ts` — and is only written when the folder has no JavaScript project
  config of its own. IntelliJ needs no config: it applies global `declare`s from
  any indexed `.d.ts`. The JSON lives in `ScriptApiReference` rather than in the
  extension's TypeScript, and the webview passes it over the message bus, so it is
  defined once.
- **A host binds `scriptGlobals` and nothing else.** Globals used to be assembled per
  host (`binary` here, `ScriptOutput.bindings()` there), which is how a host could
  end up one global short. `scriptGlobals(provider, output, fileName, opener)` is now
  the single list, and `ScriptApiReferenceTest` compares the documented globals
  against it.
- **A host-dependent global declares a policy instead of being absent.**
  `ScriptOpenPolicy` has three cases and `open(path)` is the only member that needs
  it: `Inline` (the JVM reads the file inside the call), `Deferred` (the VS Code
  webview asks the extension host and the value is fed back), `Unsupported` (the
  browser web app, which only ever has the bytes the page pushed it). Even
  `Unsupported` keeps `open` *bound*: an unbound `open` in a browser resolves to
  `window.open`, so `open("/path/to.wasm")` would try to navigate instead of
  failing.
- **Deferred open works by suspending the script's generator.** The browser host
  already ran user scripts as a generator so output could stream; that same
  machinery is what makes an async file read look like a synchronous call.
  `open(path)` evaluates to a request object carrying `__hexanaOpenRequest`; the
  driver in `web-ui/.../ScriptTab.kt` recognises it as a yielded value, has the
  host read the bytes, parses them *in the webview* (bytes cross the message bus,
  never a provider), and resumes with `iter.next(binary)`. A failed read comes back
  as `iter.throw(...)`, so the script's own `try/catch` sees it exactly as on the
  JVM — which is what keeps the bundled comparison example working unchanged in
  every editor. Two things that are easy to get wrong here:
  - **The rewrite must parenthesise the call.** `yield` binds looser than member
    access, so `yield open(p).size` reads `.size` off the *request* and yields
    `undefined` — the host is never asked and the script silently sees nothing.
    Suspending calls are emitted as `(yield open(p))`; output calls keep the bare
    prefix, because their value is unused.
  - **`yield` is illegal inside a nested function**, so the transform now tracks
    function bodies and skips them. That also fixed a standing bug where a script
    printing from a helper failed to *compile* in the webview. An `open` inside a
    helper therefore cannot suspend, and its request object reports "call it at the
    top level" rather than reading as an empty object.
- **Where the file is, is the host's answer — and `name` is derived, not passed
  through.** `binary.path`/`binary.dir` are null whenever the binary has no file
  behind it (a ZIP entry, a module opened from a table, anything in a browser),
  because a plausible-looking path that resolves to nothing is worse than an
  admitted absence. The IDE already computed exactly this distinction for its
  providers (`if (inMemoryBuffer == null) myFile.toNioPathOrNull()`), the VS Code
  load message already carried `filePath`, and the CLI/MCP loader now returns
  `LoadedBinary.resolvedPath` — the *resolved* path, not the caller's string, so
  `--binary ./app.wasm` still yields a `dir` that `open(...)` can build on
  (`identity.path` keeps the caller's own spelling, which is what a caller matches
  its request against). `binary.name` is computed as the last segment of whichever
  of the two the host supplied, which also fixed a live divergence: VS Code passed
  `document.uri.fsPath` as the name, so one script reported a full path there and
  a bare name in the IDE and the CLI.
- **`dir` exists so scripts do not do separator arithmetic.** The sidecar pattern
  — the linker map beside an ELF, the previous build beside this one — is the
  reason `open` and these members ship together. A root keeps its separator
  (`/app.wasm` → `/`, `C:\app.wasm` → `C:\`), because trimming it would name a
  different directory, and `/` works as a join separator on every platform Hexana
  runs on.
- **`open` reuses the caller's own file policy rather than adding one.**
  `ScriptBinaryFileOpener` (in `mcp:scripting`) loads through `BinaryInputLoader` +
  `AllowedFilePolicy` — the same pair that decides where the caller's `binary` may
  come from. So it reaches exactly the files the host would already have loaded:
  unrestricted in the IDE and by default in the CLI, confined to the configured roots
  on a restricted MCP server. Results are memoized per path string (comparing in a
  loop costs one parse) and `HexanaScriptLimits.maxOpenedBinaries` (8) bounds how many
  distinct binaries one run can hold, since each stays in memory for the whole run.
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
`mcp:scripting` closed that: no host class lookup, no GraalJS IO, no process or
thread creation, no native access, a timeout, and bounded output. `binary.raw` is
read-only *data* exposure through `ProxyObject`s (public properties only, no
methods, no class handles), not host access.

`open(path)` is the one deliberate exception to "no file access", and a narrow one:
GraalJS IO stays off (`read()` and `load()` are still denied, and the sandbox tests
pin that), the path goes through `AllowedFilePolicy`, and what crosses into the
script is a parsed `BinaryProvider` — not a file handle, not arbitrary file text.
It therefore does not widen the trust boundary of any host: `run_binary_script`
already accepts an arbitrary `path` as its binary source under the same policy, so
`open` reads the same set of files by a different route. Restating this in the tool
guidance (which claimed "no file IO") is what moved `EXPECTED_SEMANTIC` in
`HexanaToolCompatibilityTest`.
