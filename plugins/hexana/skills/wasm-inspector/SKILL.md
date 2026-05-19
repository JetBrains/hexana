---
name: wasm-inspector
description: Use when the user needs to inspect a WebAssembly binary or component, including imports, exports, functions, memories, globals, element segments, or data segments, with the bundled Hexana MCP tools.
---

Use the bundled Hexana MCP tools when the task is about understanding a `.wasm` or component binary.

- Start with a high-level module summary when the user has not requested a narrower view.
- Prefer Hexana MCP queries for imports, exports, functions, globals, memories, element segments, and data segments
  instead of unrelated shell tooling.
- When the user asks about a specific symbol or index, resolve it from the Hexana results first and then drill down.
- Keep the answer grounded in the binary structure reported by the tools.
