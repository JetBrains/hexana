package org.jetbrains.hexana.disassembly.capstone

/**
 * JVM convenience: builds a [CapstoneWasmDisassembler] by reading
 * `disassembler.wasm` from the classpath. The file lives in
 * `commonMain/resources/` so it's shared across all KMP targets; KMP's
 * resource pipeline propagates it into the JVM jar where
 * `Class.getResource` finds it.
 *
 * Equivalent to `CapstoneWasmDisassembler.load(bytes)` after reading the
 * resource. Throws if the resource is missing.
 */
suspend fun CapstoneWasmDisassembler.Companion.loadFromClasspath(): CapstoneWasmDisassembler {
    val stream = CapstoneWasmDisassembler::class.java.getResourceAsStream("/disassembler.wasm")
        ?: error("disassembler.wasm not found on classpath at /disassembler.wasm")
    val bytes = stream.use { it.readBytes() }
    return load(bytes)
}
