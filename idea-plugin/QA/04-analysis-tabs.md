# 04 — WASM Analysis Tabs

## Prerequisites
- A `.wasm` module file (not Component) open in Hexana. Ideally Skiko WASM for full coverage.

## Test Cases

### 4.1 Functions tab
**Steps:** Click the "Functions" tab.
**Expected:** Table with Name and Signature columns. Unreachable functions prefixed with a bullet marker. Clicking a row scrolls the hex viewer to that function's byte range.

### 4.2 Functions — context menu
**Steps:** Right-click a function -> "Show Paths from Roots".
**Expected:** Switches to the Dominators tab with the selected function highlighted.

### 4.3 Exports tab
**Steps:** Click the "Exports" tab.
**Expected:** Table with Kind, Name, Signature columns. Play icon visible next to function exports.

### 4.4 Exports — run function
**Steps:** Click the play icon next to an exported function.
**Expected:** Engine selector popup appears. After selecting engine: if function has params, args dialog appears; then function executes with output in Run console.

### 4.5 Imports tab
**Steps:** Click the "Imports" tab.
**Expected:** Import names listed with module and field. Clicking a row scrolls the hex viewer to the import record.

### 4.6 Data tab
**Steps:** Click the "Data" tab.
**Expected:** Data segments table showing type (active/passive) and size. Clicking a row scrolls the hex viewer to that segment.

### 4.7 Custom sections tab
**Steps:** Click the "Custom" tab.
**Expected:** Custom section names listed with an ASCII preview of the first 64 bytes.

### 4.8 Top (size analysis) tab
**Steps:** Click the "Top" tab.
**Expected:** Expandable tree showing section, function, and data breakdown with size (bytes), percentage, and hex offset. Clicking a node navigates the hex viewer.

### 4.9 Monos tab
**Steps:** Click the "Monos" tab (requires a WASM with name section).
**Expected:** Monomorphization groups displayed with base name, count, total size, and average size.

### 4.10 Garbage tab
**Steps:** Click the "Garbage" tab.
**Expected:** Three sub-views: Functions, Types, Data. Lists unreachable items.

### 4.11 Garbage — highlight in hex
**Steps:** In the Garbage tab, enable the "Highlight in hex" toggle.
**Expected:** Garbage bytes are colored/highlighted in the hex viewer.

### 4.12 Garbage — context menu
**Steps:** Right-click a garbage function -> "Show in Dominators" / "Show Callers".
**Expected:** Navigates to Dominators tab or displays caller information.

### 4.13 Dominators tab
**Steps:** Click the "Dominators" tab.
**Expected:** Split pane: left side shows call-graph dominator tree (all functions); right side shows "CFG Dominators" or "Paths from Roots" for the selected function.

### 4.14 Dominators — toggle sub-view
**Steps:** Select a function in the dominator tree, toggle between "CFG Dominators" and "Paths from Roots" on the right side.
**Expected:** Right pane updates to show the selected sub-view.

### 4.15 Tab navigation to hex
**Steps:** Click rows in Functions, Imports, Data, and Top tabs sequentially.
**Expected:** Hex viewer scrolls to the correct byte range for each clicked item.

### 4.16 Expand/collapse tab area
**Steps:** Click the expand/collapse icon button to the left of the tab bar.
**Expected:** Hex viewer hides; tabs fill the entire editor area. Click again restores the split layout.
