package org.jetbrains.hexana.graalwasm.wat2wasm

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.graalwasm.WasmModuleMemory
import org.jetbrains.hexana.graalwasm.initWasmContext
import java.io.ByteArrayOutputStream
import java.net.URL


internal class Wat2Wasm() : AutoCloseable {
    private val context: Context = initWasmContext()
    private val module: Value
    private val memory: WasmModuleMemory
    private val allocInput: Value
    private val wat2wasm: Value
    private val out = ByteArrayOutputStream()

    init {

        val wasmFile: URL? = Wat2Wasm::class.java.getResource("/wat2wasm_full.wasm")

        module = context.eval(Source.newBuilder("wasm", wasmFile).build())
        val mainInstance = module.newInstance(
            ProxyObject.fromMap(
                mapOf("env" to ProxyObject.fromMap(
                    mapOf("host_print" to ProxyExecutable { arguments ->
                        // Ensure arguments are properly cast to match the function definition
                        val ptr = arguments[0].asInt()
                        val len = arguments[1].asInt()
                        hostPrint(ptr, len)
                    }, "host_error" to ProxyExecutable { arguments ->
                        val ptr = arguments[0].asInt()
                        val len = arguments[1].asInt()
                        hostError(ptr, len)
                    }, "host_debug" to ProxyExecutable { arguments ->
                        val ptr = arguments[0].asInt()
                        val len = arguments[1].asInt()
                        hostDebug(ptr, len)
                    })
                ))
            ))

        val mem = mainInstance.getMember("exports").getMember("memory")
        memory = WasmModuleMemory(mem)

        allocInput = mainInstance.getMember("exports").getMember("alloc_input")
        wat2wasm = mainInstance.getMember("exports").getMember("wat2wasm")
    }

    private fun hostPrint(ptr: Int, len: Int) {
        memory.read(ptr, len).let {
            out.write(it)
        }
    }

    private fun hostError(ptr: Int, len: Int) {
        throw Exception(memory.read(ptr, len).decodeToString())
    }

    private fun hostDebug(ptr: Int, len: Int) {
        println(memory.read(ptr, len).decodeToString())
    }

    fun wat2wasm(str: String): ByteArray {
        val bytes = str.encodeToByteArray()
        val ptr = allocInput.execute(bytes.size).asInt()
        memory.write(ptr, bytes)

        wat2wasm.execute(ptr, bytes.size)

        return out.toByteArray()
    }

    // Releases the underlying polyglot Context. Without this, every helper
    // call leaked a fresh Engine + Context + WasmInstance + Source quartet
    // (see hprof analysis 2026-05-24) — Truffle keeps the entire AST graph
    // pinned for every load.
    override fun close() {
        context.close()
    }
}

fun wat2wasm(str: String): ByteArray = Wat2Wasm().use { it.wat2wasm(str) }
