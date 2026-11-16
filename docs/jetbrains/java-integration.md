---
title: Java-Side WebAssembly API Support (0.9)
description: Hexana's code completion and inspections for Java sources using GraalWasm and Chicory WebAssembly runtimes.
version: "0.9"
---

# Java-Side WebAssembly API Support

Hexana 0.9 understands two Java-side WebAssembly runtimes and provides completion plus inspections for code that uses them:

- **GraalWasm** — `org.graalvm.polyglot.*` API.
- **Chicory** — `com.dylibso.chicory.*` JVM-native WASM runtime by Dylibso.

This support is conditional on the Java module being present in the host IDE. The optional descriptor `org.jetbrains.hexana.java.xml` is loaded when `com.intellij.modules.java` is available.

## Why this exists

When you call a WebAssembly module from Java code — `instance.export("compute").apply(...)` in Chicory, or `instance.getMember("exports").invokeMember("compute", ...)` in GraalWasm — the export name is a string literal. The Java compiler cannot tell you whether `"compute"` actually exists in the module, or whether you are passing the right number / type of arguments. Hexana indexes the `.wasm` files in your project and resolves those string literals back to their declared exports, then surfaces mismatches as IDE warnings.

## What you get

### Completion

Two completion contributors registered at `order="first"` so they win over generic string completion:

- `GraalWasmJavaCompletionContributor` — completes inside GraalWasm calls. Recognised call shapes:
  - `Source.newBuilder("wasm", url, ...)` — first argument completion is a no-op (always `"wasm"`), but subsequent code is recognised.
  - `Context.eval(source)` followed by `module.newInstance(ProxyObject.fromMap(Map.of(...)))` — completes import-map keys against the module's imports.
  - `instance.getMember("exports")` — completes the `"exports"` literal.
  - `exports.invokeMember("name", ...)` — completes `"name"` against the module's exports.
  - `exports.hasMember("name")` and `getMember("name")` — same completion.

- `ChicoryJavaCompletionContributor` — completes inside Chicory calls. Recognised call shapes:
  - `Parser.parse(path)` — recognised; no string completion needed.
  - `Instance.builder(module).withImportValues(ImportValues.builder()...).build()` — completes import module names and item names inside the builder DSL.
  - `instance.export("name")` — completes `"name"` against the module's exports. Scoped to the closest sibling builder so multiple instances in the same method don't cross-pollinate.
  - `new HostFunction("module", "name", ...)` — completes both module and name.
  - `Store.addFunction(...)` — same.
  - `FunctionType.of(ValType.I32, ValType.I32)` — recognised for argument-type checking.

### Inspections

Four inspections defined directly in `org.jetbrains.hexana.java.xml`:

| Inspection | Short name | Level | Triggers when |
|---|---|---|---|
| Unresolved WebAssembly export name | `WasmExportInspection` | WARNING | The string literal passed as an export name does not match any export in any `.wasm` file in scope. |
| WebAssembly export argument count mismatch | `WasmExportArgCountInspection` | WARNING | The number of arguments passed to an export call does not match the export's function signature. |
| WebAssembly export argument type mismatch | `WasmExportArgTypeInspection` | WARNING | An argument's Java type does not coerce to the export parameter's WASM type. |
| Unresolved WebAssembly import name | `WasmImportInspection` | WARNING | A host-function declaration (Chicory `HostFunction`, GraalWasm import-map entry) does not match any import declared by any `.wasm` in scope. |

All four are grouped under the **WebAssembly** inspection category in **Settings → Editor → Inspections**.

### Index

`JavaWasmReferenceIndex` (registered as `fileBasedIndex`) maps Java string literals that look like WASM names back to their owning `.wasm` files. The index keys on the string literal contents and stores the surrounding call shape so resolution can disambiguate exports from imports without re-parsing the Java file.

## How resolution works

When you type `instance.export("|")` (caret at `|`):

1. The completion contributor recognises the call shape (`instance.export(String)`).
2. It walks back through the local scope, looking for the closest `Parser.parse(path)` or `Source.newBuilder(...).uri(...)` that produced `instance`. This is the **scoped resolution** behaviour added in 0.9 — completion is anchored to the specific `.wasm` file the caller chose, not the union of all WASM files in the project.
3. Hexana resolves that path to a `VirtualFile`, queries `WasmExportIndex` for the export list, and offers the names as completion variants.

The same resolution path is reused by the inspections: if you write `instance.export("nope")` and `nope` is not in the resolved module's exports, `WasmExportInspection` warns. If the path itself cannot be resolved (e.g. the `.wasm` is downloaded at runtime), the inspections defer — no warning is produced.

## Limitations in 0.9

- **No quick-fixes** are wired on the inspections in 0.9. Mismatches are reported but not auto-correctable.
- **Cross-call argument typing** is local — if a parameter is passed through several local variables, the inspector may not track its type back to the call site.
- **Only synchronous APIs**. Async patterns in GraalWasm (`Value.execute()` returning a Promise) are not yet special-cased by completion.
- **No Kotlin support** — the Java module covers Java sources only. Kotlin Hexana users get the WASM analysis features but not the API-level completion / inspections.

## See also

- [`features.md`](features.md) — Hexana feature reference.
- [`mcp-tools.md`](mcp-tools.md) — AI assistants can use these tools to verify the same kinds of cross-file claims at conversation time.
- Tests under `idea-plugin/src/test/kotlin/org/jetbrains/hexana/javaLang/` cover the completion and inspection contributors.
