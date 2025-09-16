package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.CommonByteBuffer
import java.io.File
import java.nio.ByteBuffer

fun WasmParser.parseFile(path: String): WasmModule? {
    return parse(CommonByteBuffer(ByteBuffer.wrap(File(path).readBytes())))
}