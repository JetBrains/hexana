package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase

class WasmDebugRunnerCommandLineTest : UsefulTestCase() {

    fun testLinuxDisablesExternalSymbolLookupBeforeTargetCreation() {
        val parameters = WasmDebugRunner.buildWasmtimeLldbParameters(
            wasmtimePath = "/opt/wasmtime/bin/wasmtime",
            wasmtimeArguments = listOf("run", "module.wasm"),
            disableExternalSymbolLookup = true,
        )

        assertEquals(
            listOf(
                "-O",
                "settings set symbols.enable-external-lookup false",
                "--",
                "/opt/wasmtime/bin/wasmtime",
                "run",
                "module.wasm",
            ),
            parameters,
        )
    }

    fun testOtherPlatformsKeepTargetArgumentsUnchanged() {
        val parameters = WasmDebugRunner.buildWasmtimeLldbParameters(
            wasmtimePath = "/opt/wasmtime/bin/wasmtime",
            wasmtimeArguments = listOf("run", "module.wasm"),
            disableExternalSymbolLookup = false,
        )

        assertEquals(
            listOf("--", "/opt/wasmtime/bin/wasmtime", "run", "module.wasm"),
            parameters,
        )
    }
}
