package org.jetbrains.hexana.disassembly.capstone

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser
import org.jetbrains.hexana.disassembly.capstone.aot.DisassemblerWasmMachine
import org.jetbrains.hexana.disassembly.capstone.redline.DisassemblerWasm as RedlineDisassemblerWasm

/**
 * JVM actual for [CapstoneWasmModule]. The disassembler WASM is **build-time
 * compiled** into one of two flavours, picked at module-load time via the
 * `hexana.disassembly.backend` system property:
 *
 *  - **`aot` (default)** — Chicory's build-time-compiler lowers each WASM
 *    function to JVM bytecode (`DisassemblerWasmMachine`) plus a slim "meta"
 *    .wasm for the few functions that exceed the JVM 64 KB method limit.
 *    Pure-JVM portability; no native code.
 *  - **`redline`** — chicory-redline uses Cranelift to compile the same WASM
 *    to native machine code per (OS, arch) at build time. At runtime
 *    `DisassemblerWasm.builder().build()` selects between Panama FFM /
 *    jffi / bytecode fallback via SPI. Faster on compute-heavy decode
 *    (Cranelift-grade codegen vs JVM bytecode); ships native slices for
 *    the supported (Linux/macOS/Windows × x86_64/aarch64) matrix. See
 *    `chicory-redline-expert` agent for trade-offs.
 *
 * The `bytes` parameter from the [loadCapstoneWasmModule] expect contract is
 * unused on JVM: both backends serve from the pre-compiled bundle. Other
 * platforms (js / wasmJs) still consume the full .wasm bytes the caller
 * provides.
 *
 * Not thread-safe: a Chicory [Instance] owns a single linear memory and a
 * single call stack. Callers that need parallel decoding must create separate
 * [CapstoneWasmModule] instances.
 */
internal actual class CapstoneWasmModule internal constructor(
    private val instance: Instance,
    /**
     * The Redline `UniversalInstance` when the redline backend is active —
     * holds off-heap native memory + code pages that must be released via
     * [close]. Null on the AOT path; closing is a no-op there.
     */
    private val nativeResource: AutoCloseable? = null,
) {
    private val exports = HashMap<String, ExportFunction>()
    private val memory: Memory = instance.memory()

    actual fun call(export: String, args: LongArray): LongArray {
        val fn = exports.getOrPut(export) { instance.export(export) }
        return fn.apply(*args) ?: EMPTY
    }

    actual fun readMemory(addr: Int, length: Int): ByteArray =
        memory.readBytes(addr, length)

    actual fun writeMemory(addr: Int, bytes: ByteArray) {
        memory.write(addr, bytes)
    }

    actual fun close() {
        exports.clear()
        nativeResource?.close()
    }

    private companion object {
        private val EMPTY = LongArray(0)
    }
}

@Suppress("UNUSED_PARAMETER") // see KDoc above — JVM serves the pre-compiled bundle
internal actual suspend fun loadCapstoneWasmModule(bytes: ByteArray): CapstoneWasmModule {
    val backend = System.getProperty(BACKEND_PROPERTY, BACKEND_AOT).lowercase()
    return when (backend) {
        BACKEND_REDLINE -> loadRedline()
        BACKEND_AOT -> loadAot()
        else -> loadAot()
    }
}

/**
 * AOT path: parse the meta .wasm and bind the generated
 * [DisassemblerWasmMachine] class (JVM bytecode).
 */
private fun loadAot(): CapstoneWasmModule {
    val metaStream = CapstoneWasmModule::class.java.getResourceAsStream(AOT_META_RESOURCE)
        ?: error("AOT meta wasm not found on classpath at $AOT_META_RESOURCE")
    val module = metaStream.use { Parser.parse(it) }
    val instance = Instance.builder(module)
        .withMachineFactory { inst -> DisassemblerWasmMachine(inst) }
        .build()
    return CapstoneWasmModule(instance)
}

/**
 * Redline path: the generated [RedlineDisassemblerWasm] wraps the Cranelift-
 * compiled native code (per-platform slices) plus the bytecode fallback. Its
 * builder picks the best available backend via SPI. The resulting
 * [io.roastedroot.redline.api.RedlineInstance] exposes the underlying Chicory
 * [Instance]; we wrap that in [CapstoneWasmModule] and carry the
 * RedlineInstance as `nativeResource` so `close()` releases the off-heap
 * memory + code pages.
 */
private fun loadRedline(): CapstoneWasmModule {
    val redline = RedlineDisassemblerWasm.builder().build()
    return CapstoneWasmModule(redline.instance(), redline)
}

private const val BACKEND_PROPERTY = "hexana.disassembly.backend"
private const val BACKEND_AOT = "aot"
private const val BACKEND_REDLINE = "redline"
private const val AOT_META_RESOURCE =
    "/org/jetbrains/hexana/disassembly/capstone/aot/DisassemblerWasm.meta"
