# Hexana IDE Plugin — Manual Test Plan

Manual QA test cases for the Hexana IntelliJ Platform plugin.
Each file covers a feature area and can be executed independently.

## Test Files

| File | Area | Priority |
|------|------|----------|
| [01-file-opening.md](01-file-opening.md) | File opening & editor registration | P0 |
| [02-hex-viewer.md](02-hex-viewer.md) | Hex viewer interactions | P0 |
| [03-hex-search.md](03-hex-search.md) | Hex search | P0 |
| [04-analysis-tabs.md](04-analysis-tabs.md) | WASM analysis tabs | P0 |
| [05-wat-view.md](05-wat-view.md) | WAT text format view | P0 |
| [06-structure-popup.md](06-structure-popup.md) | WASM structure popup | P1 |
| [07-wit-language.md](07-wit-language.md) | WIT language support | P0 |
| [08-run-debug.md](08-run-debug.md) | Run/debug WASM | P1 |
| [09-mcp-tools.md](09-mcp-tools.md) | MCP tool integration | P1 |
| [10-settings.md](10-settings.md) | Plugin settings | P1 |
| [11-dwarf.md](11-dwarf.md) | DWARF debug info | P2 |
| [12-cross-ide.md](12-cross-ide.md) | Cross-IDE compatibility | P1 |

## Priority Levels

- **P0** — run on every release
- **P1** — run on feature-touching releases and before major milestones
- **P2** — run periodically or when the area is changed

## Test Data

- **Skiko WASM** (~8 MB, 14k functions, DWARF info) — primary large-file test subject
- Any small `.wasm` with `_start` export — for run/debug tests
- `.wit` files from `wasm-modules/` or Component Model repos
- Component Model `.wasm` — for component-specific tab tests
