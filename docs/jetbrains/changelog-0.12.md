---
title: Hexana 0.12 Release Notes
description: Release notes for Hexana 0.12 — static-library archive inspection (.a/.lib), Component Model diff and WAT view, full Component Model size breakdown, Exports/Imports row affordances with Find Usages, Android DEX support, and ZIP64 archives.
version: "0.12"
released: 2026-06-24
---

# Hexana 0.12 Release Notes

Released **2026-06-24**. No breaking changes from 0.11.1.

0.12 extends Hexana's analysis breadth across three fronts: it opens static-library archives (`.a` and `.lib`) for inspection with per-function disassembly of the extracted object files; it brings Component Model binaries into the existing diff and WAT viewer surfaces that 0.11.1 established for core modules; and it adds per-row affordances to the Exports and Imports tabs (tooltips, copy actions, and an Imports-side Find Usages that lists callers). Android DEX and ZIP64 round out the format coverage.

## Static-library archives

<!-- TODO: screenshot for static-library Members view -->

`.a` (Unix `ar`) and `.lib` (Windows COFF import library) archives now open in Hexana as a structured **Members** list. Each row names the member, reports its detected format (ELF / Mach-O / PE / COFF / short-import), and shows its size. The archive's own bookkeeping entries (symbol tables, long-names table) are hidden from the list.

Clicking a member extracts the object file to the IDE's temp folder and opens it in the matching disassembly view. Because the extracted file lands on disk as a real file, both the Capstone and llvm-objdump disassembly backends are available. The member tab is titled `archive-name/member-name`; when two members share a name, an index is appended to keep the titles distinct.

Archives are detected by their `!<arch>\n` content magic, not by extension, so renaming a file does not confuse detection. Plain MSVC import `.lib` files that do not carry the `!<arch>\n` header are left to their normal handler.

### Per-function disassembly of archive members

Relocatable object files (ELF `.o`, Windows COFF `.obj`/`.o`, Mach-O) lay out function symbols section-relative rather than at virtual addresses. Hexana now resolves each symbol's file offset through its owning section, so the virtualized Capstone view lists each named function individually rather than presenting only the whole `.text` section as a single block. Members whose symbols are stripped still fall back to disassembling code sections whole.

## Component Model diff and WAT view (0.12)

### Comparing Component Model binaries

**Compare WASM With…** (introduced in 0.11.1 for core modules) now accepts Component Model binaries. Comparing two components opens a submodule view listing each component's embedded submodules — core modules and nested components, found recursively — and classifies each as identical, modified, added, or removed. Clicking a changed pair opens a dedicated tab:

- A **core-module pair** reuses the existing Size Impact / Entities / WAT diff from 0.11.1.
- A **nested-component pair** recurses into the same submodule view.

Submodules are paired by content hash, with positional fallback when hashes do not match.

### WAT (virtualized) tab for Component Model binaries

A Component Model binary's code lives in embedded core modules. The WAT tab now discovers every embedded core module recursively (through nested sub-components) and renders the selected one with the same structured, lazily-parsed virtualized WAT view that core modules use: three-column offset / bytes / mnemonic layout, per-function navigation, and folding. When a component contains more than one core module, a selector switches between them.

This view is also available for in-memory component views, such as a submodule opened from a diff, which previously had no WAT tab.

## Component Model size breakdown (0.12)

The **Top** tab's size profiler now attributes every nested sub-component and embedded core module with its correct byte count, expanding on demand to internal sections and then to functions, recursively. In 0.11.1 and earlier, a composed component reported most of its bytes as a single opaque block because the profiler read only the first nested component section. The fix corrects a coroutine-cancellation bug in the lazy children generator: the generator was inheriting the already-cancelled flag from the tree-build coroutine and silently producing no children.

## Exports and Imports row affordances (0.12)

### Exports tab

Each row in the **Exports** tab gains:

- A **tooltip** showing the full export name (for names truncated by the column width).
- A **Copy Name** context-menu action.
- A **Navigate to Function in WAT** action (shown only for function exports) that switches to the WAT (virtualized) tab and scrolls to that function's body.

### Imports tab

Each row in the **Imports** tab gains:

- A **tooltip** showing the module-qualified name (`module.field`).
- A **Copy Name** action that copies the qualified name.
- For function imports, a **Find Usages** action in the context menu: a flat list of the functions that call that import, each entry navigating to the caller's body in the WAT (virtualized) tab. The list is capped at 15 entries. When there are more than 15 callers, or before the call graph finishes computing, a single entry opens the Caller Paths panel listing every usage. The call graph is built off the EDT so the menu stays responsive on large modules.

## Android DEX and ZIP64 (0.12)

- **Android DEX** — `.dex` files open with a basic classes-and-members view.
- **ZIP64** — archives using the ZIP64 extensions are now supported in the archive viewer.

## Changed

- Hexana's project-view actions (**Hex view**, **Open Archive**, **Compare WASM With…**, and the in-editor **Hex** / offset / structure actions) are now grouped under a single **Hexana** submenu. In IntelliJ IDEA the submenu sits at the top level of the project-view popup. In RustRover and CLion, whose project-view popup is a fixed list that drops third-party top-level contributions, the submenu appears under **Open In > Hexana**. Each action still controls its own visibility.
- **Compare WASM With…** can now be invoked from Search Everywhere and the keymap, not only from a selected `.wasm` file in the project view. With no `.wasm` pre-selected, it prompts for the first module and then the second.
- Stability improvements for debugging with Node.js.

## Fixed

- **Top tab tree nodes expand again.** Lazily-generated children (nested component / embedded core module nodes, and a core module's sections expanding into functions) silently showed nothing. The lazy children generator was inheriting the already-cancelled flag from the tree-build coroutine. The generator no longer inherits the cancellation state from the calling coroutine.

## Upgrading to 0.12

No breaking changes. All 0.12 additions are automatic or additive.

- **Static-library archives** are opened the moment you double-click a `.a` or `.lib` in the project view. No configuration is needed.
- **Component Model diff** becomes available on any Component Model binary when you invoke **Compare WASM With…** — the same action already in your keymap or project-view menu from 0.11.1.
- **Component Model WAT tab and size breakdown** appear automatically when you open a component binary; the existing tab layout gains the module selector and the corrected tree expansion.
- **Exports / Imports row affordances** appear in the existing tabs with no configuration.
- The disassembly used for archive member inspection is the same experimental Capstone backend as for other native binaries. No additional installation is required.
- Everything runs on your machine. No data is sent anywhere.

## Known limitations in 0.12

- Android DEX support is basic: classes and members are listed, but bytecode decoding and cross-reference navigation are not yet implemented.
- Archive member disassembly uses the existing experimental native-binary backend. Symbols in COFF `.obj` files compiled without `/Zi` or `/Z7` are not always resolved.
- Browser debugging still targets Chrome (via the Chrome DevTools Protocol) only; other browsers are not wired for debug.
- Carryover from 0.11: native-binary support is experimental; the Cranelift / Redline disassembler backend is experimental; Java integration covers Java sources only; quick-fixes are not yet wired on the WebAssembly Java or WIT inspections.

## Earlier releases

- [Hexana 0.11 release notes](changelog-0.11.md) — 0.11 (2026-06-11) and 0.11.1 (2026-06-17): module diff with structural matching, Kotlin/Wasm source navigation, Node.js and browser runtimes, GraalVM Native Image with embedded SBOM and OSV vulnerability reachability.
- [Hexana 0.10 release notes](changelog-0.10.md) — 0.10 (2026-05-27), plus the 0.10.1–0.10.3 patch line.
- [Hexana 0.9 release notes](changelog-0.9.md) — 0.9 (2026-05-07), 0.9.1 (2026-05-20).
