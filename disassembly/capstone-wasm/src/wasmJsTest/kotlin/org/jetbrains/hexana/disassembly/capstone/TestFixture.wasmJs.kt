package org.jetbrains.hexana.disassembly.capstone

internal actual suspend fun loadTestDisassembler(): CapstoneWasmDisassembler =
    CapstoneWasmDisassembler.load(DISASSEMBLER_WASM_BYTES)
