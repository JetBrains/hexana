package org.jetbrains.hexana.run

enum class WasmEngine(val displayName: String, val supportsComponentsModel: Boolean, val supportsDebug: Boolean) {
    WASMTIME("Wasmtime", true, true),
    WAMR("WAMR", false, true),
    GRAALWASM("GraalWasm", false, false),
    ;

    companion object {
        val DEFAULT = WASMTIME

        fun fromString(value: String?): WasmEngine =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
