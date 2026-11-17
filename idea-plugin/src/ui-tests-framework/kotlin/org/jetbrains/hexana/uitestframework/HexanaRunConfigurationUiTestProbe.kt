package org.jetbrains.hexana.uitestframework

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.run.WasmRunConfiguration
import org.jetbrains.hexana.run.WasmRunMode
import org.jetbrains.hexana.run.WasmRuntimeSettings
import org.jetbrains.hexana.run.runWasmConfiguration
import org.jetbrains.hexana.wasisdk.WasiSdkStorage
import org.jetbrains.hexana.shared.WasiSdkReleases
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val WIRE_VERSION = "1"
private const val LAUNCH_SCHEMA = "hexana-run-configuration-launch"

object HexanaRunConfigurationUiTestProbe {
    @JvmStatic
    fun frameworkVersion(): String = "1"

    @JvmStatic
    fun ping(): String = "pong"

    @JvmStatic
    fun seedWasiSdkForWhatsNewCapture(version: Int): List<String> {
        val storage = WasiSdkStorage.getInstance()
        val root = storage.root.toAbsolutePath().normalize()
        Files.createDirectories(root)
        Files.list(root).use { entries ->
            require(entries.findAny().isEmpty) {
                "Refusing to seed non-empty wasi-sdk capture root: $root"
            }
        }
        Files.writeString(root.resolve(WASI_SDK_CAPTURE_MARKER), version.toString())
        val lldb = storage.installDir(version).resolve(WasiSdkReleases.lldbRelativePath(storage.currentOs()))
        Files.createDirectories(lldb.parent)
        Files.writeString(lldb, "#!/bin/sh\n")
        lldb.toFile().setExecutable(true)
        storage.recordInstalled(version)
        WasmRuntimeSettings.getInstance().lldbPathInUi = ""
        return wasiSdkCaptureState(storage, version)
    }

    @JvmStatic
    fun cleanupWasiSdkForWhatsNewCapture(version: Int) {
        val storage = WasiSdkStorage.getInstance()
        val root = storage.root.toAbsolutePath().normalize()
        val marker = root.resolve(WASI_SDK_CAPTURE_MARKER)
        require(Files.readString(marker) == version.toString()) {
            "Refusing to clean wasi-sdk root without the expected capture marker: $root"
        }
        root.toFile().deleteRecursively()
    }

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
    fun launchWasmtimeDebugRunConfiguration(
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
            mode = WasmRunMode.DEBUG,
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

    @JvmStatic
    fun launchNodeJsDebugRunConfiguration(
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
            mode = WasmRunMode.DEBUG,
        )
    }

    @JvmStatic
    fun clearNodeJsRunConfigurations(
        project: Project,
        wasmFileName: String,
        exportName: String,
    ): Int {
        val basePath = project.basePath ?: error("project basePath is null")
        val wasmFilePath = "$basePath/$wasmFileName"
        return clearRunConfigurations(
            project = project,
            wasmFilePath = wasmFilePath,
            engine = WasmEngine.NODE_JS,
            exportName = exportName,
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
        mode: WasmRunMode = WasmRunMode.RUN,
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

        val debugSessionObserver = if (mode == WasmRunMode.DEBUG) {
            DebugSessionObserver(project, wasmVirtualFile.path, engine)
        } else {
            null
        }

        val launchResult = try {
            val settings = runWasmConfiguration(
                project = project,
                file = wasmVirtualFile,
                exportName = exportName,
                programArgs = programArgs,
                wasmTimeArgs = if (engine == WasmEngine.WASMTIME) wasmtimeOptions else "",
                engine = engine,
                mode = mode,
                nodeOptions = if (engine == WasmEngine.NODE_JS) nodeOptions else null,
            )
            val debugSessionObserved = if (debugSessionObserver != null) {
                debugSessionObserver.await()
                true
            } else {
                false
            }
            LaunchResult(settings, debugSessionObserved)
        } finally {
            debugSessionObserver?.disconnect()
        }

        waitForLaunchedConfiguration(
            project = project,
            expectedSettings = launchResult.settings,
        )
        val configuration = launchResult.settings.configuration as? WasmRunConfiguration
            ?: error("Expected WasmRunConfiguration, got ${launchResult.settings.configuration::class.java.name}")

        return payload(
            buildList {
                add("wasmFilePath=${configuration.wasmFilePath}")
                add("workingDirectory=${configuration.workingDirectory}")
                add("engine=${configuration.engine.name}")
                add("mode=${mode.name}")
                add("configurationName=${launchResult.settings.name}")
                add("selectedConfigurationName=${currentSelectedConfigurationName(project)}")
                add("selectedConfigurationType=${configuration::class.java.name}")
                add("debugSessionObserved=${launchResult.debugSessionObserved}")
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

    private fun clearRunConfigurations(
        project: Project,
        wasmFilePath: String,
        engine: WasmEngine,
        exportName: String,
    ): Int =
        onEdt {
            val runManager = RunManager.getInstance(project)
            val settingsToRemove = runManager.allSettings.filter { settings ->
                val configuration = settings.configuration as? WasmRunConfiguration
                configuration != null &&
                        configuration.wasmFilePath == wasmFilePath &&
                        configuration.engine == engine &&
                        configuration.exportName == exportName
            }
            settingsToRemove.forEach(runManager::removeConfiguration)
            settingsToRemove.size
        }

    private data class LaunchResult(
        val settings: RunnerAndConfigurationSettings,
        val debugSessionObserved: Boolean,
    )

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

    private fun wasiSdkCaptureState(storage: WasiSdkStorage, version: Int): List<String> = listOf(
        "schema=hexana-wasi-sdk-capture-state",
        "version=1",
        "sdkVersion=$version",
        "installedVersions=${storage.installedVersions().joinToString(",")}",
        "activeVersion=${storage.activeVersion()?.toString().orEmpty()}",
        "lldbPathSetting=${WasmRuntimeSettings.getInstance().lldbPathInUi.orEmpty()}",
        "storageRoot=${storage.root.toAbsolutePath().normalize()}",
    )

    private class DebugSessionObserver(
        private val project: Project,
        private val wasmFilePath: String,
        private val engine: WasmEngine,
    ) {
        private val observed = CountDownLatch(1)
        private val lastState = AtomicReference("debug process not started")
        private val connection: MessageBusConnection = project.messageBus.connect()

        init {
            connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    val state = onEdt {
                        describeDebugProcessStart(project, debugProcess, wasmFilePath, engine)
                    }
                    lastState.set(state.message)
                    if (state.matchesExpectedLaunch) {
                        observed.countDown()
                    }
                }
            })
        }

        fun await() {
            if (observed.await(30, TimeUnit.SECONDS)) {
                return
            }

            error("Timed out waiting for debug session for $wasmFilePath; last state: ${lastState.get()}")
        }

        fun disconnect() {
            connection.disconnect()
        }
    }

    private const val WASI_SDK_CAPTURE_MARKER = ".hexana-whats-new-018-capture"

    private data class DebugProcessStartState(
        val matchesExpectedLaunch: Boolean,
        val message: String,
    )

    private fun describeDebugProcessStart(
        project: Project,
        debugProcess: XDebugProcess,
        wasmFilePath: String,
        engine: WasmEngine,
    ): DebugProcessStartState {
        val runProfile = debugProcess.session.runProfile
        val debugConfiguration = runProfile as? WasmRunConfiguration
        val selected = RunManager.getInstance(project).selectedConfiguration
        val selectedConfiguration = selected?.configuration as? WasmRunConfiguration

        val message = when {
            debugConfiguration == null -> {
                "debug session runProfile is ${runProfile?.javaClass?.name ?: "null"}"
            }
            debugConfiguration.wasmFilePath != wasmFilePath -> {
                "debug session path=${debugConfiguration.wasmFilePath}"
            }
            debugConfiguration.engine != engine -> {
                "debug session engine=${debugConfiguration.engine.name}"
            }
            selected == null -> "no selected configuration at debug start"
            selectedConfiguration == null -> {
                "selected configuration at debug start is ${selected.configuration::class.java.name}"
            }
            selectedConfiguration.wasmFilePath != wasmFilePath -> {
                "selected configuration path at debug start=${selectedConfiguration.wasmFilePath}"
            }
            selectedConfiguration.engine != engine -> {
                "selected configuration engine at debug start=${selectedConfiguration.engine.name}"
            }
            else -> {
                "observed debug session for path=$wasmFilePath engine=${engine.name}"
            }
        }

        val matchesExpectedLaunch = debugConfiguration != null &&
                debugConfiguration.wasmFilePath == wasmFilePath &&
                debugConfiguration.engine == engine &&
                selectedConfiguration != null &&
                selectedConfiguration.wasmFilePath == wasmFilePath &&
                selectedConfiguration.engine == engine

        return DebugProcessStartState(matchesExpectedLaunch, message)
    }
}
