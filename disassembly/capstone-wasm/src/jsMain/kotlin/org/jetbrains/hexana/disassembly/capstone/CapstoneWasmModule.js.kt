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
 * The WebAssembly JS-API is strict about argument types: `i32` params accept
 * only `Number` (ToInt32 throws on `BigInt`) and `i64` params accept only
 * `BigInt` (ToBigInt throws on `Number`). The only `i64` parameter across our
 * export contract is `base_va` (arg index 3 of every `decode_*`); everything
 * else is `i32`.
 */
internal actual class CapstoneWasmModule internal constructor(private val instance: dynamic) {
    actual fun call(export: String, args: LongArray): LongArray {
        val fn = instance.exports[export] ?: error("export not found: $export")
        val jsArgs = arrayOfNulls<Any?>(args.size)
        val baseVaIndex = if (export.startsWith("decode_")) 3 else -1
        for (i in args.indices) {
            jsArgs[i] = if (i == baseVaIndex) longToBigInt(args[i]) else args[i].toInt()
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
