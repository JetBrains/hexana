package org.jetbrains.hexana.disassembly.capstone

/**
 * Platform-specific binding to a loaded Capstone WASM instance.
 *
 * Each platform provides an actual:
 *  - `jvmMain` — Chicory wrapper around the bundled `capstone.wasm` blob.
 *  - `jsMain`  — direct interop with the `capstone-wasm` package via the host
 *                `WebAssembly` API (browser / Node).
 *  - `wasmJsMain` — same `capstone-wasm` blob driven from a wasmJs target via
 *                   JS interop.
 *
 * The surface here is intentionally minimal: a generic "call an export" entry
 * point plus linear-memory I/O. Capstone-specific function binding (`cs_open`,
 * `cs_disasm`, `cs_free`, …) lives in [commonMain] on top of this.
 */
internal expect class CapstoneWasmModule {
    /** Invokes an exported WASM function and returns its results. */
    fun call(export: String, args: LongArray): LongArray

    /** Reads `length` bytes starting at WASM linear-memory address [addr]. */
    fun readMemory(addr: Int, length: Int): ByteArray

    /** Writes [bytes] into WASM linear memory starting at [addr]. */
    fun writeMemory(addr: Int, bytes: ByteArray)

    /** Releases the underlying WASM instance and any allocated handles. */
    fun close()
}

/** Loads the Capstone WASM blob and returns an initialised module instance. */
internal expect fun loadCapstoneWasmModule(): CapstoneWasmModule
