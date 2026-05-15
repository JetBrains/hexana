package org.jetbrains.hexana.disassembly.capstone

internal actual suspend fun loadTestDisassembler(): CapstoneWasmDisassembler =
    TODO(
        "js test fixture not wired yet. disassembler.wasm lives in " +
            "commonMain/resources but Kotlin/JS doesn't auto-expose it at runtime — " +
            "the fixture must read the file via fs.readFileSync (node) or fetch (browser " +
            "via a Karma served-files config) and call CapstoneWasmDisassembler.load(bytes).",
    )
