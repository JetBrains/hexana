# Hexana

A WebAssembly and binary analysis tool for JetBrains IDEs and Visual Studio Code.

![Hexana opens a `.wasm` file with the structure tabs visible](doc/images/idea/01-wasm-overview.png)

Hexana opens compiled artifacts — `.wasm`, ELF, Mach-O, PE, JVM
`.class`, archives, JIT dumps — as structured, navigable documents
inside the IDE. Section-level browsing sits next to a raw hex view;
WebAssembly modules render as editable WAT alongside their imports,
exports, type signatures, and DWARF debug info; native binaries
(experimental) get multi-architecture disassembly via a bundled
Rust/WASM module that needs no host toolchain.

## Features

### WebAssembly

- Section browser with imports, exports, function types, code
  bodies, GC types, and (when present) DWARF 4/5 debug info.
- WIT (Component Model) interface support for nested module trees.
- Editable WAT view: inline row editing, length-changing
  insertions / deletions, IDE-keymap undo / redo, sidecar
  persistence that restores in-progress edits on next open.
- Size profiler tab showing where the bytes go — by function, by
  data segment, by section — with sorting and scrolling for
  multi-megabyte modules.
- Run configurations launching modules under Wasmtime, WAMR,
  GraalWasm, Node.js, or the browser (Chrome). Per-export
  Run / Debug arrows on the Exports tab.
- Source-level debugging (experimental, requires LLVM 22.1 or
  newer): line breakpoints, local variable inspection backed by
  DWARF, and continue / step controls via wasmtime or WAMR.

### Native binaries (experimental)

- ELF, Mach-O, PE detection from magic bytes — `.elf`, `.so`,
  `.dylib`, `.bundle`, `.exe`, `.dll`, `.sys`, and similar
  extensions land in the same structured viewer.
- Multi-architecture disassembly for x86, x86-64, ARM, AArch64,
  RISC-V 32 and 64. The disassembler ships as a bundled
  Rust / WebAssembly module — no host `objdump`, Capstone, or
  similar toolchain required. Virtualised rendering keeps the
  view responsive on multi-MB `.text` sections.
- GraalVM Native Image executables and shared libraries (anything
  produced by `native-image`) are recognised by their SubstrateVM
  fingerprint and tagged with a **Native Image** badge — the
  tooltip reports the GraalVM version, edition, and target
  platform. Detection survives release stripping.

#### Disassembler backend: AOT (default) and Redline (experimental)

Two paths exist for executing the bundled Capstone WASM module:

- **Bytecode AOT (default)** — at build time, the Capstone module is
  lowered to JVM bytecode and shipped alongside the plugin. Pure-JVM
  execution, no native binaries, works on every platform the IDE
  itself runs on.
- **Cranelift / Redline (experimental)** — the same module is
  compiled to native machine code at build time, one binary per
  (OS, architecture), and loaded at runtime via Project Panama FFM
  (Java 25+) or jffi. Faster on compute-heavy decode workloads on
  large binaries; falls back to the bytecode path transparently on
  platforms where no native slice is available.

To switch backends at runtime, open **Help → Find Action…** (`Cmd+Shift+A`
on macOS, `Ctrl+Shift+A` on Linux / Windows), type `Registry`, and
toggle:

| Registry key | Effect |
|---|---|
| `hexana.disassembly.backend.redline` | When `true`, use the Cranelift native backend. When `false` (the default), use the bytecode AOT. |
| `hexana.disassembly.perf.logging` | When `true`, emit `[disasm-perf]` timing lines to `idea.log` per decoded chunk. Useful when comparing the two backends on a specific binary. |

Backend selection takes effect on the next disassembly panel open
(or via the panel's Retry button). Both backends produce the same
disassembly output; the choice is a pure performance trade-off.
The Capstone backend itself is non-thread-safe, so calls are
serialised through a single-slot dispatcher inside the plugin —
the in-flight count is surfaced as a `Disassembling: N` widget in
the IDE status bar.

#### GraalVM Native Image SBOM and vulnerability reachability

Native Image binaries built with `--enable-sbom` (default-on in
Oracle GraalVM for JDK 25+) carry an embedded CycloneDX software
bill of materials. Hexana shows an **SBOM** badge and a dedicated
**SBOM** tab: a metadata header (subject, timestamp, generating
tool) over a searchable, sortable component table — type, group,
name, version, licence, PURL — with a **View raw JSON** action for
the decompressed document.

The tab can match those components against the OSV vulnerability
database and overlay known CVEs with CVSS severity, the fixed
version, and an advisory link. Because a component only appears in
the SBOM if `native-image` retained it, every CVE shown is for code
that actually survived dead-code elimination; binaries built with
`--enable-sbom=class-level` refine this to "vulnerable class
retained" vs "eliminated by DCE". Matching is offline by default —
the OSV database is downloaded once and the component list never
leaves your machine — with an opt-in toggle to also query osv.dev
online. Off until enabled under **Settings → Tools → Hexana**.

### JVM artifacts

- `.class` files render as a three-tab view: header, methods
  (with decoded bytecode), constant pool.
- `.jar`, `.zip`, `.war`, `.apk` archives open with a hex view on
  top and a searchable, sortable class list below; click an entry
  to open its contents in a nested tab.
- `.jit` dumps: a bundled JVMTI agent attaches to any Java run
  configuration, records compiled methods, and Hexana opens the
  resulting `.jit` file with a symbol tree plus per-method
  native disassembly and JVM bytecode side-by-side.

### Language support

- **WAT** — PSI-backed parser, syntax highlighting, brace matching,
  find-usages, hover documentation.
- **WIT** — inspections, formatting, completion, go-to-definition,
  indexed across the project.
- **Java** — completion and navigation for GraalWasm and Chicory
  APIs (`org.graalvm.polyglot.*`, `com.dylibso.chicory.*`).
- **JavaScript / TypeScript** — type inference for
  `WebAssembly.instantiate(...)` imports and exports, resolved
  from the referenced `.wasm` file.

### AI integration

An MCP (Model Context Protocol) server exposes Hexana's analyses —
function lists, type signatures, data segments, module structure —
to AI assistants. The server runs over stdio, HTTP, or embedded
inside the IDE, and is compatible with Claude Desktop, Claude
Code, and any other MCP-aware client.

## Install

### JetBrains IDEs

Open **Settings → Plugins → Marketplace**, search for `Hexana`,
click **Install**, and restart the IDE.

Compatible with IntelliJ IDEA, WebStorm, PhpStorm, PyCharm,
RustRover, GoLand, CLion, and Rider on the 2024.1 release line and
newer.

### Visual Studio Code

Open the **Extensions** sidebar (`Cmd+Shift+X` /
`Ctrl+Shift+X`) and search for `Hexana WebAssembly`. The same
extension is published to the
[Open VSX Registry](https://open-vsx.org/extension/JetBrains/hexana-wasm)
for editors that don't use the Microsoft Marketplace (Cursor,
Windsurf, VSCodium).

## Quick tour

### Top — size profiler

![Size profiler with sortable columns](doc/images/idea/02-top-tab.png)

Click any column header to sort. Useful first stop when "the
binary got bigger" — the top of the list is where the bytes went.

### WAT viewer

![Virtualised WAT viewer with section shortcut bar](doc/images/idea/03-wat-virtualized.png)

Sections fold individually; the shortcut bar at the top jumps
directly to Function, Data, Import, or Export ranges. Inline row
editing rewrites WASM bytes on commit:

![Inline row edit](doc/images/idea/04-wat-row-edit.png)

Length-changing edits cascade through the encoder; **Discard**
reverts; **Finalize** writes the edited bytes to a sibling
`<name>.edited.wasm` file.

### Data section view modes

![Data section with u8 / ascii toggle](doc/images/idea/05-wat-data-modes.png)

Switch between `u8`, `u16`, `u32`, `u64`, `ascii`, and `utf8`
renderings per data chunk. Expand and collapse bodies
independently.

### Native binaries

![Disassembly view of an ELF binary](doc/images/idea/09-native-elf.png)

Symbol-driven function discovery first, with a fallback to the
executable section's raw bytes when symbols are stripped. The
disassembly view virtualises by instruction window, so even
multi-megabyte `.text` sections render incrementally.

### GraalVM Native Image

![Information bar with the Native Image and SBOM badges](doc/images/idea/14-native-image-badge.png)

Executables and shared libraries produced by `native-image` are
recognised by their SubstrateVM fingerprint and tagged with a
**Native Image** badge. When the binary embeds a CycloneDX SBOM
(`--enable-sbom`), an **SBOM** tab lists every retained component
and can overlay known CVEs from the OSV database:

![SBOM tab with the vulnerability overlay](doc/images/idea/13-sbom-tab.png)

Severity, the fixed version, and an advisory link sit next to each
affected component. Matching is offline by default; enable online
osv.dev lookups under **Settings → Tools → Hexana**.

### Java archives and class files

![JAR file: hex on top, searchable class list below](doc/images/idea/07-jar-class-list.png)

Click a class to open it in a nested tab; the same three-tab
view works for `.class` files directly:

![Three-tab class view](doc/images/idea/06-class-file-view.png)

### JIT dumps

![Three-pane JIT view](doc/images/idea/10-jit-view.png)

Symbol tree on the left, native bytes for the selected method on
the top right, decoded JVM bytecode and inline tree on the bottom
right.

### Search Everywhere

![Search Everywhere finds a WASM export](doc/images/idea/08-search-everywhere-exports.png)

`Shift+Shift` surfaces `.wasm` exports, `.wit` declarations, and
component-model interfaces alongside regular code symbols.

### Run and debug

![WASM run configuration dialog](doc/images/idea/11-wasm-run-config.png)

Right-click a `.wasm` file or click the Run arrow next to an
export. Pick Wasmtime, WAMR, or GraalVM. Debug under wasmtime or
WAMR pauses on line breakpoints set in the source file the WASM
was compiled from (DWARF required).

### Information bar

![Information bar with proposal pills](doc/images/idea/12-info-bar-proposals.png)

The strip across the top of every binary editor surface reports
the file size, the module kind, and which WebAssembly proposals
the binary uses. Hover the size badge for a per-section breakdown.

### Visual Studio Code

The extension exposes the same hex + structure surfaces:

![Hex viewer in VS Code](doc/images/vscode/01-hex-viewer.png)

The Exports tab carries per-export Run / Debug arrows:

![Exports tab in VS Code](doc/images/vscode/02-exports-tab.png)

DWARF-backed debugging shows source locals at every paused frame:

![Debug session with DWARF locals](doc/images/vscode/04-debug-breakpoint-variables.png)

Native binaries open with the same hex + structure layout:

![Native binary in VS Code](doc/images/vscode/06-native-binary.png)

`.jar` and `.zip` archives open with the same hex-plus-entry-list
layout:

![JAR archive in VS Code](doc/images/vscode/08-jar-class-list.png)

Run configurations can target Node.js and the browser in addition
to Wasmtime, WAMR, and GraalWasm — running only; Node.js and
browser debugging is not yet wired up in VS Code.

## Usage analytics

Official release builds of Hexana send anonymous usage analytics to JetBrains (via PostHog) by default: which
features are used, coarse file-type buckets and sizes, and the host IDE or editor and OS. No file contents, file
names or personal data are sent. You can opt out at any time:

- JetBrains IDEs: turn off **Send anonymous usage analytics** in the Hexana settings.
- Visual Studio Code: set `hexana.enableStatistics` to `false`, or disable VS Code telemetry.
- Standalone MCP server: `--telemetry-enabled false` or `HEXANA_MCP_TELEMETRY_ENABLED=false`.
- CLI products: `--telemetry-enabled false` or `HEXANA_CLI_TELEMETRY_ENABLED=false`.
- Web app: use the **Anonymous usage analytics** toggle on the page.

Builds from source do not report anything: the analytics key is injected only by the official release pipeline, from the
`hexana.posthog.apiKey` Gradle property or the `HEXANA_POSTHOG_BUILD_API_KEY` environment variable, together with
`hexana.release=true` (or `HEXANA_RELEASE_BUILD=true`) so a release build without the key fails. A build without the key
has analytics disabled. See [JetBrains Privacy Notice](https://www.jetbrains.com/legal/docs/privacy/privacy/).

## Documentation

- [User guide](doc/) — work in progress.
- [WebAssembly proposal tracker reports](reports/) — regenerated
  from the upstream proposals repository on a schedule.
- [Component Model interface notes](doc/components/).
- [DWARF parser notes](doc/dwarf/).
- [Exception handling notes](doc/exception-handling/).

## Building from source

```bash
git clone https://github.com/JetBrains/hexana.git
cd hexana

# Launch a sandbox IDE with Hexana loaded
./gradlew :idea-plugin:runIde

# Run all tests
./gradlew test

# Run only the WASM parser tests
./gradlew test --tests "*.wasm.*"

# Build a distributable JetBrains plugin zip
./gradlew :idea-plugin:buildPlugin

# Build the VS Code extension VSIX
./gradlew :vscode-plugin:assembleExtensionAssets
cd vscode-plugin && npm install && npm run compile && npm run package
```

Requirements: JDK 21 (Hexana bundles the JetBrains Runtime as the
build toolchain), Node.js 18 or newer for the VS Code surface, Rust
plus the `wasm32-unknown-unknown` toolchain only when rebuilding
the disassembly module (pre-built artefacts ship in the repo).

## Contributing

Issues and pull requests welcome at
[github.com/JetBrains/hexana](https://github.com/JetBrains/hexana).

Before opening a PR:

```bash
./gradlew detekt          # static analysis
./gradlew ktlintCheck     # code style — max line length 140
./gradlew verifyPlugin    # IDE compatibility check
```

The repository follows a modular Gradle layout: parsers under
`wasmParser/`, `parsing/`, and `debugInfo/`; binary providers under
`binaryProvider/`; disassembly under `disassembly/`; the IDE plugin
under `idea-plugin/`; the VS Code extension under `vscode-plugin/`;
the MCP server under `mcp/`; and the bundled Rust crates under
`wasm-modules/`.

## License

See [LICENSE](LICENSE).