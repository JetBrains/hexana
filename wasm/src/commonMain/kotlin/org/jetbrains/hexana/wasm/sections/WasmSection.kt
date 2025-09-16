package org.jetbrains.hexana.wasm.sections

sealed class WasmSection(val size: Int, val offset: Int)

class WasmSectionUnresolved(val id: Byte, size: Int, offset: Int): WasmSection(size, offset)