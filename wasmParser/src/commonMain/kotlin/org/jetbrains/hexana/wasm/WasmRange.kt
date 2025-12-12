package org.jetbrains.hexana.wasm

interface WasmRange {
    val offset: Int
    val size: Int
}

data class WasmNamedRange(
    val range: WasmRange,
    val name: String
)

val WasmRange.intRange: IntRange
    get() = offset until offset + size

val WasmRange.longRange: LongRange
    get() = offset.toLong() until (offset + size).toLong()
