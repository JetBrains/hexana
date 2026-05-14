package org.jetbrains.hexana.disassembly.capstone

internal actual suspend fun loadTestDisassembler(): CapstoneWasmDisassembler =
    TODO(
        "wasmJs test fixture not wired yet: the disassembler.wasm artifact lives " +
            "in jvmMain/resources. To run the common tests on Kotlin/Wasm, copy " +
            "disassembler.wasm into the wasmJs test output and provide bytes via a " +
            "@JsFun bridge that fetches it from the test harness.",
    )
