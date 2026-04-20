# 05 — WAT View

## Prerequisites
- A `.wasm` file open in Hexana
- `wasm-tools` installed (or "Use built-in wasm tools" enabled in Settings)

## Test Cases

### 5.1 WAT conversion
**Steps:** Click the "Wat" tab on a `.wasm` file.
**Expected:** "Converting..." animated indicator appears briefly, then WAT text is rendered with syntax highlighting.

### 5.2 Syntax highlighting
**Steps:** Inspect the WAT output text.
**Expected:** Keywords (`func`, `module`, `param`, `result`), strings, numbers, and comments are color-coded using standard IDE theme colors.

### 5.3 Gutter offsets — enabled
**Steps:** Enable "Show offsets" in Settings -> Tools -> Hexana. Reopen the `.wasm` file and switch to the Wat tab.
**Expected:** Hex offset annotations (e.g. `0x001234`) appear in the left gutter of each line that maps to a WASM byte offset.

### 5.4 Gutter offsets — disabled
**Steps:** Disable "Show offsets" in Settings. Reopen the WAT view.
**Expected:** No offset annotations in the gutter.

### 5.5 Gutter click navigates hex
**Steps:** Click a gutter offset label (e.g. `0x001234`).
**Expected:** The hex viewer scrolls to that byte offset and highlights it.

### 5.6 Context menu — Show in hex view
**Steps:** Right-click on a `func` definition in the WAT text -> "Show in hex view".
**Expected:** Hex viewer scrolls to and highlights the byte range of that function.

### 5.7 Open function in WAT from hex
**Steps:** In the hex viewer, right-click within the code section -> "Open function in WAT".
**Expected:** WAT tab activates and scrolls to the matching function definition.

### 5.8 Open data in WAT from hex
**Steps:** In the hex viewer, right-click within the data section -> "Open data in WAT".
**Expected:** WAT tab activates and scrolls to the matching data element.

### 5.9 Large file WAT rendering
**Steps:** Open Skiko WASM (~8 MB), click the Wat tab.
**Expected:** Lite editor (Compose-based) may be used for large output. Text renders without freezing the UI. Search and scrolling work.

### 5.10 WAT search
**Steps:** In the WAT view, press Cmd/Ctrl+F and search for a function name.
**Expected:** Search bar appears. Matches highlighted. Navigation with Find Next / Find Previous works.

### 5.11 WAT copy
**Steps:** Select text in the WAT view -> Cmd/Ctrl+C.
**Expected:** Selected WAT text is copied to clipboard.

### 5.12 Missing wasm-tools
**Steps:** Remove or rename the `wasm-tools` binary. Disable "Use built-in wasm tools". Open a `.wasm` file.
**Expected:** Editor notification banner: "Wasm-tools is not found" with a "Configure..." link that opens Settings -> Hexana.

### 5.13 Built-in wasm tools
**Steps:** Enable "Use built-in wasm tools" in Settings. Open a `.wasm` file, click Wat tab.
**Expected:** WAT conversion succeeds using the built-in GraalWasm-based converter (no external `wasm-tools` needed).

### 5.14 Standalone `.wat` file
**Steps:** Open a `.wat` file directly (not generated from WASM).
**Expected:** Standard IntelliJ editor with WAT syntax highlighting, brace matching, and find usages.
