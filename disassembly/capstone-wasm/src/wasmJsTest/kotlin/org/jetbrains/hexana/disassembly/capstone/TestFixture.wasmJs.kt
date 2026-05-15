package org.jetbrains.hexana.disassembly.capstone

internal actual suspend fun loadTestDisassembler(): CapstoneWasmDisassembler =
    TODO(
        "wasmJs test fixture not wired yet. disassembler.wasm lives in " +
            "commonMain/resources but the wasmJs runtime doesn't auto-fetch it — the " +
            "fixture must provide bytes via a @JsFun bridge (fetch / Karma served-files) " +
            "and call CapstoneWasmDisassembler.load(bytes).",
    )
