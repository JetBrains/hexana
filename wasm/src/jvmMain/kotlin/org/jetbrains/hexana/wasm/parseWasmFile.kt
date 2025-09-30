package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.JvmByteBuffer
import java.io.File
import java.nio.ByteBuffer

fun WasmParser.parseFile(path: String): WasmModule? {
    return parse(JvmByteBuffer(ByteBuffer.wrap(File(path).readBytes())))
}