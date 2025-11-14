package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.JvmByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

fun main() {
    //val file = File("/Users/user/ws/wasm-playground/simple/simple.wasm")
    //val file = File("/Users/user/ws/Hexana/wasm/src/commonTest/resources/string.wasm")
    val file = File("/Users/user/ws/Hexana/wasm/src/commonTest/resources/string.wasm")
    FileInputStream(file).use {
        val buffer = JvmByteBuffer(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()))
        val wasmModule = WasmParser().parse(buffer)
        val signatures = buffer.importSymbols(wasmModule)
        objectMapper.writeValueAsString(signatures).also(::println)
        val functions = buffer.listFunctions(wasmModule)
        objectMapper.writeValueAsString(functions).also(::println)

        val dataEntries = buffer.listDataSections(wasmModule)
        objectMapper.writeValueAsString(dataEntries).also(::println)
    }
}