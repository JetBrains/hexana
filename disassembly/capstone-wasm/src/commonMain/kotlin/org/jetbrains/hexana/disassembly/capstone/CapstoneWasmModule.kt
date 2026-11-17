package org.jetbrains.hexana.disassembly.capstone

/**
 * Platform-specific binding to a loaded Capstone WASM instance.
 *
 * Each platform provides an actual:
 *  - `jvmMain` — Chicory wrapper around the bundled `disassembler.wasm` blob.
 *  - `jsMain`  — direct `WebAssembly.instantiate(...)` via Kotlin/JS dynamic.
 *  - `wasmJsMain` — same browser-side API, accessed from a wasmJs target.
 *
 * The surface is intentionally minimal: a generic "call an export" entry point
 * plus linear-memory I/O. Capstone-specific function binding (`alloc`,
 * `decode_*`, `free_result`, …) lives in [commonMain] on top of this.
 *
 * Not thread-safe: a single instance owns a single linear memory and call
 * stack. Concurrent decoding requires separate [CapstoneWasmModule] instances.
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

/**
 * Instantiates a [CapstoneWasmModule] from the raw `disassembler.wasm` bytes.
 *
 * The bytes themselves are platform-loaded — JVM reads them from the classpath
 * (see `CapstoneWasmDisassembler.loadFromClasspath`); browser callers fetch
 * them and pass the result in. The instantiation step is async on every
 * platform that targets WebAssembly through the host JS API (browser / Node)
 * because `WebAssembly.instantiate` only exists as a Promise-returning entry;
 * the JVM Chicory path is synchronous internally but presents the same
 * suspendable signature so the API is uniform.
 */
internal expect suspend fun loadCapstoneWasmModule(bytes: ByteArray): CapstoneWasmModule
