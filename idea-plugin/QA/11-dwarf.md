# 11 — DWARF Debug Info

## Prerequisites
- A `.wasm` file with embedded DWARF sections (e.g. compiled from C/C++/Rust with debug info)
- Optionally: a `.wasm.map` source map file in the same directory

## Test Cases

### 11.1 DWARF tab visible
**Steps:** Open a `.wasm` file that contains several DWARF custom sections.
**Expected:** A single `DWARF` tab appears, with an inner tab strip for `.debug_abbrev`, `.debug_info` and `.debug_line`. The three section names do **not** appear as top-level tabs.

### 11.2 DWARF tabs absent
**Steps:** Open a `.wasm` file without DWARF info.
**Expected:** No DWARF tab is shown.

### 11.3 Single DWARF section
**Steps:** Open a `.wasm` file carrying exactly one DWARF section (e.g. only `.debug_info`).
**Expected:** That section appears as its own top-level `.debug_info` tab, with no `DWARF` parent tab wrapping it.

### 11.4 Abbreviation table
**Steps:** Open the `DWARF` tab, then its `.debug_abbrev` inner tab.
**Expected:** Tree view of abbreviation table entries with tag names, attributes, and forms.

### 11.5 DIE tree
**Steps:** Open the `.debug_info` inner tab.
**Expected:** DWARF DIE (Debug Information Entry) tree with expandable nodes showing compilation units, functions, variables, types. `DW_FORM_strp` attributes show the resolved string, not `Unknown string`.

### 11.6 Line table
**Steps:** Open the `.debug_line` inner tab.
**Expected:** One node per line-program unit, expanding to the header fields, `standard_opcode_lengths` and `line_program`.

### 11.7 Search within a DWARF tree
**Steps:** In any DWARF inner tab, type into the search field at the top.
**Expected:** Matches are highlighted and the first is selected and scrolled to. The counter reads "n of m" (briefly "Indexing..." on a large binary while the index builds). Previous/next buttons and Enter / Shift+Enter step through matches; Escape clears.

### 11.8 Search reaches collapsed subtrees
**Steps:** Collapse everything, then search for a term that only occurs deep in the tree.
**Expected:** The match is found, its ancestors expand automatically, and the tree scrolls to it.

### 11.9 Row navigates to bytes
**Steps:** Click an abbrev entry, a `.debug_info` attribute row, and a `.debug_line` opcode row.
**Expected:** In each case the hex view selects that row's own bytes. A `.debug_info` attribute selects bytes inside the `.debug_info` section (not `.debug_abbrev`), and two different DIEs sharing an abbrev select different bytes.

### 11.10 Vertical scrollbar
**Steps:** Open a DWARF tree taller than the panel.
**Expected:** A vertical scrollbar is present and scrolls the tree.

### 11.11 Open in sources — success
**Steps:** Right-click a hex offset that has DWARF source mapping -> "Open in sources".
**Expected:** IDE navigates to the original source file at the correct line and column.

### 11.12 Open in sources — file not found
**Steps:** Trigger "Open in sources" for a DWARF mapping where the source file no longer exists on disk.
**Expected:** Balloon notification: "File not found: {path}".

### 11.13 Source map file
**Steps:** Place a `.wasm.map` file alongside the `.wasm` file. Open the `.wasm` file.
**Expected:** Source map is loaded and used for source location resolution (may enhance "Open in sources" navigation).
