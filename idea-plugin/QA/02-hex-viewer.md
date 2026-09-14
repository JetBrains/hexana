# 02 — Hex Viewer

## Prerequisites
- A `.wasm` file open in the Hexana editor

## Test Cases

### 2.1 Byte display layout
**Steps:** Open a `.wasm` file, look at the hex viewer area.
**Expected:** Three synchronized columns visible: offset (hex address), hex data (16 bytes per row), text (ASCII).

### 2.2 Mouse selection
**Steps:** Click a byte in the hex area, drag to select a range.
**Expected:** Blue highlight appears across both hex and text columns. Selection stays in sync between the two.

### 2.3 Keyboard navigation
**Steps:** Use arrow keys to move the cursor. Use Shift+arrows to extend selection.
**Expected:** Cursor moves one byte at a time. Shift creates a selection range highlighted in blue.

### 2.4 ASCII / UTF-8 toggle
**Steps:** Click the "ASCII" / "UTF-8" label in the text column header.
**Expected:** Text column re-renders with the chosen encoding.

### 2.5 Hover highlight
**Steps:** Hover the mouse over a byte in the hex column.
**Expected:** The corresponding byte is highlighted in both hex and text columns simultaneously.

### 2.6 Copy as ASCII
**Steps:** Select a byte range -> right-click -> "Copy as ASCII".
**Expected:** Clipboard contains the ASCII text representation of the selected bytes.

### 2.7 Copy as Kotlin
**Steps:** Select a byte range -> right-click -> "Copy as Kotlin::listOf".
**Expected:** Clipboard contains a Kotlin expression like `listOf(0x00u, 0x61u, 0x73u, 0x6Du)`.

### 2.8 Go to offset (decimal)
**Steps:** Press the Go to Line shortcut (Cmd+G / Ctrl+G) -> type `256` -> press Enter.
**Expected:** Cursor jumps to byte offset 256 (0x100). Viewer scrolls if needed.

### 2.9 Go to offset (hex)
**Steps:** Press Go to Line shortcut -> type `0x100` -> press Enter.
**Expected:** Cursor jumps to byte offset 256. Same position as test 2.8.

### 2.10 Context menu — Open function in WAT
**Steps:** Place cursor in the code section of a `.wasm` file -> right-click -> "Open function in WAT".
**Expected:** WAT tab opens and scrolls to the matching function definition.

### 2.11 Context menu — Open data in WAT
**Steps:** Place cursor in the data section -> right-click -> "Open data in WAT".
**Expected:** WAT tab opens and scrolls to the matching data element.

### 2.12 Context menu — Open in sources
**Steps:** Place cursor at an offset that has DWARF source mapping -> right-click -> "Open in sources".
**Expected:** Navigates to the mapped source file at the correct line/column.

### 2.13 Run button visibility
**Steps:** Open a `.wasm` that exports `_start`, `_initialize`, or implements `wasi:cli/run`.
**Expected:** A play button appears in the offset column header.

### 2.14 Scrolling large files
**Steps:** Open Skiko WASM (~8 MB). Scroll to the end and back.
**Expected:** Smooth scrolling without lag. Offset column stays aligned with hex data.

### 2.15 Structural region tinting and labels
**Steps:** With semantic highlighting on, open one file per family: a `.jar`, a `.tar`, a `.dex`, a `.pcap`, a `.parquet`, a `resources.arsc`, a `.class`, an `.a`.
**Expected:** Rows carry subtle background bands that change at region boundaries, and the offset column shows the region's name where it starts (entry path, `string_ids`, `Packet 1`, `Row group 0`, `package com.example`, a method name, a member name). Prev/next region navigation steps between region starts.

### 2.16 Region label switches to the matching tab
**Steps:** Click a region label in the offset column for a `.jar` (`Entries`), a `.pcap` (`Packets`), an `.a` (`Members`), a `.parquet` (`Row Groups`).
**Expected:** The bottom panel switches to that tab. Labels with no matching tab (COFF sections, DEX `data`, a class file's constant pool) are not clickable.

### 2.17 Structure tool window for non-WASM formats
**Steps:** Open a `.jar`, then a `.pcap`, then a `.parquet`, and show the **Structure** tool window for each.
**Expected:** Each lists its regions as a flat tree in file order; selecting a node scrolls and selects those bytes in the hex view. A format with nothing to report (an unrecognised `.bin`) shows only the file node. Files above 10,000 regions end with a `... N more regions` entry.
