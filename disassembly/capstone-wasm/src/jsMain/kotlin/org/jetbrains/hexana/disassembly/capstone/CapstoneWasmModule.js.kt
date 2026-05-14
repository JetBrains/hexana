package org.jetbrains.hexana.disassembly.capstone

/**
 * JS actual for [CapstoneWasmModule]: will drive `capstone-wasm` directly through
 * the host `WebAssembly` API (browser / Node). Skeleton — JS interop and module
 * loading are not wired yet.
 */
internal actual class CapstoneWasmModule {
    actual fun call(export: String, args: LongArray): LongArray =
        TODO("JS capstone-wasm call not yet implemented")

    actual fun readMemory(addr: Int, length: Int): ByteArray =
        TODO("JS capstone-wasm memory read not yet implemented")

    actual fun writeMemory(addr: Int, bytes: ByteArray): Unit =
        TODO("JS capstone-wasm memory write not yet implemented")

    actual fun close(): Unit =
        TODO("JS capstone-wasm instance disposal not yet implemented")
}

internal actual fun loadCapstoneWasmModule(): CapstoneWasmModule =
    TODO("JS WebAssembly.instantiate(capstone-wasm) not yet implemented")
