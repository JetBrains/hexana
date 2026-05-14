package org.jetbrains.hexana.disassembly.capstone

/**
 * wasmJs actual for [CapstoneWasmModule]: drives `capstone-wasm` through JS
 * interop from a wasmJs Kotlin target. Skeleton — wasmJs interop and module
 * loading are not wired yet.
 */
internal actual class CapstoneWasmModule {
    actual fun call(export: String, args: LongArray): LongArray =
        TODO("wasmJs capstone-wasm call not yet implemented")

    actual fun readMemory(addr: Int, length: Int): ByteArray =
        TODO("wasmJs capstone-wasm memory read not yet implemented")

    actual fun writeMemory(addr: Int, bytes: ByteArray): Unit =
        TODO("wasmJs capstone-wasm memory write not yet implemented")

    actual fun close(): Unit =
        TODO("wasmJs capstone-wasm instance disposal not yet implemented")
}

internal actual fun loadCapstoneWasmModule(): CapstoneWasmModule =
    TODO("wasmJs interop with capstone-wasm not yet implemented")
