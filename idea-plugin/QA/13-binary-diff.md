# 13 — Binary Comparison

## Prerequisites
- Two builds of the same `.wasm` module (e.g. before/after a code change), and two Component Model binaries
- Two builds of the same native binary: an ELF executable or `.so`, a Mach-O binary, and a COFF `.obj`
  (`parsing/native-fixtures/test-data` has small samples; for scale, two LLVM tools such as `clangd` and `clang-tidy`)
- A PE `.exe` or `.dll`

## Test Cases

### 13.1 WASM module diff
**Steps:** Select two `.wasm` core modules in the Project view -> **Compare WASM Files**.
**Expected:** A diff editor titled `base ↔ head` with **Size Impact**, **Entities** and **Dispatch** tabs. Rows are tinted added / removed / modified / moved; each table has a search field and sortable columns. Clicking a function row shows its WAT side by side in the bottom pane, removed lines on the left, added on the right.

### 13.2 Mismatched or non-WASM inputs
**Steps:** Run **Compare WASM With…** on a core module and a component; then on a `.wasm` whose header is not WebAssembly (e.g. a renamed text file).
**Expected:** A warning each time — "not the same kind of WASM binary", then "`<name>` is not a WebAssembly binary". No diff editor opens.

### 13.3 Component diff navigation
**Steps:** Compare two Component Model binaries. Click a **Modified** submodule, then an **Added** one.
**Expected:** The table lists every submodule with its status. The modified pair opens as its own WASM diff tab; the added one opens as a single binary. The UI stays responsive while the submodule is extracted.

### 13.4 Native size and function tabs
**Steps:** **Compare Binary With…** on two builds of an ELF (repeat for Mach-O and COFF).
**Expected:** **Size Impact** and **Functions** tabs. Functions lists only changed functions (identical ones are counted in the footer), classified Added / Removed / Modified / Moved; a renamed but unchanged function reads "renamed from …", one that only shifted reads "addresses only".

### 13.5 Native function detail
**Steps:** In the Functions tab, click a Modified function.
**Expected:** The bottom pane shows both versions' disassembly with real addresses; instructions whose only difference is a shifted target line up as unchanged, changed instructions are tinted.

### 13.6 Relocatable objects with per-function sections
**Steps:** Compare two objects built with `-ffunction-sections` (or C++ objects with inline functions).
**Expected:** Every function is listed individually, not merged into one.

### 13.7 Formats without per-function comparison
**Steps:** Compare two PE images; then two stripped ELF executables.
**Expected:** PE shows **Size Impact** only, with no bottom pane. The stripped pair shows a Functions tab saying neither side has function symbols.

### 13.8 Large binaries
**Steps:** Compare `clangd` with `clang-tidy` (tens of thousands of functions).
**Expected:** The IDE stays responsive while both tabs load (Functions takes longest, as it disassembles changed functions to rule out address-only changes); selecting a large function renders without freezing.
