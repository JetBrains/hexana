package org.jetbrains.hexana.tests

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser

suspend fun watStringToBuffer(wat: String): CommonByteBuffer =
    wat2wasm(wat).toCommonByteBuffer()

suspend fun watResourceToBuffer(resourcePath: String): CommonByteBuffer {
    val buffer = resourceAsByteBuffer(resourcePath)
    val watBytes = ByteArray(buffer.remaining())
    buffer.get(watBytes, 0, watBytes.size)
    val wat = watBytes.decodeToString()
    return wat2wasm(wat).toCommonByteBuffer()
}

suspend fun parseWat(wat: String): WasmModule {
    val buffer = watStringToBuffer(wat)
    val result = WasmParser().parse(buffer)
    return ((result as WasmParseSuccess).wasmModule as WasmModule)
}

suspend fun parseWatResource(resourcePath: String): WasmModule {
    val buffer = watResourceToBuffer(resourcePath)
    val result = WasmParser().parse(buffer)
    return ((result as WasmParseSuccess).wasmModule as WasmModule)
}