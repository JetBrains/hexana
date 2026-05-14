package org.jetbrains.hexana.disassembly.capstone

/**
 * JVM actual for [CapstoneWasmModule]: will wrap a Chicory-loaded `capstone.wasm`
 * instance. Skeleton — Chicory dependency and instance plumbing are not wired
 * yet.
 */
internal actual class CapstoneWasmModule {
    actual fun call(export: String, args: LongArray): LongArray =
        TODO("Chicory-backed capstone-wasm call not yet implemented")

    actual fun readMemory(addr: Int, length: Int): ByteArray =
        TODO("Chicory-backed memory read not yet implemented")

    actual fun writeMemory(addr: Int, bytes: ByteArray): Unit =
        TODO("Chicory-backed memory write not yet implemented")

    actual fun close(): Unit =
        TODO("Chicory instance disposal not yet implemented")
}

internal actual fun loadCapstoneWasmModule(): CapstoneWasmModule =
    TODO("Chicory load of capstone.wasm not yet implemented")
