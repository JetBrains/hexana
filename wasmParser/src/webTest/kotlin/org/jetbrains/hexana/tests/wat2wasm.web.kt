package org.jetbrains.hexana.tests

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.fetch.RequestInit
import kotlin.js.js

private val defaultFetchInit: RequestInit = js("{}")

private class Wat2WasmWeb {
    private var instance: dynamic = null
    private var memory: dynamic = null
    private var outputBytes = mutableListOf<Byte>()

    suspend fun init() {
        if (instance != null) return

        val response = window.fetch("/kotlin/wat2wasm_full.wasm", defaultFetchInit).await()
        val wasmBytes: ArrayBuffer = response.arrayBuffer().await()

        val importObject = js("{}")
        val env = js("{}")
        env.host_print = { ptr: Int, len: Int -> hostPrint(ptr, len) }
        env.host_error = { ptr: Int, len: Int -> hostError(ptr, len) }
        env.host_debug = { _: Int, _: Int -> }
        importObject.env = env

        val result = js("WebAssembly.instantiate(wasmBytes, importObject)").unsafeCast<dynamic>()
        val instantiated = result.await()
        instance = instantiated.instance
        memory = instance.exports.memory
    }

    private fun hostPrint(ptr: Int, len: Int) {
        val buffer = memory.buffer.unsafeCast<ArrayBuffer>()
        val view = Uint8Array(buffer, ptr, len)
        for (i in 0 until len) {
            outputBytes.add(view[i].toByte())
        }
    }

    private fun hostError(ptr: Int, len: Int) {
        val buffer = memory.buffer.unsafeCast<ArrayBuffer>()
        val view = Uint8Array(buffer, ptr, len)
        val bytes = ByteArray(len) { view[it].toByte() }
        throw Exception(bytes.decodeToString())
    }

    fun compile(wat: String): ByteArray {
        outputBytes.clear()
        val bytes = wat.encodeToByteArray()
        val allocInput = instance.exports.alloc_input
        val wat2wasmFn = instance.exports.wat2wasm

        val ptr = allocInput(bytes.size).unsafeCast<Int>()
        val memBuffer = memory.buffer.unsafeCast<ArrayBuffer>()
        val memView = Uint8Array(memBuffer, ptr, bytes.size)
        for (i in bytes.indices) {
            memView.asDynamic()[i] = bytes[i]
        }

        wat2wasmFn(ptr, bytes.size)
        return outputBytes.toByteArray()
    }
}

private var compiler: Wat2WasmWeb? = null

actual suspend fun wat2wasm(wat: String): ByteArray {
    val c = compiler ?: Wat2WasmWeb().also { it.init(); compiler = it }
    return c.compile(wat)
}
