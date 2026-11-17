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

internal class WasmPrinter(
    val foldInstructions: Boolean = true,
    val nameUnnamed: Boolean = true,
    val printOffsets: Boolean = false
) : AutoCloseable {
    private val context: Context = initWasmContext()
    private val module: Value
    private val memory: WasmModuleMemory
    private val allocInput: Value
    private val printWat: Value
    private val out = ByteArrayOutputStream()
    private val warning = ByteArrayOutputStream()

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
        memory.read(ptr, len).let {
            warning.write(it)
        }
    }

    fun print(bytes: ByteArray): WatWithWarnings {
        val ptr = allocInput.execute(bytes.size).asInt()
        memory.write(ptr, bytes)

        val flagFoldInstructions = foldInstructions.let { if (it) 1 else 0 }
        val flagNameUnnamed = nameUnnamed.let { if (it) 1 else 0 }
        val flagPrintOffsets = printOffsets.let { if (it) 1 else 0 }

        printWat.execute(ptr, bytes.size, flagFoldInstructions, flagNameUnnamed, flagPrintOffsets)

        return WatWithWarnings(out.toByteArray().decodeToString(),
            warning.toByteArray().decodeToString().takeIf { it.isNotEmpty() })
    }

    // Releases the underlying polyglot Context. See the matching comment on
    // [org.jetbrains.hexana.graalwasm.wat2wasm.Wat2Wasm.close].
    override fun close() {
        context.close()
    }
}

data class WatWithWarnings(val wat: String, val warnings: String?)
