package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import java.io.File

fun WasmParser.parseFile(path: String): WasmParseResult {
    return parse(File(path).readBytes().toCommonByteBuffer())
}