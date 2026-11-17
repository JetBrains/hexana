# 08 — Run / Debug WASM

## Prerequisites
- `wasmtime` installed and in PATH (for Wasmtime tests)
- GraalWasm available (for GraalWasm tests)
- A `.wasm` file with `_start` or other exported functions

## Run

### 8.1 Run from hex viewer play button
**Steps:** Open a `.wasm` that exports `_start` / `_initialize` / `wasi:cli/run`. Click the play button in the offset column header.
**Expected:** Engine selector popup appears. After selection, program executes. Output shown in the Run console.

### 8.2 Run exported function from Exports tab
**Steps:** In the Exports tab, click the play icon next to a function export.
**Expected:** Engine selector popup -> if the function has parameters, an args dialog appears with typed input fields -> function executes with output.

### 8.3 Function args dialog — validation
**Steps:** Run a function with `i32` params. Enter non-numeric text in the field.
**Expected:** Validation prevents execution. Error indication on the invalid field.

### 8.4 Function args dialog — i64/f32/f64
**Steps:** Run functions with `i64`, `f32`, `f64` parameter types.
**Expected:** Input fields accept appropriate numeric values. Function executes correctly.

### 8.5 Program args dialog (WASI component)
**Steps:** Run a `.wasm` component that implements `wasi:cli/run`.
**Expected:** Program args dialog appears with a free-text field for command-line arguments.

### 8.6 Auto-create run configuration
**Steps:** Right-click a `.wasm` file in Project view -> Run.
**Expected:** A "WebAssembly" run configuration is created automatically with the file path pre-filled.

## Run Configuration Settings

### 8.7 Wasmtime engine settings
**Steps:** Open Run/Debug Configurations -> add "WebAssembly" config -> select Wasmtime.
**Expected:** Fields visible: Wasm file, Wasmtime path, Wasmtime options, Program arguments, Working directory.

### 8.8 GraalWasm engine settings
**Steps:** Switch engine to GraalWasm in the run config.
**Expected:** Fields visible: Wasm file, Export name, Program arguments, Working directory. Wasmtime-specific fields hidden.

### 8.9 Engine switch — migrate --invoke
**Steps:** Set Wasmtime with `--invoke myFunc` in options. Switch to GraalWasm.
**Expected:** "Export name" field is populated with `myFunc`. Switch back: `--invoke myFunc` restored.

### 8.10 Wasmtime execution
**Steps:** Configure Wasmtime engine. Run a WASI module.
**Expected:** Program runs via `wasmtime` binary. Output and exit code shown in Run console.

### 8.11 GraalWasm execution
**Steps:** Configure GraalWasm engine. Run a core WASM module.
**Expected:** Program runs via GraalWasm polyglot. Output shown in Run console.

### 8.12 GraalWasm + Component Model
**Steps:** Configure GraalWasm engine. Select a Component Model `.wasm`.
**Expected:** Validation error shown: Component Model not supported by GraalWasm.

### 8.13 Dependencies field
**Steps:** Open run config for a `.wasm` component with imports.
**Expected:** Dependencies text area shows resolved component dependencies (auto-populated).

## Debug

### 8.14 Start debug session
**Steps:** Debug a `.wasm` file with Wasmtime engine.
**Expected:** LLDB-backed debug session starts. Debug tool window opens.

### 8.15 Breakpoints in WAT
**Steps:** Open a `.wat` file. Set a breakpoint on a function line. Start debugging the corresponding `.wasm`.
**Expected:** Execution pauses at the breakpoint. Current line is highlighted in the WAT editor.

### 8.16 Debug stepping
**Steps:** While paused at a breakpoint, use Step Over / Step Into / Step Out.
**Expected:** Execution advances as expected. Stack frames and local variables update.

### 8.17 Stack frames
**Steps:** While paused, inspect the Frames panel in the Debug tool window.
**Expected:** WASM call stack is visible with function names (from name section or indices).

### 8.18 Variable inspection
**Steps:** While paused, inspect the Variables panel.
**Expected:** Local variables shown with their types and current values.
