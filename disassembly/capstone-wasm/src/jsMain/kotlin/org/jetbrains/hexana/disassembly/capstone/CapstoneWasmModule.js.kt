package org.jetbrains.hexana.disassembly.capstone

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * Kotlin/JS actual for [CapstoneWasmModule] — drives `disassembler.wasm`
 * directly through the host `WebAssembly` global. Suitable for browser, Node,
 * Deno, Bun.
 *
 * `Long` arguments cross the WASM-JS boundary as `BigInt` (passing `BigInt`
 * for `i32` params is converted via `ToInt32`; passing `Number` for `i64`
 * throws `TypeError`). For uniformity, every argument is forwarded as
 * `BigInt`.
 */
internal actual class CapstoneWasmModule internal constructor(private val instance: dynamic) {
    actual fun call(export: String, args: LongArray): LongArray {
        val fn = instance.exports[export] ?: error("export not found: $export")
        val jsArgs = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) {
            jsArgs[i] = longToBigInt(args[i])
        }
        val result: dynamic = fn.apply(null, jsArgs)
        return convertResult(result)
    }

    actual fun readMemory(addr: Int, length: Int): ByteArray {
        val buffer = instance.exports.memory.buffer.unsafeCast<ArrayBuffer>()
        val view = Uint8Array(buffer, addr, length).asDynamic()
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = (view[i] as Int).toByte()
        return out
    }

    actual fun writeMemory(addr: Int, bytes: ByteArray) {
        val buffer = instance.exports.memory.buffer.unsafeCast<ArrayBuffer>()
        val view = Uint8Array(buffer, addr, bytes.size).asDynamic()
        for (i in bytes.indices) view[i] = bytes[i].toInt() and 0xFF
    }

    actual fun close() {
        // No-op — JS GC reclaims the instance once the last reference is dropped.
    }
}

internal actual suspend fun loadCapstoneWasmModule(bytes: ByteArray): CapstoneWasmModule {
    val u8 = Uint8Array(bytes.size)
    for (i in bytes.indices) u8.asDynamic()[i] = bytes[i].toInt() and 0xFF
    val result = WebAssembly.instantiate(u8.buffer).await()
    return CapstoneWasmModule(result.instance)
}

@Suppress("ClassName")
private external object WebAssembly {
    fun instantiate(buffer: ArrayBuffer, importObject: dynamic = definedExternally): Promise<dynamic>
}

private fun longToBigInt(value: Long): dynamic =
    js("BigInt")(value.toString())

private fun convertResult(value: dynamic): LongArray {
    if (value == null) return EMPTY
    val type = jsTypeOf(value)
    return when (type) {
        "undefined" -> EMPTY
        "number" -> longArrayOf((value.unsafeCast<Number>()).toLong())
        "bigint" -> longArrayOf(value.toString().unsafeCast<String>().toLong())
        else -> EMPTY
    }
}

private fun jsTypeOf(value: dynamic): String = js("typeof value").unsafeCast<String>()

private val EMPTY = LongArray(0)
