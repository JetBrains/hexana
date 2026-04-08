package org.jetbrains.hexana.run

enum class WasmEngine(val displayName: String) {
    WASMTIME("Wasmtime"),
    GRAALWASM("GraalWasm"),
    ;

    companion object {
        val DEFAULT = WASMTIME

        fun fromString(value: String?): WasmEngine =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
