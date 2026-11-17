package org.jetbrains.hexana.tests

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise
import kotlin.js.js

private val defaultFetchInit: RequestInit = js("{}")

// Top-level JS interop helpers — Kotlin/Wasm requires js() in top-level function bodies or property initializers
private fun newJsObject(): JsAny = js("({})")
private fun jsSet(obj: JsAny, key: String, value: JsAny): Unit = js("obj[key] = value")
private fun jsGet(obj: JsAny, key: String): JsAny = js("obj[key]")
private fun jsSetFn(obj: JsAny, key: String, fn: (Int, Int) -> Unit): Unit = js("obj[key] = fn")
private fun wasmInstantiate(bytes: ArrayBuffer, imports: JsAny): Promise<JsAny> =
    js("WebAssembly.instantiate(bytes, imports)")
private fun getMemoryBuffer(memory: JsAny): ArrayBuffer = js("memory.buffer")
private fun jsCallIntToInt(fn: JsAny, arg: Int): Int = js("fn(arg)")
private fun jsCallIntInt(fn: JsAny, a: Int, b: Int): Unit = js("fn(a, b)")
private fun uint8Set(view: Uint8Array, index: Int, value: Int): Unit = js("view[index] = value")

// Module-level state shared by host callbacks (only one compiler instance exists)
private var outputBytes = mutableListOf<Byte>()
private var wasmMemory: JsAny? = null
private var wasmInstance: JsAny? = null

private fun hostPrint(ptr: Int, len: Int) {
    val buffer = getMemoryBuffer(wasmMemory!!)
    val view = Uint8Array(buffer, ptr, len)
    for (i in 0 until len) {
        outputBytes.add(view[i].toByte())
    }
}

private fun hostError(ptr: Int, len: Int) {
    val buffer = getMemoryBuffer(wasmMemory!!)
    val view = Uint8Array(buffer, ptr, len)
    val bytes = ByteArray(len) { view[it].toByte() }
    throw Exception(bytes.decodeToString())
}

private suspend fun ensureInit() {
    if (wasmInstance != null) return

    val response: Response = window.fetch("/kotlin/wat2wasm_full.wasm", defaultFetchInit).await()
    val wasmBytes: ArrayBuffer = response.arrayBuffer().await()

    val importObject = newJsObject()
    val env = newJsObject()
    jsSetFn(env, "host_print") { ptr, len -> hostPrint(ptr, len) }
    jsSetFn(env, "host_error") { ptr, len -> hostError(ptr, len) }
    jsSetFn(env, "host_debug") { _, _ -> }
    jsSet(importObject, "env", env)

    val instantiated: JsAny = wasmInstantiate(wasmBytes, importObject).await()
    wasmInstance = jsGet(instantiated, "instance")
    wasmMemory = jsGet(jsGet(wasmInstance!!, "exports"), "memory")
}

private fun compile(wat: String): ByteArray {
    outputBytes.clear()
    val bytes = wat.encodeToByteArray()
    val exports = jsGet(wasmInstance!!, "exports")
    val allocInput = jsGet(exports, "alloc_input")
    val wat2wasmFn = jsGet(exports, "wat2wasm")

    val ptr = jsCallIntToInt(allocInput, bytes.size)
    val memBuffer = getMemoryBuffer(wasmMemory!!)
    val memView = Uint8Array(memBuffer, ptr, bytes.size)
    for (i in bytes.indices) {
        uint8Set(memView, i, bytes[i].toInt() and 0xFF)
    }

    jsCallIntInt(wat2wasmFn, ptr, bytes.size)
    return outputBytes.toByteArray()
}

actual suspend fun wat2wasm(wat: String): ByteArray {
    ensureInit()
    return compile(wat)
}
