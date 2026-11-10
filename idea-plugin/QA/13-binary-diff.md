# 13 — Binary Comparison

## Prerequisites
- Two builds of the same `.wasm` module (e.g. before/after a code change), and two Component Model binaries
- Two builds of the same native binary: an ELF executable or `.so`, a Mach-O binary, and a COFF `.obj`
  (`parsing/native-fixtures/test-data` has small samples; for scale, two LLVM tools such as `clangd` and `clang-tidy`)
- A PE `.exe` or `.dll`

## Test Cases

### 13.1 WASM module diff
**Steps:** Select two `.wasm` core modules in the Project view -> **Hexana** -> **Compare Binary Files**.
**Expected:** A diff editor titled `base ↔ head` with **Size Impact**, **Entities** and **Dispatch** tabs. Rows are tinted added / removed / modified / moved; each table has a search field and sortable columns. Clicking a function row shows its WAT side by side in the bottom pane, removed lines on the left, added on the right.

### 13.2 Mismatched or non-WASM inputs
**Steps:** Run **Compare Binaries With…** on a core module and a component; on a `.wasm` and an ELF; then on a `.wasm` whose header is not WebAssembly (e.g. a renamed text file).
**Expected:** A warning each time naming both kinds ("`a.wasm` is a WebAssembly module and `b.wasm` is a WebAssembly component — only binaries of the same kind can be compared"), then "`<name>` is not a WebAssembly binary". No diff editor opens.

### 13.2a One action for every format
**Steps:** Open Find Action and type "compare binaries"; open the Project view context menu on a `.wasm` file, then on an `.so`.
**Expected:** A single **Compare Binaries With…** entry in each place (no separate WASM / Binary entries; the former ids do not show in Find Action). A shortcut previously assigned to the old Compare WASM or Compare Binary action still runs the comparison.

### 13.2b Extensionless binaries
**Steps:** Right-click an extensionless ELF executable (e.g. a Linux build output), then select two of them; repeat with a WASM module saved without `.wasm`. Then right-click a headerless `.bin` firmware image and a text file.
**Expected:** The entry is shown for the executables and the module (**Compare Binary Files** when two are selected), and not for the `.bin` image or the text file.

### 13.3 Component diff navigation
**Steps:** Compare two Component Model binaries. Click a **Modified** submodule, then an **Added** one.
**Expected:** The table lists every submodule with its status. The modified pair opens as its own WASM diff tab; the added one opens as a single binary. The UI stays responsive while the submodule is extracted.

### 13.4 Native size and function tabs
**Steps:** **Compare Binaries With…** on two builds of an ELF (repeat for Mach-O and COFF).
**Expected:** **Size Impact** and **Functions** tabs. Functions lists only changed functions (identical ones are counted in the footer), classified Added / Removed / Modified / Moved; a renamed but unchanged function reads "renamed from …", one that only shifted reads "addresses only".

### 13.5 Native function detail
**Steps:** In the Functions tab, click a Modified function.
**Expected:** The bottom pane shows both versions' disassembly with real addresses; instructions whose only difference is a shifted target line up as unchanged, changed instructions are tinted.

### 13.6 Relocatable objects with per-function sections
**Steps:** Compare two objects built with `-ffunction-sections` (or C++ objects with inline functions).
**Expected:** Every function is listed individually, not merged into one.

### 13.7 Formats without per-function comparison
**Steps:** Compare two native (non-.NET) PE images; then two stripped ELF executables.
**Expected:** PE shows **Size Impact** only, with no bottom pane. The stripped pair shows a Functions tab saying neither side has function symbols.

### 13.8 Large binaries
**Steps:** Compare `clangd` with `clang-tidy` (tens of thousands of functions).
**Expected:** The IDE stays responsive while both tabs load (Functions takes longest, as it disassembles changed functions to rule out address-only changes); selecting a large function renders without freezing.

### 13.9 .NET assemblies
**Steps:** **Compare Binaries With…** on `parsing/native-fixtures/test-data/pe/sample-clr.exe` and `sample-clr-v2.exe`
(or two versions of the same NuGet DLL). Click a Modified method, then a Modified type.
**Expected:** **Size Impact**, **Types** and **Methods** tabs. The header shows the assembly version change
(`1.2.3.4 → 1.2.4.0`). Methods lists `Greeter::Greet` and `Program::Describe` as Modified, `get_Prefix` as Removed and
the `Farewell` methods as Added; `Main` is absent although its IL bytes changed, and the header says how many methods
differ only in metadata tokens. The bottom pane shows the method's IL side by side: in `Describe`, the `"three"` case is
three added lines and the shifted `IL_` labels around it are not tinted. A type row shows the whole type's IL (`Color`
gains `.field … Alpha`).
