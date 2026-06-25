---
title: File Types Registered by Hexana
description: File types Hexana registers in the IntelliJ Platform — .wasm, .wat, .wit, native binaries (ELF/Mach-O/PE), JVM artifacts (.class, .jar, .war, .apk), static-library archives (.a/.lib), Android DEX (.dex), and JIT dumps.
version: "0.12"
---

# File Types

Hexana registers and detects the following file types in the IntelliJ Platform.

| File type | Extensions / detection | Editor | Implementation |
|---|---|---|---|
| `wasm` | `.wasm` | Hexana multi-tab viewer | `org.jetbrains.hexana.WasmFileType` |
| `wat` | `.wat` | IntelliJ editor with WAT language support | `org.jetbrains.hexana.wat.WatFileType` |
| `wit` | `.wit` | IntelliJ editor with WIT language support | `org.jetbrains.hexana.wit.WitFileType` |
| Native binary | ELF / Mach-O / PE magic bytes (any extension); `.elf`, `.so`, `.dylib`, `.bundle`, `.exe`, `.dll`, `.sys` | Hexana hex + structure + disassembly | `org.jetbrains.hexana.NativeBinaryFileType` |
| `binary` | `.bin` and other generic-extension fallbacks | Hex view | `org.jetbrains.hexana.BinaryFileType` |
| JVM class | `.class` | Three-tab class view (header, methods, constant pool) | Handled inside `HexanaFileEditorProvider` |
| JVM archive | `.jar`, `.zip`, `.war`, `.apk` (including ZIP64) | Hex view + searchable class list | Handled inside `HexanaFileEditorProvider` |
| Static-library archive (0.12) | `!<arch>\n` magic; `.a`, `.lib` | Members list + per-member disassembly | Handled inside `HexanaFileEditorProvider` |
| Android DEX (0.12) | `.dex` | Basic classes-and-members view | Handled inside `HexanaFileEditorProvider` |
| JIT dump | `.jit` | Three-pane symbol / native / bytecode view | Handled inside `HexanaFileEditorProvider` |

Hexana also registers a `fileTypeOverrider` (`HexFileTypeOverrider`) that claims `.wasm`, `.wat`, and `.wit` even when another plugin tries to register the same extension.

## `.wasm` — WebAssembly binary

The primary surface. Hexana replaces the default editor with its multi-tab viewer through `HexanaFileEditorProvider`. Tabs: Module, Imports, Exports, Functions, Top, Hex, WAT. See [`features.md`](features.md) for the full per-tab capability list.

Two file-size checkers run before the file opens:

- `WasmFileSizeChecker` — gates expensive operations for very large `.wasm` files.
- `WatFileSizeChecker` — sibling check for `.wat`.

When a `.wasm` opens, FUS records the `wasm.file.opened` event.

## `.wat` — WebAssembly Text format

WAT is the S-expression text representation of WebAssembly. Hexana provides full language support:

- Parser (`WatParserDefinition`) and PSI tree.
- Syntax highlighter (`WatSyntaxHighlighter`).
- Brace matcher (`WatBraceMatcher`).
- File-view-provider factory.
- Problem-highlight filter (`WatProblemHighlightFilter`).
- Use-scope optimizer (`WatScopeOptimizer`) — speeds up reference lookups on large WAT files.
- Documentation target provider.
- Find Usages handler factory.

WAT is *also* the rendered form Hexana produces inside the **WAT** tab of a `.wasm` file — the same language support applies in both surfaces.

## `.wit` — WebAssembly Interface Types

WIT is the interface description language of the WebAssembly Component Model. Hexana's WIT support is its most complete language implementation. See [`wit-language.md`](wit-language.md) for the full reference.

Quick summary of what `.wit` files gain:
- Parsing, semantic highlighting, brace matching, code folding, breadcrumbs.
- Five inspections (empty definition, world-name uniqueness, missing semicolon, gate, use-declaration names).
- Keyword and `@gate` completion.
- Find Usages, Rename validation, Goto Symbol.
- Code formatter with code-style settings page.
- Cross-file resolve via `WitComponentIndex` and `WitBuiltInDefinitionsContributor` (which seeds the index with built-in WIT types).
- Documentation provider.
- Element manipulators for `WitHandle`, `WitUsePath`, `WitUseNamesItem`, `WitIncludeNamesItem` — required by the platform's Refactor and Rename machinery.

## Native binaries — ELF, Mach-O, PE (experimental)

Hexana detects native executables and shared libraries by magic bytes — the file extension is checked first for the common shapes (`.elf`, `.so`, `.dylib`, `.bundle`, `.exe`, `.dll`, `.sys`), but extensionless or unusually named binaries are still recognised when their leading bytes match an ELF, Mach-O, or PE/COFF header.

When opened, a native binary uses the same multi-tab layout as a `.wasm` file:

- **Hex** tab — raw bytes with structure popups for the format-specific headers.
- **Structure** tab — sections, segments, dynamic symbols, imports, exports.
- **Disassembly** tab — multi-architecture decoding via the bundled Capstone WASM module: x86, x86-64, ARM, AArch64, RISC-V 32 / 64. Virtualised by instruction window, so multi-MB `.text` sections render incrementally.

The disassembly tab has a switchable execution backend (bytecode AOT or Cranelift native). See [`disassembler-backends.md`](disassembler-backends.md).

**GraalVM Native Image** (0.11): native binaries produced by `native-image` are recognised by their SubstrateVM fingerprint and carry a **Native Image** badge. When they embed a CycloneDX SBOM (`--enable-sbom`), an additional **SBOM** tab lists the retained components and can overlay OSV vulnerabilities. See [`features.md`](features.md#graalvm-native-image-011).

## JVM `.class` files

`.class` files open with a three-tab view:

- **Header** — magic, major/minor version, flags, this/super class names.
- **Methods** — every method with its decoded bytecode.
- **Constant pool** — full constant-pool dump with cross-references.

This is the same view used for individual entries when browsing a `.jar` or `.apk`.

## JVM archives — `.jar`, `.zip`, `.war`, `.apk`

Archives open with a hex view on top and a searchable, sortable class list below. Click any entry to open it in a nested tab using the `.class` view above. The list supports filtering by name and sorting by size, name, or position in the archive.

ZIP64 archives (entries and central-directory records using the ZIP64 extension fields) are supported as of 0.12.

## Static-library archives — `.a`, `.lib` (0.12)

<!-- TODO: screenshot for static-library Members view -->

Unix `ar` archives (`.a`) and Windows COFF import libraries (`.lib`) open as a **Members** list. Detection is by `!<arch>\n` content magic, not extension, so a renamed archive is still recognised and a plain MSVC import `.lib` without that header is left to its normal handler.

Each row in the Members list shows the member name, its detected object format (ELF / Mach-O / PE / COFF / short-import), and its size. The archive's own bookkeeping entries (symbol tables, long-names table) are hidden. Clicking a member extracts the object file to the IDE's temp folder and opens it in the matching disassembly view; because the extracted file is a real on-disk file, both the Capstone and llvm-objdump backends are available. The member tab is titled `archive-name/member-name`; duplicate names receive an index suffix.

For relocatable object files (ELF `.o`, Windows COFF `.obj`/`.o`, Mach-O), the virtualized Capstone view lists each named function individually, with file offsets resolved through the owning section. Stripped members fall back to whole-section disassembly.

## Android DEX — `.dex` (0.12)

`.dex` files open with a basic classes-and-members view listing the classes and their methods.

## JIT dumps — `.jit`

Hexana ships a bundled JVMTI agent (`libjitview`). When attached to a Java run configuration, it hooks `CompiledMethodLoad` and writes a `.jit` dump on shutdown. Opening the dump gives:

- A symbol tree on the left.
- Per-method native disassembly on the top right.
- Decoded JVM bytecode and the inline tree on the bottom right.

See [`run-and-debug.md`](run-and-debug.md) for how the agent is wired into a run configuration.

## Generic binary — `.bin`

A catch-all binary file type for content that doesn't match ELF, Mach-O, PE, or a known JVM artifact. These open directly in Hexana's hex view (the **Hex** tab presented standalone). The viewer supports the same selection, search, and structure-popup features as the hex tab inside a `.wasm` editor.

## Default editor selection

`HexanaFileEditorProvider` returns a Hexana editor for `.wasm`, native binaries (detected by magic bytes), `.class`, `.jar` / `.war` / `.apk` / `.zip` (including ZIP64), static-library archives (detected by `!<arch>\n` magic), `.dex`, and `.jit`. For `.wat` and `.wit`, the platform's default editor (with Hexana's language support applied) is used. For the generic binary type, Hexana's hex view is the default editor.

If you have another plugin that conflicts on `.wasm` / `.wat` / `.wit`, `HexFileTypeOverrider` ensures Hexana wins. To disable the override, uninstall or disable the Hexana plugin.
