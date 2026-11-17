# 12 — Cross-IDE Compatibility

## Prerequisites
- Hexana plugin installed on each IDE under test
- A `.wasm` file and a `.wit` file available

## Test Cases

### 12.1 IntelliJ IDEA
**Steps:** Install the plugin in IntelliJ IDEA. Open a `.wasm` file. Open a `.wit` file.
**Expected:** Full functionality: hex viewer, analysis tabs, WAT view, WIT language support. Analytics `ide_type` = "IntelliJ IDEA".

### 12.2 CLion
**Steps:** Install the plugin in CLion. Open a `.wasm` file. Open a `.wit` file.
**Expected:** Full functionality. Analytics `ide_type` = "CLion".

### 12.3 WebStorm
**Steps:** Install the plugin in WebStorm. Open a `.wasm` file. Open a `.wit` file.
**Expected:** Full functionality. Analytics `ide_type` = "WebStorm".

### 12.4 PyCharm
**Steps:** Install the plugin in PyCharm. Open a `.wasm` file.
**Expected:** Full functionality. Analytics `ide_type` = "PyCharm".

### 12.5 GoLand
**Steps:** Install the plugin in GoLand. Open a `.wasm` file.
**Expected:** Full functionality. Analytics `ide_type` = "GoLand".

### 12.6 Rider
**Steps:** Install the plugin in Rider. Open a `.wasm` file.
**Expected:** Full functionality. Analytics `ide_type` = "Rider".

### 12.7 RustRover
**Steps:** Install the plugin in RustRover. Open a `.wasm` file.
**Expected:** Full functionality. Analytics `ide_type` = "RustRover".

### 12.8 Minimum supported version
**Steps:** Install the plugin on IntelliJ IDEA 2024.1 (minimum supported). Open a `.wasm` file.
**Expected:** Plugin loads without errors. All core features work.

### 12.9 Latest IDE version
**Steps:** Install the plugin on the latest IntelliJ IDEA EAP/release. Open a `.wasm` file.
**Expected:** No compatibility issues. `verifyPlugin` gradle task passes.
