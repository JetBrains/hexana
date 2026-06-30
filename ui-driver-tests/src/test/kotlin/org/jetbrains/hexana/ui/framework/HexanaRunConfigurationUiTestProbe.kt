package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import org.junit.jupiter.api.Assertions.assertEquals

private const val HEXANA_PLUGIN_ID = "org.jetbrains.hexana"

internal fun Driver.runConfigurationProbeResponds() {
    val probe = utility(HexanaRunConfigurationUiTestProbeRef::class)
    assertEquals("pong", probe.ping())
    assertEquals("1", probe.frameworkVersion())
}

@Remote(
    "org.jetbrains.hexana.uitestframework.HexanaRunConfigurationUiTestProbe",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface HexanaRunConfigurationUiTestProbeRef {
    fun frameworkVersion(): String
    fun ping(): String
}

@Remote(
    "org.jetbrains.hexana.run.WasmRunConfigTestHelper",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface RunConfigHelperRef {
    fun createAndRunWasmtime(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        engine: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
        wasmtimePath: String,
    )

    fun createAndDebugWasmtime(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        engine: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
        wasmtimePath: String,
    )
}

@Remote(
    "org.jetbrains.hexana.run.WasmRunConfigTestHelper",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface NodeJsRunConfigHelperRef {
    fun createAndRunNodeJs(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        engine: String,
        nodeOptions: String,
        exportName: String,
        programArgs: String,
        nodePath: String,
    )

    fun createAndDebugNodeJs(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        engine: String,
        nodeOptions: String,
        exportName: String,
        programArgs: String,
        nodePath: String,
    )
}

@Remote(
    "org.jetbrains.hexana.run.WasmRunConfigTestHelper",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface NodeJsDebugTestHelperRef {
    fun debugNodeJsWithBreakpointAndInspectVariables(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        engine: String,
        nodeOptions: String,
        exportName: String,
        programArgs: String,
        nodePath: String,
    ): String
}

@Remote(
    "org.jetbrains.hexana.run.WasmRunConfigTestHelper",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface DebugTestHelperRef {
    fun debugWithBreakpointAndInspectVariables(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        engine: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
        wasmtimePath: String,
    ): String
}
