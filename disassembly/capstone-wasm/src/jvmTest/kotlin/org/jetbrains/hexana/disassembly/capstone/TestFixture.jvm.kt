package org.jetbrains.hexana.disassembly.capstone

/** JVM fixture: load the vendored disassembler.wasm from the classpath. */
internal actual suspend fun loadTestDisassembler(): CapstoneWasmDisassembler =
    CapstoneWasmDisassembler.loadFromClasspath()
