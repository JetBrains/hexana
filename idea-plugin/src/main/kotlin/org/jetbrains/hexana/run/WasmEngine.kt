package org.jetbrains.hexana.run

enum class WasmEngine(
    val displayName: String,
    val supportsComponentsModel: Boolean,
    val supportsDebug: Boolean,
    /** Whether the runtime can invoke a specific exported function by name. */
    val supportsExportInvocation: Boolean = true,
) {
    WASMTIME("Wasmtime", true, true),
    WAMR("WAMR", false, true),
    GRAALWASM("GraalWasm", false, false),
    BROWSER("Browser", false, true),
    NODE_JS("Node.js", false, true),
    CHICORY_INTERPRETER("Chicory (Interpreter)", false, false),
    CHICORY_AOT("Chicory (AOT)", false, false),
    WAZERO("Wazero", false, false, supportsExportInvocation = false),
    ;

    companion object {
        val DEFAULT = WASMTIME

        fun fromString(value: String?): WasmEngine =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
