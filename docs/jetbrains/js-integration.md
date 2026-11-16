---
title: JavaScript and TypeScript Integration (0.9)
description: Hexana's WebAssembly type inference and completion for JavaScript and TypeScript sources that call WebAssembly.instantiate, instantiateStreaming, compile, or compileStreaming.
version: "0.9"
---

# JavaScript and TypeScript Integration

Hexana 0.9 understands the standard browser / Node.js **`WebAssembly`** JavaScript API and provides completion and type inference for code that loads and calls `.wasm` modules from JS or TypeScript. This support is conditional on the JavaScript plugin being present in the host IDE (WebStorm by default; in IntelliJ IDEA, RustRover, PhpStorm, Rider when the JavaScript plugin is bundled or installed).

The optional descriptor `org.jetbrains.hexana.javascript.xml` registers a `frameworkIndexingHandler` (`WasmFrameworkIndexingHandler`) against the JavaScript language. The handler hooks into the JetBrains JS engine's type-inference pipeline.

## Why this exists

The browser's `WebAssembly.instantiate(...)` returns a `Promise<WebAssemblyInstantiatedSource>` whose `.instance.exports` is typed as `any` — TypeScript and JS engines have no idea what the resolved `.wasm` actually exports. Similarly, the imports object literal (second argument) is typed as `WebAssembly.Imports`, which says nothing about which module names and item names the binary expects.

Hexana 0.9 closes both gaps by parsing the `.wasm` referenced by the call and feeding the real import / export shape back into the JS type system at edit time. Inside the imports literal you get completion for module and item names; on `.instance.exports.<name>` you get completion plus argument-count and argument-type checking through the JS engine's own type machinery.

## Recognised call shapes

The handler recognises four `WebAssembly.*` calls:

| Call | Behaviour |
|---|---|
| `WebAssembly.instantiate(source, importObject)` | Imports completion on the second argument; exports type inference on the result's `.instance.exports`. |
| `WebAssembly.instantiateStreaming(response, importObject)` | Same as `instantiate`, but `source` comes from `fetch(...)` — Hexana traces the `fetch` literal. |
| `WebAssembly.compile(source)` | Returns `Promise<WebAssembly.Module>`; downstream `new WebAssembly.Instance(module, imports)` resolves through the cached path. |
| `WebAssembly.compileStreaming(response)` | Same, streaming variant. |

## Capabilities

### Imports completion (typing inside the second argument)

When you type inside the imports object literal:

```javascript
WebAssembly.instantiate(await fetch("./calculator.wasm"), {
  env: {
    // ↑ caret here → completion offers exactly the imports calculator.wasm declares
  }
});
```

Hexana:

1. Resolves the imports literal's parent call to `WebAssembly.instantiate` / `instantiateStreaming`.
2. Identifies the source `.wasm` (see *How source resolution works* below).
3. Parses imports through `WasmBinaryDataCacheService` (cached).
4. Surfaces import module names (`env`, `wasi_snapshot_preview1`, `gl`, etc.) at the top level and item names (`memory`, `abort`, `glDrawArrays`, etc.) at the per-module level.
5. Walks nested property paths so completion stays accurate inside deeply-nested literals.

Function-typed imports are typed as `(arg1: …, arg2: …) => result` so the JS engine flags wrong-arity and wrong-type implementations. Memory, table, and global imports are typed accordingly.

This branch covers **core WebAssembly modules only** in 0.9. Component-Model components return `null` from the imports resolver — completion for component imports is on the roadmap.

### Exports type inference (using `.instance.exports`)

When you call into the resolved exports:

```javascript
const { instance } = await WebAssembly.instantiate(await fetch("./calculator.wasm"), {});
instance.exports.calculate(/* ↑ caret → completion + checked signature */);
```

Hexana augments the `Promise<WebAssemblyInstantiatedSource>` return type so that `.instance.exports` is a record type matching the real exports of `calculator.wasm`:

- Function exports become `(p1: type, p2: type, …) => returnType`.
- Memory exports become `WebAssembly.Memory`.
- Global exports become the corresponding numeric / `WebAssembly.Global` type.
- Table exports become `WebAssembly.Table`.

The JS engine then drives the rest: completion of `.exports.<name>`, parameter hints, "no overload matches" warnings on type mismatch, navigation to the export declaration (which resolves to the matching row in Hexana's **Exports** tab).

### Literal-union argument types

When a WASM function's body branches on a string-literal argument — for example, a `calculate(op, a, b)` function that switches on `op` between `"add"` and `"sub"` — Hexana extracts the literal alternatives from the binary and types the parameter as `"add" | "sub"` instead of `string`. This propagates the WASM-side discriminated-union pattern into the JS type system, giving IDE warnings when callers pass an unrecognised operation. Covered by `JsResolveTest.testCalculatorWasmExportResolve`.

### Compile-then-instantiate flows

`WebAssembly.compile(...)` returns a `Promise<WebAssembly.Module>`. Hexana wraps that module with a `WasmWrapperType` carrying the resolved `.wasm` path; a downstream `new WebAssembly.Instance(module, imports)` reads the wrapper and types the imports + exports the same way as the single-call `instantiate` flow.

## How source resolution works

The handler resolves the `.wasm` source of a call in three steps, falling back through each:

1. **Direct string literal** — if the first argument is `"path/to/file.wasm"` (relative to the JS file's directory), use that path. The most common and reliable case.
2. **Inferred type with cached path** — if the argument's resolved type is a `WasmWrapperType` (carried in from a prior `compile` / `instantiate` call in the same code path), reuse the wrapper's path.
3. **`ArrayBuffer` + sibling `fetch` heuristic** — when instantiating from raw bytes (`new Uint8Array(...)`, `await response.arrayBuffer()`), Hexana walks up to the enclosing expression and looks for a sibling `fetch("...wasm")` call. If one is found, its literal is used.

If none of these match, type inference defers — no warnings are produced. This is intentional: dynamic / network-fetched modules cannot be statically analysed, and false negatives are preferable to false positives.

## Caching

`WasmBinaryDataCacheService` is an application-level service that parses each `.wasm` once and caches the resulting `ParsedWasmData` (imports, exports, types, component metadata) per `VirtualFile`. Every JS resolve in the same editor session reuses the cache. The service invalidates entries when the underlying `VirtualFile` changes.

This matters at scale: large modules (Skiko is ~14k functions) parse in well under a second, and editing JS code that calls into them stays responsive because every keystroke does *not* trigger a re-parse.

## TypeScript support

The handler is TypeScript-aware. When `WebAssembly.Instance`, `WebAssembly.Module`, or `WebAssemblyInstantiatedSource` is resolved through a `TypeScriptModule` named `WebAssembly` (i.e. the lib.dom.d.ts declarations), Hexana recognises them and applies the same augmentation. No `.d.ts` shim is required.

## Limitations in 0.9

- **Component-Model imports are not yet completed**. Exports for components are surfaced; imports return `null` and fall through to the default `WebAssembly.Imports` type.
- **No support for dynamic-path loading**. If your `.wasm` path is built from string concatenation or comes from a network response without a literal sibling `fetch`, type inference defers.
- **No quick-fixes** are wired on the implicit JS engine warnings — Hexana drives the type system, the JS engine drives the diagnostics, and the JS engine's default quick-fix surface does not know about WASM specifics.
- **No editor banner** when Hexana cannot resolve the `.wasm` referenced in a call. Diagnose by checking whether the literal path is resolvable from the JS file's directory.

## Compatibility

- Bundled with the JavaScript plugin. Works in **WebStorm** out of the box and in **IntelliJ IDEA / RustRover / PhpStorm / Rider** when the JavaScript plugin is enabled.
- Has no effect in PyCharm Community (no JavaScript plugin available) — Hexana's WASM viewer and MCP server still work; only the JS-side type inference is unavailable.
- Works for both **JavaScript** (`.js`, `.mjs`, `.cjs`) and **TypeScript** (`.ts`, `.tsx`, `.mts`, `.cts`) sources. JSX / TSX files inherit the same support.

## Tests

`idea-plugin/src/test/kotlin/org/jetbrains/hexana/javascript/JsResolveTest.kt` exercises:

- `testCalculatorWasmExportResolve` — multi-arg function with literal-union argument typing.
- `testClassicModule` — classic `WebAssembly.instantiate` flow.
- (Plus additional cases for `compile` / `compileStreaming` and the `fetch` heuristic.)

Fixtures live under `idea-plugin/src/test/data/js/` and are real `.wasm` binaries paired with `.js` files that exercise the recognised call shapes.

## See also

- [`features.md`](features.md) — Hexana feature reference.
- [`java-integration.md`](java-integration.md) — the sibling page covering GraalWasm and Chicory for Java sources.
- [`mcp-tools.md`](mcp-tools.md) — for the AI-assistant path to the same data the JS handler reads through the cache.
