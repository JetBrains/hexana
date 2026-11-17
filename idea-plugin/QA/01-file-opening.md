# 01 — File Opening & Editor Registration

## Prerequisites
- Hexana plugin installed
- `.wasm`, `.bin`/`.elf`/`.exe` files available in the project

## Test Cases

### 1.1 Open `.wasm` file
**Steps:** Open any `.wasm` file from the Project view.
**Expected:** Hexana "Hex" editor tab appears before the default editor tab. Hex viewer loads with byte grid showing offset, hex, and text columns.

### 1.2 Open binary file
**Steps:** Open a `.bin`, `.elf`, or `.exe` file.
**Expected:** Hexana hex viewer opens in generic binary mode (no WASM-specific analysis tabs like Functions, Exports, etc.).

### 1.3 Open large `.wasm` (> 3 MB)
**Steps:** Open `skiko.Os.wasm` (~8 MB).
**Expected:** File opens without freezing the UI. WAT tab shows "Converting..." while processing. Analysis tabs populate in the background.

### 1.4 Open Component Model `.wasm`
**Steps:** Open a WebAssembly Component file.
**Expected:** Component-specific tabs shown: Exports, Imports, Modules, Top, Wat. No core-module-only tabs (Functions, Garbage, etc.).

### 1.5 Open in Hex View (arbitrary file)
**Steps:** Right-click any non-binary file in Project view (e.g. `.kt`, `.json`) -> select "Hex view".
**Expected:** File opens in the Hexana hex viewer regardless of its extension.

### 1.6 Rapid tab switching
**Steps:** Open a `.wasm` file. Quickly switch between the "Hex" editor tab and other editor tabs 5-10 times in rapid succession.
**Expected:** No exceptions or crashes. `FileEditorProvider.dispose()` racing with `createEditor()` is handled gracefully.

### 1.7 WASM magic validation
**Steps:** Rename a `.txt` file to `.wasm` and open it.
**Expected:** Hex viewer opens but WASM analysis tabs either show an error state or fall back to generic binary mode.
