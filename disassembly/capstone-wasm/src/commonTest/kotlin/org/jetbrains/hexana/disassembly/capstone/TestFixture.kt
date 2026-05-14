package org.jetbrains.hexana.disassembly.capstone

/**
 * Loads the disassembler.wasm test fixture for the current platform.
 *
 * JVM reads from the classpath. Other platforms must wire their own bytes
 * source before they can run the common disassembly tests.
 */
internal expect suspend fun loadTestDisassembler(): CapstoneWasmDisassembler
