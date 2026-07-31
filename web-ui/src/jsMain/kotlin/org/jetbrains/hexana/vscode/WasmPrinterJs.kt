package org.jetbrains.hexana.vscode

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.fetch.Response
import kotlin.js.Promise

/**
 * Browser-side wrapper around `wasmprinter_wrapper.wasm`.
 *
 * Uses the browser WebAssembly API to instantiate the same `.wasm` binary
 * that the IntelliJ plugin runs via GraalWasm. The module exports:
 * - `memory`      – linear memory
 * - `alloc_input`  – allocates a buffer and returns a pointer
 * - `print_wat`    – converts wasm bytes to WAT text
 *
 * and imports two host callbacks from `env`:
 * - `host_print(ptr, len)` – receives WAT output chunks
 * - `host_error(ptr, len)` – receives error/warning messages
 */
object WasmPrinterJs {

    private var memory: dynamic = null
    private var allocInput: dynamic = null
    private var printWatFn: dynamic = null

    /** Collects output chunks during a single [printWat] call. */
    private var outChunks = mutableListOf<ByteArray>()
    /** Collects error/warning chunks during a single [printWat] call. */
    private var errChunks = mutableListOf<ByteArray>()

    private var initialized = false

    /**
     * Loads and instantiates the wasmprinter module. Must be called (and
     * awaited) before [printWat].
     */
    suspend fun ensureInitialized() {
        if (initialized) return

        val uri = js("window.__wasmPrinterUri") as? String
            ?: error("wasmprinter URI not set – check webview HTML injection")

        val response = jsFetch(uri).await()
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch wasmprinter wasm: ${response.status}")
        }
        val buffer = response.arrayBuffer().await()

        val importObject: dynamic = js("{}")
        val env: dynamic = js("{}")

        env.host_print = fun(ptr: Int, len: Int) {
            outChunks.add(readMemory(ptr, len))
        }
        env.host_error = fun(ptr: Int, len: Int) {
            errChunks.add(readMemory(ptr, len))
        }
        importObject.env = env

        val result = wasmInstantiate(buffer, importObject).await()

        val exports = result.instance.exports
        memory = exports.memory
        allocInput = exports.alloc_input
        printWatFn = exports.print_wat
        initialized = true
    }

    private fun readMemory(ptr: Int, len: Int): ByteArray {
        val memBuffer = memory.buffer.unsafeCast<ArrayBuffer>()
        val view = Uint8Array(memBuffer, ptr, len)
        return ByteArray(len) { i -> view[i] }
    }

    private fun writeMemory(ptr: Int, bytes: ByteArray) {
        val memBuffer = memory.buffer.unsafeCast<ArrayBuffer>()
        val view = Uint8Array(memBuffer, ptr, bytes.size)
        for (i in bytes.indices) {
            view.asDynamic()[i] = bytes[i].toInt() and 0xFF
        }
    }

    /**
     * Converts raw `.wasm` bytes to WAT text using the embedded wasmprinter.
     *
     * Must be called after [ensureInitialized] has completed.
     */
    fun printWat(
        wasmBytes: ByteArray,
        foldInstructions: Boolean = false,
        nameUnnamed: Boolean = true,
        printOffsets: Boolean = false,
    ): WatResult {
        outChunks = mutableListOf()
        errChunks = mutableListOf()

        val ptr = allocInput(wasmBytes.size) as Int
        writeMemory(ptr, wasmBytes)

        val flagFold = if (foldInstructions) 1 else 0
        val flagName = if (nameUnnamed) 1 else 0
        val flagOffsets = if (printOffsets) 1 else 0

        printWatFn(ptr, wasmBytes.size, flagFold, flagName, flagOffsets)

        val wat = outChunks.joinToString("") { it.decodeToString() }
        val warnings = errChunks.joinToString("") { it.decodeToString() }.takeIf { it.isNotEmpty() }

        return WatResult(wat, warnings)
    }
}

data class WatResult(val wat: String, val warnings: String?)

private fun jsFetch(url: String): Promise<Response> =
    js("fetch(url)").unsafeCast<Promise<Response>>()

private fun wasmInstantiate(buffer: ArrayBuffer, importObject: dynamic): Promise<dynamic> =
    js("WebAssembly.instantiate(buffer, importObject)").unsafeCast<Promise<dynamic>>()
