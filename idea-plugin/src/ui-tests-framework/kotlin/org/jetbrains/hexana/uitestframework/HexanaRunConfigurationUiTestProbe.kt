package org.jetbrains.hexana.uitestframework

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.run.WasmRunConfiguration
import org.jetbrains.hexana.run.WasmRunMode
import org.jetbrains.hexana.run.WasmRuntimeSettings
import org.jetbrains.hexana.run.runWasmConfiguration

private const val WIRE_VERSION = "1"
private const val LAUNCH_SCHEMA = "hexana-run-configuration-launch"

object HexanaRunConfigurationUiTestProbe {
    @JvmStatic
    fun frameworkVersion(): String = "1"

    @JvmStatic
    fun ping(): String = "pong"

    @JvmStatic
    fun launchWasmtimeRunConfiguration(
        project: Project,
        wasmFileName: String,
        wasmtimePath: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
    ): List<String> {
        return launchRunConfiguration(
            project = project,
            wasmFileName = wasmFileName,
            engine = WasmEngine.WASMTIME,
            runtimePath = wasmtimePath,
            wasmtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
    }

    @JvmStatic
    fun launchNodeJsRunConfiguration(
        project: Project,
        wasmFileName: String,
        nodePath: String,
        nodeOptions: String? = null,
        exportName: String,
        programArgs: String,
    ): List<String> {
        return launchRunConfiguration(
            project = project,
            wasmFileName = wasmFileName,
            engine = WasmEngine.NODE_JS,
            runtimePath = nodePath,
            nodeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
    }

    private fun launchRunConfiguration(
        project: Project,
        wasmFileName: String,
        engine: WasmEngine,
        runtimePath: String,
        wasmtimeOptions: String = "",
        nodeOptions: String? = null,
        exportName: String,
        programArgs: String,
    ): List<String> {
        val basePath = project.basePath ?: error("project basePath is null")
        val wasmFilePath = "$basePath/$wasmFileName"
        val wasmVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(wasmFilePath)
            ?: error("wasm file not found: $wasmFilePath")

        val runtimeSettings = WasmRuntimeSettings.getInstance()
        when (engine) {
            WasmEngine.WASMTIME -> runtimeSettings.wasmtimePathInUi = runtimePath
            WasmEngine.NODE_JS -> runtimeSettings.nodePathInUi = runtimePath
            else -> error("Unsupported engine for run probe: ${engine.name}")
        }
        val settings = runWasmConfiguration(
            project = project,
            file = wasmVirtualFile,
            exportName = exportName,
            programArgs = programArgs,
            wasmTimeArgs = if (engine == WasmEngine.WASMTIME) wasmtimeOptions else "",
            engine = engine,
            mode = WasmRunMode.RUN,
            nodeOptions = if (engine == WasmEngine.NODE_JS) nodeOptions else null,
        )

        waitForLaunchedConfiguration(
            project = project,
            expectedSettings = settings,
        )
        val configuration = settings.configuration as? WasmRunConfiguration
            ?: error("Expected WasmRunConfiguration, got ${settings.configuration::class.java.name}")

        return payload(
            buildList {
                add("wasmFilePath=${configuration.wasmFilePath}")
                add("workingDirectory=${configuration.workingDirectory}")
                add("engine=${configuration.engine.name}")
                add("mode=${WasmRunMode.RUN.name}")
                add("configurationName=${settings.name}")
                add("selectedConfigurationName=${currentSelectedConfigurationName(project)}")
                add("selectedConfigurationType=${configuration::class.java.name}")
                add("effectiveWasmtimePath=${runtimeSettings.effectiveWasmtimePath()}")
                add("effectiveNodePath=${runtimeSettings.effectiveNodePath()}")
                add("wasmtimeOptions=${configuration.wasmtimeOptions}")
                add("nodeOptions=${configuration.nodeOptions}")
                add("exportName=${configuration.exportName}")
                add("programArguments=${configuration.programArguments}")
                add("dependenciesCount=${configuration.dependencies.size}")
                configuration.dependencies.forEach { dependency ->
                    add("dependency=$dependency")
                }
            },
        )
    }

    private fun waitForLaunchedConfiguration(
        project: Project,
        expectedSettings: RunnerAndConfigurationSettings,
    ) {
        val deadlineMs = System.currentTimeMillis() + 30_000
        var lastState = "launch not observed"
        while (System.currentTimeMillis() < deadlineMs) {
            val selected = onEdt { RunManager.getInstance(project).selectedConfiguration }
            lastState = when {
                selected == null -> "no selected configuration"
                selected !== expectedSettings -> {
                    "selected configuration is ${selected.name}, expected ${expectedSettings.name}"
                }
                else -> return
            }
            Thread.sleep(100)
        }

        error("Timed out waiting for launch of ${expectedSettings.name}; last state: $lastState")
    }

    private fun currentSelectedConfigurationName(project: Project): String =
        onEdt { RunManager.getInstance(project).selectedConfiguration?.name.orEmpty() }

    private fun <T> onEdt(action: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return action()
        }

        var result: Result<T>? = null
        app.invokeAndWait {
            result = runCatching(action)
        }
        return result!!.getOrThrow()
    }

    private fun payload(entries: List<String>): List<String> =
        buildList {
            add("schema=$LAUNCH_SCHEMA")
            add("version=$WIRE_VERSION")
            addAll(entries)
        }
}
