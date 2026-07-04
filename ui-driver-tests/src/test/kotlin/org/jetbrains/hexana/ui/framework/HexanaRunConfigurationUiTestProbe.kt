package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.singleProject
import org.junit.jupiter.api.Assertions.assertEquals

private const val HEXANA_PLUGIN_ID = "org.jetbrains.hexana"

internal fun Driver.runConfigurationProbeResponds() {
    val probe = utility(HexanaRunConfigurationUiTestProbeRef::class)
    assertEquals("pong", probe.ping())
    assertEquals("1", probe.frameworkVersion())
}

internal fun Driver.launchWasmtimeRunConfiguration(
    wasmFileName: String,
    wasmtimePath: String,
    wasmtimeOptions: String = "",
    exportName: String = "",
    programArgs: String = "",
): HexanaRunConfigurationLaunchSnapshot {
    val probe = utility(HexanaRunConfigurationUiTestProbeRef::class)
    return HexanaRunConfigurationLaunchPayload.parse(
        probe.launchWasmtimeRunConfiguration(
            singleProject(),
            wasmFileName,
            wasmtimePath,
            wasmtimeOptions,
            exportName,
            programArgs,
        ),
    )
}

internal fun Driver.launchWasmtimeDebugRunConfiguration(
    wasmFileName: String,
    wasmtimePath: String,
    wasmtimeOptions: String = "",
    exportName: String = "",
    programArgs: String = "",
): HexanaRunConfigurationLaunchSnapshot {
    val probe = utility(HexanaRunConfigurationUiTestProbeRef::class)
    return HexanaRunConfigurationLaunchPayload.parse(
        probe.launchWasmtimeDebugRunConfiguration(
            singleProject(),
            wasmFileName,
            wasmtimePath,
            wasmtimeOptions,
            exportName,
            programArgs,
        ),
    )
}

internal fun Driver.launchNodeJsRunConfiguration(
    wasmFileName: String,
    nodePath: String,
    nodeOptions: String? = null,
    exportName: String = "",
    programArgs: String = "",
): HexanaRunConfigurationLaunchSnapshot {
    val probe = utility(HexanaRunConfigurationUiTestProbeRef::class)
    return HexanaRunConfigurationLaunchPayload.parse(
        probe.launchNodeJsRunConfiguration(
            singleProject(),
            wasmFileName,
            nodePath,
            nodeOptions,
            exportName,
            programArgs,
        ),
    )
}

internal fun Driver.launchNodeJsDebugRunConfiguration(
    wasmFileName: String,
    nodePath: String,
    nodeOptions: String? = null,
    exportName: String = "",
    programArgs: String = "",
): HexanaRunConfigurationLaunchSnapshot {
    val probe = utility(HexanaRunConfigurationUiTestProbeRef::class)
    return HexanaRunConfigurationLaunchPayload.parse(
        probe.launchNodeJsDebugRunConfiguration(
            singleProject(),
            wasmFileName,
            nodePath,
            nodeOptions,
            exportName,
            programArgs,
        ),
    )
}

internal fun Driver.clearNodeJsRunConfigurations(
    wasmFileName: String,
    exportName: String,
): Int {
    val probe = utility(HexanaRunConfigurationUiTestProbeRef::class)
    return probe.clearNodeJsRunConfigurations(
        singleProject(),
        wasmFileName,
        exportName,
    )
}

internal fun Driver.debugWasmtimeWithBreakpointAndInspectVariables(
    wasmFileName: String,
    sourceFileName: String,
    breakpointLine: Int,
    wasmtimePath: String,
    wasmtimeOptions: String = "",
    exportName: String = "",
    programArgs: String = "",
): String {
    val helper = utility(DebugTestHelperRef::class)
    return helper.debugWithBreakpointAndInspectVariables(
        singleProject(),
        wasmFileName,
        sourceFileName,
        breakpointLine,
        "WASMTIME",
        wasmtimeOptions,
        exportName,
        programArgs,
        wasmtimePath,
    )
}

internal fun Driver.debugNodeJsWithBreakpointAndInspectVariables(
    wasmFileName: String,
    sourceFileName: String,
    breakpointLine: Int,
    nodeOptions: String?,
    exportName: String,
    programArgs: String,
): String {
    val helper = utility(NodeJsDebugTestHelperRef::class)
    return helper.debugNodeJsWithBreakpointAndInspectVariables(
        singleProject(),
        wasmFileName,
        sourceFileName,
        breakpointLine,
        "NODE_JS",
        nodeOptions.orEmpty(),
        exportName,
        programArgs,
        HexanaRunConfigurationRuntimePreflight.ensureNodeJs(),
    )
}

@Remote(
    "org.jetbrains.hexana.uitestframework.HexanaRunConfigurationUiTestProbe",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface HexanaRunConfigurationUiTestProbeRef {
    fun frameworkVersion(): String
    fun ping(): String
    fun launchWasmtimeRunConfiguration(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        wasmtimePath: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
    ): List<String>
    fun launchWasmtimeDebugRunConfiguration(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        wasmtimePath: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
    ): List<String>
    fun launchNodeJsRunConfiguration(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        nodePath: String,
        nodeOptions: String?,
        exportName: String,
        programArgs: String,
    ): List<String>
    fun launchNodeJsDebugRunConfiguration(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        nodePath: String,
        nodeOptions: String?,
        exportName: String,
        programArgs: String,
    ): List<String>
    fun clearNodeJsRunConfigurations(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        exportName: String,
    ): Int
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
