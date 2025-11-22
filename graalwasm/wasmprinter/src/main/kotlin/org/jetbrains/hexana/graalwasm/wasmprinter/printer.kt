package org.jetbrains.hexana.graalwasm.wasmprinter

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.ByteArrayOutputStream
import java.net.URL

private class WasmModuleMemory(
    private val memory: Value
) {
    fun write(ptr: Int, bytes: ByteArray) {
        for (i in bytes.indices) {
            memory.setArrayElement((ptr + i).toLong(), bytes[i].toInt())
        }
    }

    fun read(ptr: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = (memory.getArrayElement((ptr + i).toLong()).asInt() and 0xFF).toByte()
        }
        return out
    }
}

private class WasmPrinter() {
    private val context: Context
    private val module: Value
    private val memory: WasmModuleMemory
    private val allocInput: Value
    private val printWat: Value
    private val out = ByteArrayOutputStream()

    init {
        context = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        val wasmFile: URL? = WasmPrinter::class.java.getResource("/wasmprinter_wrapper.wasm")

        module = context.eval(Source.newBuilder("wasm", wasmFile).build())
        val mainInstance = module.newInstance(
            ProxyObject.fromMap(
                mapOf("env" to ProxyObject.fromMap(
                    mapOf("host_print" to ProxyExecutable { arguments ->
                        // Ensure arguments are properly cast to match the function definition
                        val ptr = arguments[0].asInt()
                        val len = arguments[1].asInt()
                        hostPrint(ptr, len)
                        null // WebAssembly requires void functions to return null
                    })
                ))
            ))

        val mem = mainInstance.getMember("exports").getMember("memory")
        memory = WasmModuleMemory(mem)

        allocInput = mainInstance.getMember("exports").getMember("alloc_input")
        printWat = mainInstance.getMember("exports").getMember("print_wat")
    }

    private fun hostPrint(ptr: Int, len: Int) {
        memory.read(ptr, len).let {
            out.write(it)
        }
    }

    fun print(bytes: ByteArray): String {
        val ptr = allocInput.execute(bytes.size).asInt()
        memory.write(ptr, bytes)

        printWat.execute(ptr, bytes.size)

        return out.toByteArray().decodeToString()
    }
}

fun printWat(bytes: ByteArray): String {
    return WasmPrinter().print(bytes)
}
