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

internal fun Driver.seedWasiSdkForWhatsNewCapture(version: Int): List<String> =
    utility(HexanaRunConfigurationUiTestProbeRef::class).seedWasiSdkForWhatsNewCapture(version)

internal fun Driver.cleanupWasiSdkForWhatsNewCapture(version: Int) {
    utility(HexanaRunConfigurationUiTestProbeRef::class).cleanupWasiSdkForWhatsNewCapture(version)
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

internal fun Driver.startNodeJsDebugWithBreakpointForCapture(
    wasmFileName: String,
    sourceFileName: String,
    breakpointLine: Int,
    nodeOptions: String = "",
    exportName: String = "",
    programArgs: String = "",
) {
    utility(DebugTestHelperRef::class).startNodeJsDebugWithBreakpointForCapture(
        singleProject(),
        wasmFileName,
        sourceFileName,
        breakpointLine,
        nodeOptions,
        exportName,
        programArgs,
        HexanaRunConfigurationRuntimePreflight.ensureNodeJs(),
    )
}

internal fun Driver.startWasmtimeDebugWithBreakpointForCapture(
    wasmFileName: String,
    sourceFileName: String,
    breakpointLine: Int,
    wasmtimeOptions: String,
    exportName: String,
    programArgs: String,
    wasmtimePath: String,
    lldbPath: String,
) {
    utility(DebugTestHelperRef::class).startWasmtimeDebugWithBreakpointForCapture(
        singleProject(),
        wasmFileName,
        sourceFileName,
        breakpointLine,
        wasmtimeOptions,
        exportName,
        programArgs,
        wasmtimePath,
        lldbPath,
    )
}

internal fun Driver.startWasmtimeComponentDebugPausedForCapture(
    wasmFileName: String,
    moduleId: Int,
    moduleRelativeOffset: Int,
    wasmtimePath: String,
    lldbPath: String,
) {
    utility(DebugTestHelperRef::class).startWasmtimeComponentDebugPausedForCapture(
        singleProject(),
        wasmFileName,
        moduleId,
        moduleRelativeOffset,
        wasmtimePath,
        lldbPath,
    )
}

internal fun Driver.wasmtimeDebuggerCaptureState(): HexanaWasmtimeDebuggerCaptureState =
    HexanaProbePayload.wasmtimeDebuggerCaptureState(
        utility(DebugTestHelperRef::class).wasmtimeDebuggerCaptureState(singleProject()),
    )

internal fun Driver.terminateDebuggerForCapture() {
    utility(DebugTestHelperRef::class).terminateDebuggerForCapture(singleProject())
}

internal fun Driver.cdpDebuggerCaptureState(): HexanaCdpDebuggerCaptureState =
    HexanaProbePayload.cdpDebuggerCaptureState(
        utility(DebugTestHelperRef::class).cdpDebuggerCaptureState(singleProject()),
    )

internal fun Driver.resumeDebuggerForCapture() {
    utility(DebugTestHelperRef::class).resumeDebuggerForCapture(singleProject())
}

internal fun Driver.startWasmDebugPausedAtBreakpoint(
    wasmFileName: String,
    sourceFileName: String,
    breakpointLine: Int,
    engine: String,
    runtimeOptions: String,
    exportName: String,
    programArgs: String,
    runtimePath: String,
) {
    utility(DebugTestHelperRef::class).startWasmDebugPausedAtBreakpoint(
        singleProject(),
        wasmFileName,
        sourceFileName,
        breakpointLine,
        engine,
        runtimeOptions,
        exportName,
        programArgs,
        runtimePath,
    )
}

internal fun Driver.wasmDebuggerTabState(): HexanaWasmDebuggerTabState =
    HexanaProbePayload.wasmDebuggerTabState(
        utility(DebugTestHelperRef::class).wasmDebuggerTabState(singleProject()),
    )

internal fun Driver.stepInWasmDebugSession(kind: String) {
    utility(DebugTestHelperRef::class).stepInWasmDebugSession(singleProject(), kind)
}

internal fun Driver.stopWasmDebugSession() {
    utility(DebugTestHelperRef::class).stopWasmDebugSession(singleProject())
}

@Remote(
    "org.jetbrains.hexana.uitestframework.HexanaRunConfigurationUiTestProbe",
    plugin = HEXANA_PLUGIN_ID,
)
internal interface HexanaRunConfigurationUiTestProbeRef {
    fun frameworkVersion(): String
    fun ping(): String
    fun seedWasiSdkForWhatsNewCapture(version: Int): List<String>
    fun cleanupWasiSdkForWhatsNewCapture(version: Int)
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
    fun startNodeJsDebugWithBreakpointForCapture(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        nodeOptions: String,
        exportName: String,
        programArgs: String,
        nodePath: String,
    )
    fun startWasmtimeDebugWithBreakpointForCapture(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
        wasmtimePath: String,
        lldbPath: String,
    )
    fun startWasmtimeComponentDebugPausedForCapture(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        moduleId: Int,
        moduleRelativeOffset: Int,
        wasmtimePath: String,
        lldbPath: String,
    )
    fun wasmtimeDebuggerCaptureState(project: com.intellij.driver.sdk.Project): List<String>
    fun terminateDebuggerForCapture(project: com.intellij.driver.sdk.Project)
    fun cdpDebuggerCaptureState(project: com.intellij.driver.sdk.Project): List<String>
    fun resumeDebuggerForCapture(project: com.intellij.driver.sdk.Project)
    fun startWasmDebugPausedAtBreakpoint(
        project: com.intellij.driver.sdk.Project,
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        engine: String,
        runtimeOptions: String,
        exportName: String,
        programArgs: String,
        runtimePath: String,
    )
    fun wasmDebuggerTabState(project: com.intellij.driver.sdk.Project): List<String>
    fun stepInWasmDebugSession(project: com.intellij.driver.sdk.Project, kind: String)
    fun stopWasmDebugSession(project: com.intellij.driver.sdk.Project)
}
