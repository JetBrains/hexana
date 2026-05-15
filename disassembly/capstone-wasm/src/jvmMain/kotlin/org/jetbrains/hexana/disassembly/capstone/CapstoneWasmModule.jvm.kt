package org.jetbrains.hexana.disassembly.capstone

import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser

/**
 * JVM actual for [CapstoneWasmModule] — wraps a Chicory-loaded WASM instance.
 * The instance is built with Chicory's AOT machine: WASM is compiled to JVM
 * bytecode at load time, so every subsequent decode call runs as native JVM
 * code instead of going through the interpreter. The compile pass is paid once
 * per [loadCapstoneWasmModule] call (a fraction of a second for our ~590 KB
 * blob); pool the [CapstoneWasmModule] instance across decodes if you care.
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

internal actual suspend fun loadCapstoneWasmModule(bytes: ByteArray): CapstoneWasmModule {
    val module = Parser.parse(bytes)
    val machineFactory = MachineFactoryCompiler.compile(module)
    val instance = Instance.builder(module)
        .withMachineFactory(machineFactory)
        .build()
    return CapstoneWasmModule(instance)
}
