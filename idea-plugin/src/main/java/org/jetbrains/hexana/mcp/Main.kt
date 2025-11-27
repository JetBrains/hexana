package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

fun main() {
    //val file = File("/Users/user/ws/wasm-playground/simple/simple.wasm")
    //val file = File("/Users/user/ws/Hexana/wasm/src/commonTest/resources/string.wasm")
    //val file = File("/Users/user/ws/Hexana/wasm/src/commonTest/resources/string.wasm")
    val file = File("/Users/user/ws/Hexana/wasmParser/src/commonTest/resources/skiko.wasm")
    FileInputStream(file).use {
        val buffer = it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()).toCommonByteBuffer()
        val wasmModule = WasmParser().parse(buffer)
        with(buffer) {
            printJson("importedSymbols", wasmModule, CommonByteBuffer::importFunctions)
            //printJson(wasmModule, CommonByteBuffer::listFunctions)
            printJson("listDataSections", wasmModule,CommonByteBuffer::listDataSections)
            printJson("listExports", wasmModule, CommonByteBuffer::listExports)
            //printJson("listExportedFunctions", wasmModule, CommonByteBuffer::listExportedFunctions)

            //printJson("functionsByIndex (1,2,8)", wasmModule) {
            //    functionsByIndices(wasmModule, listOf(1, 2, 8))
            //}
            printJson("listTypes", wasmModule, CommonByteBuffer::listTypes)
            printJson("listDataSegments", wasmModule, CommonByteBuffer::listDataSegments)
            printJson("listMemory", wasmModule, CommonByteBuffer::listMemory)
        }






    }
}
inline fun <T> CommonByteBuffer.printJson(msg:String? = "", wasmModule: WasmModule?, body: CommonByteBuffer.(WasmModule?) -> T) {
    val str = objectMapper.writeValueAsString(body(wasmModule))
    println("$msg:preview: ${str.take(1000)}")
    println("$msg:size: ${str.length}")
}
