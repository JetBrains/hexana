---
title: File Types Registered by Hexana (0.9)
description: The four file types Hexana registers in the IntelliJ Platform — .wasm, .wat, .wit, and generic binary.
version: "0.9"
---

# File Types

Hexana 0.9 registers four file types with the IntelliJ Platform.

| File type | Extensions | Editor | Language | Implementation |
|---|---|---|---|---|
| `wasm` | `.wasm` | Hexana multi-tab viewer | — | `org.jetbrains.hexana.WasmFileType` |
| `wat` | `.wat` | IntelliJ editor with WAT language support | `wat` | `org.jetbrains.hexana.wat.WatFileType` |
| `wit` | `.wit` | IntelliJ editor with WIT language support | `wit` | `org.jetbrains.hexana.wit.WitFileType` |
| `binary` | `.bin`, `.elf`, `.exe` | Hex view | — | `org.jetbrains.hexana.BinaryFileType` |

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

## Binary — `.bin`, `.elf`, `.exe`

A generic catch-all binary file type. These open directly in Hexana's hex view (the **Hex** tab presented standalone, without the WASM-specific tabs). The viewer supports the same selection, search, and structure-popup features as the hex tab inside a `.wasm` editor.

Future native-binary work (ELF / PE / COFF parsers and `llvm-objdump` integration) extends this surface; in 0.9 the type primarily exists so users can route binary files into Hexana's hex view from external openers without manual file-type association.

## Default editor selection

`HexanaFileEditorProvider` returns a Hexana editor for `.wasm`. For `.wat` and `.wit`, the platform's default editor (with Hexana's language support applied) is used. For the binary type, Hexana's hex view is the default editor.

If you have another plugin that conflicts on `.wasm` / `.wat` / `.wit`, `HexFileTypeOverrider` ensures Hexana wins. To disable the override, uninstall or disable the Hexana plugin.
