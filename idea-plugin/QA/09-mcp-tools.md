# 09 — MCP Tool Integration

## Prerequisites
- Hexana plugin installed with MCP toolset enabled
- An MCP client connected to the IDE's MCP server (e.g. AI assistant)
- A `.wasm` file available in the project

## Test Cases

### 9.1 summarize_module
**Steps:** Call `summarize_module` with a valid `.wasm` file path.
**Expected:** Returns section counts, import breakdown, module size summary.

### 9.2 list_imports
**Steps:** Call `list_imports` with a `.wasm` path.
**Expected:** Returns function imports with `funcidx` values.

### 9.3 list_functions — paginated
**Steps:** Call `list_functions` with `offset=0`, `limit=50`.
**Expected:** Returns first 50 functions with indices and signatures.

### 9.4 list_functions — next page
**Steps:** Call `list_functions` with `offset=50`, `limit=50`.
**Expected:** Returns functions 50-99.

### 9.5 list_exports
**Steps:** Call `list_exports`.
**Expected:** All exports returned with name, kind, and external index.

### 9.6 list_exported_functions
**Steps:** Call `list_exported_functions` with a range.
**Expected:** Exported functions returned with resolved signatures.

### 9.7 get_instructions_for_functions
**Steps:** Call with a specific `funcidx`.
**Expected:** Returns decoded instruction text for the function body.

### 9.8 list_data_segments
**Steps:** Call `list_data_segments`.
**Expected:** Data segment metadata returned (offset, size, active/passive).

### 9.9 list_types
**Steps:** Call `list_types`.
**Expected:** All type entries returned including GC composite types.

### 9.10 list_globals
**Steps:** Call `list_globals`.
**Expected:** Imported and defined globals with type, mutability, init expression.

### 9.11 list_memory
**Steps:** Call `list_memory`.
**Expected:** Memory declarations with limits returned.

### 9.12 list_element_segments
**Steps:** Call `list_element_segments`.
**Expected:** Element segments returned with kind (active/passive/declarative).

### 9.13 functions_for_indices
**Steps:** Call with a list of specific `funcidx` values.
**Expected:** Targeted function info returned for each index.

### 9.14 get_locals_for_functions
**Steps:** Call with a `funcidx`.
**Expected:** Local variable declarations (count, type) for the function.

### 9.15 list_data (raw bytes)
**Steps:** Call `list_data` with a range.
**Expected:** Raw data segment bytes returned as base64.

### 9.16 Invalid path
**Steps:** Call any tool with a non-existent file path.
**Expected:** Clear error message explaining the file was not found.

### 9.17 Non-WASM file
**Steps:** Call `summarize_module` with a `.txt` file path.
**Expected:** Clear error message explaining the file is not a valid WASM module.

### 9.18 Large result set
**Steps:** Call `list_functions` on Skiko WASM (14k functions) without limit or with limit > 1000.
**Expected:** Results are paginated. No unbounded response.
