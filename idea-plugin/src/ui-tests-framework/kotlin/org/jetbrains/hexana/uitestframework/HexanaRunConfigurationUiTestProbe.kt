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
            runtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
    }

    @JvmStatic
    fun launchNodeJsRunConfiguration(
        project: Project,
        wasmFileName: String,
        nodePath: String,
        nodeOptions: String,
        exportName: String,
        programArgs: String,
    ): List<String> {
        return launchRunConfiguration(
            project = project,
            wasmFileName = wasmFileName,
            engine = WasmEngine.NODE_JS,
            runtimePath = nodePath,
            runtimeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
    }

    private fun launchRunConfiguration(
        project: Project,
        wasmFileName: String,
        engine: WasmEngine,
        runtimePath: String,
        runtimeOptions: String,
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
        runWasmConfiguration(
            project = project,
            file = wasmVirtualFile,
            exportName = exportName,
            programArgs = programArgs,
            wasmTimeArgs = if (engine == WasmEngine.WASMTIME) runtimeOptions else "",
            engine = engine,
            mode = WasmRunMode.RUN,
            nodeOptions = if (engine == WasmEngine.NODE_JS) runtimeOptions else "",
        )

        val settings = waitForLaunchedConfiguration(
            project = project,
            wasmFilePath = wasmVirtualFile.path,
            engine = engine,
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
        wasmFilePath: String,
        engine: WasmEngine,
    ): RunnerAndConfigurationSettings {
        val deadlineMs = System.currentTimeMillis() + 30_000
        var lastState = "launch not observed"
        while (System.currentTimeMillis() < deadlineMs) {
            val selected = onEdt { RunManager.getInstance(project).selectedConfiguration }
            val configuration = selected?.configuration as? WasmRunConfiguration
            lastState = when {
                selected == null -> "no selected configuration"
                configuration == null -> {
                    "selected configuration is ${selected.configuration::class.java.name}"
                }
                configuration.wasmFilePath != wasmFilePath -> {
                    "selected configuration path=${configuration.wasmFilePath}"
                }
                configuration.engine != engine -> {
                    "selected configuration engine=${configuration.engine.name}"
                }
                else -> return selected
            }
            Thread.sleep(100)
        }

        error("Timed out waiting for launch of $wasmFilePath; last state: $lastState")
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
