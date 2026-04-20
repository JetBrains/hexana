# 06 — WASM Structure Popup

## Prerequisites
- A `.wasm` file open in the Hexana hex viewer

## Test Cases

### 6.1 Open structure popup
**Steps:** Press Cmd+F12 (macOS) or Ctrl+F12 (Linux/Windows) while the hex viewer is focused.
**Expected:** A floating, movable, resizable popup appears showing all WASM sections as expandable tree nodes.

### 6.2 Expand sections
**Steps:** Expand a section node (e.g. "Code", "Export").
**Expected:** Individual records within the section appear as tree leaves (functions, exports, etc.).

### 6.3 Speed search
**Steps:** Start typing a name while the popup is open (e.g. `malloc`).
**Expected:** Tree filters to nodes matching the typed text.

### 6.4 Navigate from popup
**Steps:** Click a node or press Enter on a selected node.
**Expected:** Hex viewer scrolls to the selected record's byte range. Popup closes.

### 6.5 Close popup
**Steps:** Press Escape.
**Expected:** Popup closes without navigating.

### 6.6 Resize and move
**Steps:** Drag the popup edges to resize. Drag the title area to move.
**Expected:** Popup resizes and moves smoothly.
