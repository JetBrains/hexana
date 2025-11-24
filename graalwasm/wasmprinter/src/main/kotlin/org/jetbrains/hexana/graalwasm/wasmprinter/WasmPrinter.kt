package org.jetbrains.hexana.graalwasm.wasmprinter

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.graalwasm.WasmModuleMemory
import org.jetbrains.hexana.graalwasm.initWasmContext
import java.io.ByteArrayOutputStream
import java.net.URL

internal class WasmPrinter() {
    private val context: Context = initWasmContext()
    private val module: Value
    private val memory: WasmModuleMemory
    private val allocInput: Value
    private val printWat: Value
    private val out = ByteArrayOutputStream()

    init {

        val wasmFile: URL? = WasmPrinter::class.java.getResource("/wasmprinter_wrapper.wasm")

        module = context.eval(Source.newBuilder("wasm", wasmFile).build())
        val mainInstance = module.newInstance(
            ProxyObject.fromMap(
                mapOf("env" to ProxyObject.fromMap(
                    mapOf("host_print" to ProxyExecutable { arguments ->
                        val ptr = arguments[0].asInt()
                        val len = arguments[1].asInt()
                        hostPrint(ptr, len)
                        null
                    },
                    "host_error" to ProxyExecutable { arguments ->
                        val ptr = arguments[0].asInt()
                        val len = arguments[1].asInt()
                        hostError(ptr, len)
                        null
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

    private fun hostError(ptr: Int, len: Int) {
        throw Exception(memory.read(ptr, len).decodeToString())
    }

    fun print(bytes: ByteArray): String {
        val ptr = allocInput.execute(bytes.size).asInt()
        memory.write(ptr, bytes)

        printWat.execute(ptr, bytes.size)

        return out.toByteArray().decodeToString()
    }
}
