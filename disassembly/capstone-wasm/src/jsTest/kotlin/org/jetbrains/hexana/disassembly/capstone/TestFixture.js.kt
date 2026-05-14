package org.jetbrains.hexana.disassembly.capstone

internal actual suspend fun loadTestDisassembler(): CapstoneWasmDisassembler =
    TODO(
        "js test fixture not wired yet: the disassembler.wasm artifact lives in " +
            "jvmMain/resources and is only readable from the JVM classpath. To run " +
            "the common tests on Kotlin/JS, copy disassembler.wasm into the JS test " +
            "output and read it via fs.readFileSync (node) or fetch (browser-karma).",
    )
