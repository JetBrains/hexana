package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase

class WasmDebugRunnerCommandLineTest : UsefulTestCase() {

    fun testLinuxDisablesExternalSymbolLookupBeforeTargetCreation() {
        val parameters = WasmDebugRunner.buildWasmtimeLldbParameters(
            wasmtimePath = "/opt/wasmtime/bin/wasmtime",
            wasmtimeArguments = listOf("run", "module.wasm"),
            disableExternalSymbolLookup = WasmDebugRunner.shouldDisableExternalSymbolLookup(
                isLinux = true,
                isWindows = false,
            ),
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

    fun testWindowsDisablesExternalSymbolLookupBeforeTargetCreation() {
        val parameters = WasmDebugRunner.buildWasmtimeLldbParameters(
            wasmtimePath = "C:/wasmtime/bin/wasmtime.exe",
            wasmtimeArguments = listOf("run", "module.wasm"),
            disableExternalSymbolLookup = WasmDebugRunner.shouldDisableExternalSymbolLookup(
                isLinux = false,
                isWindows = true,
            ),
        )

        assertEquals(
            listOf(
                "-O",
                "settings set symbols.enable-external-lookup false",
                "--",
                "C:/wasmtime/bin/wasmtime.exe",
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
            disableExternalSymbolLookup = WasmDebugRunner.shouldDisableExternalSymbolLookup(
                isLinux = false,
                isWindows = false,
            ),
        )

        assertEquals(
            listOf("--", "/opt/wasmtime/bin/wasmtime", "run", "module.wasm"),
            parameters,
        )
    }
}
