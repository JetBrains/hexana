@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.jetbrains.hexana.disassembly.capstone

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Kotlin/Wasm (wasmJs) actual for [CapstoneWasmModule] — bridges out to the
 * host `WebAssembly` global via JS interop. Kotlin/Wasm and the host
 * `disassembler.wasm` instance are two separate WASM modules sharing only the
 * JS host: byte transfers cross the JS boundary one element at a time. Fine
 * for correctness; perf-sensitive workloads should switch to bulk transfer
 * helpers (typed-array view sharing) once we need them.
 *
 * `Long` arguments are forwarded as `BigInt` over the bridge — passing
 * `Number` for `i64` params throws `TypeError` in the JS WebAssembly API, and
 * `BigInt` is silently truncated to `i32` for `i32` params (via ToInt32). So
 * one uniform BigInt path covers both cases.
 */
internal actual class CapstoneWasmModule internal constructor(private val instance: JsAny) {
    actual fun call(export: String, args: LongArray): LongArray {
        val jsArgs = jsNewArray(args.size)
        for (i in args.indices) {
            jsSetBigIntAt(jsArgs, i, args[i].toString())
        }
        val resultStr = jsCallExport(instance, export, jsArgs) ?: return EMPTY
        if (resultStr.length < 2) return EMPTY
        // Tag is "b:" (bigint) or "n:" (number); rest is decimal payload.
        return longArrayOf(resultStr.substring(2).toLong())
    }

    actual fun readMemory(addr: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        for (i in 0 until length) {
            out[i] = jsReadMemoryByte(instance, addr, i).toByte()
        }
        return out
    }

    actual fun writeMemory(addr: Int, bytes: ByteArray) {
        for (i in bytes.indices) {
            jsWriteMemoryByte(instance, addr, i, bytes[i].toInt() and 0xFF)
        }
    }

    actual fun close() {
        // No-op — JS host GC reclaims the instance once the last Kotlin reference is dropped.
    }
}

internal actual suspend fun loadCapstoneWasmModule(bytes: ByteArray): CapstoneWasmModule {
    val u8 = jsNewUint8Array(bytes.size)
    for (i in bytes.indices) {
        jsSetUint8At(u8, i, bytes[i].toInt() and 0xFF)
    }
    val instance: JsAny = jsInstantiate(u8).await<JsAny>()
    return CapstoneWasmModule(instance)
}

@JsFun("(n) => new Uint8Array(n)")
private external fun jsNewUint8Array(n: Int): JsAny

@JsFun("(u8, i, v) => { u8[i] = v; }")
private external fun jsSetUint8At(u8: JsAny, i: Int, v: Int)

@JsFun("(u8) => WebAssembly.instantiate(u8).then(r => r.instance)")
private external fun jsInstantiate(u8: JsAny): Promise<JsAny>

@JsFun("(n) => new Array(n)")
private external fun jsNewArray(n: Int): JsAny

@JsFun("(arr, i, s) => { arr[i] = BigInt(s); }")
private external fun jsSetBigIntAt(arr: JsAny, i: Int, s: String)

@JsFun(
    "(instance, name, args) => {" +
        "  const r = instance.exports[name].apply(null, args);" +
        "  if (r === undefined || r === null) return null;" +
        "  return typeof r === 'bigint' ? ('b:' + r.toString()) : ('n:' + r.toString());" +
        "}",
)
private external fun jsCallExport(instance: JsAny, name: String, args: JsAny): String?

@JsFun(
    "(instance, addr, idx) => {" +
        "  return new Uint8Array(instance.exports.memory.buffer)[addr + idx];" +
        "}",
)
private external fun jsReadMemoryByte(instance: JsAny, addr: Int, idx: Int): Int

@JsFun(
    "(instance, addr, idx, v) => {" +
        "  new Uint8Array(instance.exports.memory.buffer)[addr + idx] = v;" +
        "}",
)
private external fun jsWriteMemoryByte(instance: JsAny, addr: Int, idx: Int, v: Int)

private val EMPTY = LongArray(0)
