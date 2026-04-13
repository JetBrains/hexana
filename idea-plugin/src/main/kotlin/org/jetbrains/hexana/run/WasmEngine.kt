package org.jetbrains.hexana.run

enum class WasmEngine(val displayName: String, val supportsComponentsModel: Boolean) {
    WASMTIME("Wasmtime", true),
    GRAALWASM("GraalWasm", false),
    ;

    companion object {
        val DEFAULT = WASMTIME

        fun fromString(value: String?): WasmEngine =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
