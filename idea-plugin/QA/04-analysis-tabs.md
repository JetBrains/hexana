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

### 4.17 Dock tabs to the right
**Steps:** Open a `.wasm` file. Click the **Move tabs to the right of the hex view** button (right of the tab bar, before
**+**). Drag the vertical divider; select tabs in the list; add a script tab and close it from the list. Open a second
binary, then restart the IDE.
**Expected:** The tabs panel moves beside the hex view and lists its tabs vertically (selected one marked, long names
ellipsized, the list scrolls when it does not fit). The divider resizes the two sides; expand/collapse still works. The
second file and the restarted IDE open with the tabs on the right too. With the panel narrow, tables (e.g. a .NET
assembly's **Methods**) keep readable single-line headers and scroll horizontally, header and rows together, instead of
squeezing or overlapping columns. Clicking the button again (**Move tabs below the
hex view**) restores the bottom layout with the horizontal tab strip, in every open editor.
