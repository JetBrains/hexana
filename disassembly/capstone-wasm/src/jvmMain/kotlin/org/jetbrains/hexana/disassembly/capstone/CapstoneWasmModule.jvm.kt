package org.jetbrains.hexana.disassembly.capstone

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser
import org.jetbrains.hexana.disassembly.capstone.aot.DisassemblerWasmMachine

/**
 * JVM actual for [CapstoneWasmModule]. The disassembler WASM is **build-time
 * AOT-compiled** (see `aotCompileDisassemblerWasm` in `build.gradle.kts`): the
 * Chicory build-time-compiler lowers each WASM function to JVM bytecode and
 * emits a `DisassemblerWasmMachine` class plus a slim "meta" .wasm containing
 * only the functions that exceeded the JVM 64 KB method limit and fall back
 * to the interpreter at runtime. At load time we just parse the meta and bind
 * the generated machine — no in-process bytecode generation.
 *
 * The `bytes` parameter from the [loadCapstoneWasmModule] expect contract is
 * unused on JVM: we serve from the pre-compiled bundle regardless. Other
 * platforms (js / wasmJs) still consume the full .wasm bytes the caller
 * provides.
 *
 * Not thread-safe: a Chicory [Instance] owns a single linear memory and a
 * single call stack. Callers that need parallel decoding must create separate
 * [CapstoneWasmModule] instances.
 */
internal actual class CapstoneWasmModule internal constructor(private val instance: Instance) {
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
    }

    private companion object {
        private val EMPTY = LongArray(0)
    }
}

@Suppress("UNUSED_PARAMETER") // see KDoc above — JVM serves the AOT-compiled bundle
internal actual suspend fun loadCapstoneWasmModule(bytes: ByteArray): CapstoneWasmModule {
    // The meta .wasm sits in resources next to the generated machine class — both
    // are emitted by the aotCompileDisassemblerWasm gradle task in the
    // `org.jetbrains.hexana.disassembly.capstone.aot` package.
    val metaStream = CapstoneWasmModule::class.java.getResourceAsStream(META_RESOURCE)
        ?: error("AOT meta wasm not found on classpath at $META_RESOURCE")
    val module = metaStream.use { Parser.parse(it) }
    val instance = Instance.builder(module)
        .withMachineFactory { instance -> DisassemblerWasmMachine(instance) }
        .build()
    return CapstoneWasmModule(instance)
}

private const val META_RESOURCE =
    "/org/jetbrains/hexana/disassembly/capstone/aot/DisassemblerWasm.meta"
