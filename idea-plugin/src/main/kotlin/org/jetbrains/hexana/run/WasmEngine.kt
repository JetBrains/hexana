package org.jetbrains.hexana.run

enum class WasmEngine(val displayName: String, val supportsComponentsModel: Boolean, val supportsDebug: Boolean) {
    WASMTIME("Wasmtime", true, true),
    WAMR("WAMR", false, true),
    GRAALWASM("GraalWasm", false, false),
    BROWSER("Browser", false, true),
    NODE_JS("Node.js", false, true),
    CHICORY_INTERPRETER("Chicory (Interpreter)", false, false),
    CHICORY_AOT("Chicory (AOT)", false, false),
    ;

    companion object {
        val DEFAULT = WASMTIME

        fun fromString(value: String?): WasmEngine =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
