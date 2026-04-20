# 11 — DWARF Debug Info

## Prerequisites
- A `.wasm` file with embedded DWARF sections (e.g. compiled from C/C++/Rust with debug info)
- Optionally: a `.wasm.map` source map file in the same directory

## Test Cases

### 11.1 DWARF tabs visible
**Steps:** Open a `.wasm` file that contains DWARF custom sections.
**Expected:** Additional tabs appear: `.debug_abbrev`, `.debug_info`, `.debug_line`.

### 11.2 DWARF tabs absent
**Steps:** Open a `.wasm` file without DWARF info.
**Expected:** No DWARF tabs are shown.

### 11.3 Abbreviation table
**Steps:** Click the `.debug_abbrev` tab.
**Expected:** Tree view of abbreviation table entries with tag names, attributes, and forms.

### 11.4 DIE tree
**Steps:** Click the `.debug_info` tab.
**Expected:** DWARF DIE (Debug Information Entry) tree with expandable nodes showing compilation units, functions, variables, types.

### 11.5 Line table
**Steps:** Click the `.debug_line` tab.
**Expected:** Line number program entries displayed as a tree with file, line, column, and address mappings.

### 11.6 Open in sources — success
**Steps:** Right-click a hex offset that has DWARF source mapping -> "Open in sources".
**Expected:** IDE navigates to the original source file at the correct line and column.

### 11.7 Open in sources — file not found
**Steps:** Trigger "Open in sources" for a DWARF mapping where the source file no longer exists on disk.
**Expected:** Balloon notification: "File not found: {path}".

### 11.8 Source map file
**Steps:** Place a `.wasm.map` file alongside the `.wasm` file. Open the `.wasm` file.
**Expected:** Source map is loaded and used for source location resolution (may enhance "Open in sources" navigation).
