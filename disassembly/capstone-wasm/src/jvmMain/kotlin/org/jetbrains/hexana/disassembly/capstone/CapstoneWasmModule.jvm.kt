package org.jetbrains.hexana.disassembly.capstone

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser

/**
 * JVM actual for [CapstoneWasmModule] — wraps a Chicory-loaded `disassembler.wasm`
 * instance. Resource path: `/disassembler.wasm` on the classpath, vendored by the
 * `wasm-modules/disassembler` Rust crate.
 *
 * Not thread-safe: a Chicory [Instance] owns a single linear memory and a single
 * call stack. Callers that need parallel decoding must create separate
 * [CapstoneWasmModule] instances.
 */
internal actual class CapstoneWasmModule internal constructor(private val instance: Instance) {
    private val exports = HashMap<String, ExportFunction>()
    private val memory: Memory = instance.memory()

    actual fun call(export: String, args: LongArray): LongArray {
        val fn = exports.getOrPut(export) { instance.export(export) }
        // Chicory returns null for void WASM functions; treat as empty result array.
        return fn.apply(*args) ?: EMPTY
    }

    private companion object {
        private val EMPTY = LongArray(0)
    }

    actual fun readMemory(addr: Int, length: Int): ByteArray =
        memory.readBytes(addr, length)

    actual fun writeMemory(addr: Int, bytes: ByteArray) {
        memory.write(addr, bytes)
    }

    actual fun close() {
        exports.clear()
    }
}

internal actual fun loadCapstoneWasmModule(): CapstoneWasmModule {
    val resourceStream = CapstoneWasmModule::class.java.getResourceAsStream("/disassembler.wasm")
        ?: error("disassembler.wasm not found on classpath at /disassembler.wasm")
    val bytes = resourceStream.use { it.readBytes() }
    val module = Parser.parse(bytes)
    val instance = Instance.builder(module).build()
    return CapstoneWasmModule(instance)
}
