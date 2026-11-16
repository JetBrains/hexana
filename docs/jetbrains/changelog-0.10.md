---
title: Hexana 0.10 Release Notes
description: Release notes for the Hexana IntelliJ plugin 0.10 — editable WAT viewer, native binaries (ELF/Mach-O/PE), JVM artifacts, JIT viewer, and a switchable disassembler backend.
version: "0.10"
released: 2026-05-27
---

# Hexana 0.10 Release Notes

Released **2026-05-27**.

Hexana 0.10 is the largest functional release since 0.8. It adds editable binary documents, opens native binaries and JVM artifacts as first-class file types, ships a bundled JIT viewer, and introduces a switchable disassembler execution backend.

## What's new

### Editable binary documents

- **Inline WAT row editing.** Click a row in the WAT viewer to edit it in place. Length-changing edits are encoded back into the underlying WASM via an append-and-redirect scheme, and a Discard / Finalize button pair on the editor surface lets you revert or commit the edits to a sibling `<name>.edited.wasm` file.
- **Hex-cell editing** with the same Discard / Finalize semantics.
- **IDE-keymap Undo / Redo** wired into the standard editor actions.
- **Sidecar persistence** — in-progress edits are saved to a sidecar file and restored on next open, even across IDE restarts.

### Virtualised WAT viewer

- Per-entry breakouts for **Function**, **Data**, **Import**, and **Export** segments, individually foldable.
- View modes for data chunks: `u8`, `u16`, `u32`, `u64`, `ascii`, `utf8` (toggleable per chunk).
- **Go-To-Declaration**, **Go-To-Symbol**, **Back**, and **Forward** through the IDE keymap.
- **Section shortcut bar** at the top of the viewer for fast jumps to Function / Data / Import / Export ranges.

### Native binaries (experimental)

Hexana now detects and opens **ELF**, **Mach-O**, and **PE** files using magic bytes. Common extensions (`.elf`, `.so`, `.dylib`, `.bundle`, `.exe`, `.dll`, `.sys`) are matched first, but extensionless binaries are still recognised. See [`file-types.md`](file-types.md) for the registered set.

A native binary opens with the same multi-tab layout as a `.wasm` file: hex, structure, and disassembly. The disassembler covers x86, x86-64, ARM, AArch64, and RISC-V 32 / 64 via the bundled Capstone WASM module — no host `objdump` or Capstone install required.

The disassembly view is virtualised by instruction window. Multi-megabyte `.text` sections render incrementally.

### Disassembler backend: AOT (default) and Redline (experimental)

The Capstone WASM module can run in two ways:

- **Bytecode AOT** (default) — Capstone is lowered to JVM bytecode at build time. Pure-JVM execution, works on every platform the IDE itself runs on.
- **Cranelift / Redline** (experimental) — Capstone is compiled to native machine code at build time, loaded via Project Panama FFM (Java 25+) or jffi. Lower overhead on compute-heavy decode workloads.

Both backends produce identical output. Switch via the Registry key `hexana.disassembly.backend.redline`. See [`disassembler-backends.md`](disassembler-backends.md) for details, including the `[disasm-perf]` logging key and the `Disassembling: N` status-bar widget.

### JVM artifacts

- **`.class` files** open as a three-tab view: header, methods (with decoded bytecode and WAT-style foldable bodies), and constant pool.
- **`.jar`, `.zip`, `.war`, `.apk`** open with a hex view on top and a searchable, sortable class list below. Click any entry to open it in a nested tab. Includes a project-view **Open in… → Hexana** action for archives, including entries under External Libraries. [#91](https://github.com/JetBrains/hexana/issues/91)

### JIT Viewer

A new **JIT Viewer** run-configuration tab lets you opt in to attaching the bundled JVMTI agent (`libjitview`) to any Java run configuration. The agent records compiled methods and writes a configurable `.jit` dump file (`default.jit` by default). The dump auto-opens in Hexana when the run completes, with a three-pane view: symbol tree on the left, native disassembly top-right, decoded JVM bytecode and inline tree bottom-right.

### WASM proposal information bar

The information bar at the top of every binary editor now shows the **WebAssembly proposals** the binary uses. When you launch a run configuration, the matching feature flags are passed to the underlying runtime so the binary executes with the right proposals enabled.

### Other additions

- Experimental support for **llvm-objdump** as an alternative disassembly source.
- DWARF debug info now correctly handles location information encoded in the `.debug_loc` section — breakpoints that previously didn't hit due to `.debug_loc`-only locals now resolve.
- GraalVM run configurations support GraalVM builds without the embedded WASM runner.
- WASM debug compatibility restored on IntelliJ IDEA 2025.
- General improvements to the MCP server and its tools.

## Upgrading to 0.10

No breaking changes from 0.9.x.

- Existing run configurations continue to work. The new proposal-flag plumbing is automatic.
- The disassembler backend defaults to the bytecode AOT path — no change for users not on the Cranelift native experiment.
- The new `.jar` / `.class` / `.jit` openers do not displace any IntelliJ default; Hexana registers itself as an additional editor provider.

## Known limitations in 0.10

- Native-binary support is experimental — section parsing covers the common cases; less common ELF or Mach-O subforms may not yet have a structured view.
- Cranelift / Redline backend is experimental. If the host platform has no native slice, Hexana transparently falls back to the AOT path.
- Java integration covers Java sources only. Kotlin source support is not yet wired.
- Quick-fixes are not yet wired on the WebAssembly Java inspections or the WIT inspections.

## Earlier releases

- [Hexana 0.9 release notes](changelog-0.9.md) — 0.9 (2026-05-07), 0.9.1 (2026-05-20).