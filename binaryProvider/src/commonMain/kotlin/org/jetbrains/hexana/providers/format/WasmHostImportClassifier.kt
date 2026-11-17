package org.jetbrains.hexana.providers.format

/**
 * Heuristic classifier for whether a core-module import comes from a well-known
 * host/runtime namespace (as opposed to an application-provided module). Used by
 * the diff's supply-chain lens to flag newly-introduced host capabilities.
 *
 * Core WASM has no formal notion of "host" imports — this is a curated list of
 * the namespaces real toolchains emit (Emscripten `env`, the WASI preview
 * modules, and the Component Model `wasi:` namespace). Conservative by design:
 * unknown modules are treated as application imports.
 */
public object WasmHostImportClassifier {

    private val HOST_MODULES = setOf(
        "env",
        "wasi_snapshot_preview1",
        "wasi_snapshot_preview2",
        "wasi_unstable",
    )

    public fun isHostImport(module: String, @Suppress("UNUSED_PARAMETER") field: String): Boolean =
        module in HOST_MODULES || module.startsWith("wasi:") || module.startsWith("wasi_")
}
