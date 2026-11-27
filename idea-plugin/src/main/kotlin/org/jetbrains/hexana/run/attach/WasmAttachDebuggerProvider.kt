package org.jetbrains.hexana.run.attach

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.UserDataHolder
import com.intellij.util.execution.ParametersListUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import org.jetbrains.hexana.run.CdpDebugProcess
import org.jetbrains.hexana.run.CdpDebugRunner
import org.jetbrains.hexana.run.LldbCommunicator
import org.jetbrains.hexana.run.MostlySilentOSProcessHandler
import org.jetbrains.hexana.run.WamrDebugProcess
import org.jetbrains.hexana.run.WasmDebugRunner
import org.jetbrains.hexana.run.WasmDwarfVariableResolver
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.run.WasmRuntimeSettings
import org.jetbrains.hexana.run.WasmtimeGdbDebugProcess
import org.jetbrains.hexana.run.startAttachedDebugSession
import org.jetbrains.hexana.shared.CdpSourceLocationResolver
import java.io.File

/**
 * Contributes Hexana's Wasm debuggers to **Run | Attach to Process…** for
 * local processes that already serve a debug endpoint:
 *
 * - `wasmtime run -g <host>:<port>` and `iwasm -g=<host>:<port>`: LLDB
 *   connects to the runtime's gdbstub, as in a launched session.
 * - `node --inspect[-brk|-wait]` and Chromium browsers started with
 *   `--remote-debugging-port`: Hexana's CDP debugger connects to the
 *   inspector.
 *
 * Ending an attached session detaches; the process keeps running.
 */
class WasmAttachDebuggerProvider : XAttachDebuggerProvider {

    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost

    override fun getAvailableDebuggers(
        project: Project,
        attachHost: XAttachHost,
        processInfo: ProcessInfo,
        contextHolder: UserDataHolder,
    ): List<XAttachDebugger> {
        val target = detectWasmAttachTarget(processInfo) ?: return emptyList()
        if (target is WasmAttachTarget.GdbStub && isGdbStubInUse(project, target)) return emptyList()
        return listOf(WasmAttachDebugger(target))
    }

    /** A gdbstub serves one debugger; hide endpoints a running session already holds. */
    private fun isGdbStubInUse(project: Project, target: WasmAttachTarget.GdbStub): Boolean {
        val endpoint = "${target.host}:${target.port}"
        return XDebuggerManager.getInstance(project).debugSessions.any { session ->
            when (val process = session.debugProcess) {
                is WasmtimeGdbDebugProcess -> process.debugEndpoint == endpoint
                is WamrDebugProcess -> process.debugEndpoint == endpoint
                else -> false
            }
        }
    }
}

internal fun detectWasmAttachTarget(processInfo: ProcessInfo): WasmAttachTarget? {
    val args = try {
        ParametersListUtil.parse(processInfo.args)
    } catch (_: Exception) {
        return null
    }
    return detectWasmAttachTarget(processInfo.executableName, args)
}

private class WasmAttachDebugger(private val target: WasmAttachTarget) : XAttachDebugger {

    override fun getDebuggerDisplayName(): String = when (target) {
        is WasmAttachTarget.GdbStub -> {
            val runtime = if (target.engine == WasmEngine.WAMR) "WAMR" else "Wasmtime"
            "Hexana WebAssembly ($runtime gdbstub ${target.host}:${target.port})"
        }
        is WasmAttachTarget.NodeInspector -> "Hexana WebAssembly (Node.js inspector ${target.host}:${target.port})"
        is WasmAttachTarget.ChromeDevTools -> "Hexana WebAssembly (Chrome DevTools)"
    }

    override fun attachDebugSession(project: Project, attachHost: XAttachHost, processInfo: ProcessInfo) {
        val sessionName = "${processInfo.executableDisplayName} (${processInfo.pid})"
        val starter = runBlockingWork(project, "Attaching to ${processInfo.executableDisplayName}…") {
            prepareStarter(project, processInfo)
        }
        runOnEdt { startAttachedDebugSession(project, sessionName, starter) }
    }

    private fun prepareStarter(project: Project, processInfo: ProcessInfo): XDebugProcessStarter {
        val wasmPath = resolveModulePath(target.wasmPath, processInfo.pid, project.basePath)
        val wasmBytes = wasmPath?.let { path ->
            try {
                File(path).readBytes()
            } catch (e: Exception) {
                LOG.debug("Failed to read attached module $path: ${e.message}")
                null
            }
        }
        val dwarfResolver = wasmBytes?.let { bytes ->
            try {
                WasmDwarfVariableResolver.create(bytes)
            } catch (e: Exception) {
                LOG.debug("Failed to create DWARF variable resolver for $wasmPath: ${e.message}")
                null
            }
        }
        return when (target) {
            is WasmAttachTarget.GdbStub -> gdbStubStarter(project, target, wasmPath, dwarfResolver)
            is WasmAttachTarget.NodeInspector -> {
                val wsUrl = CdpDebugRunner.discoverNodeWebSocketUrl(target.host, target.port)
                    ?: throw ExecutionException(
                        "No Node.js inspector answered at ${target.host}:${target.port}. " +
                            "Another debugger may already be attached."
                    )
                cdpStarter(project, wsUrl, WasmEngine.NODE_JS, wasmPath, wasmBytes, dwarfResolver)
            }
            is WasmAttachTarget.ChromeDevTools -> {
                val port = chromeDevToolsPort(target)
                    ?: throw ExecutionException(
                        "Cannot find the DevTools port of this browser: it was started with " +
                            "--remote-debugging-port=0 and its DevToolsActivePort file is missing."
                    )
                val wsUrl = CdpDebugRunner.discoverPageWebSocketUrl(LOOPBACK, port, retries = 1)
                    ?: throw ExecutionException("The browser on DevTools port $port has no page to debug.")
                cdpStarter(project, wsUrl, WasmEngine.BROWSER, wasmPath, wasmBytes, dwarfResolver)
            }
        }
    }

    private fun gdbStubStarter(
        project: Project,
        target: WasmAttachTarget.GdbStub,
        wasmPath: String?,
        dwarfResolver: WasmDwarfVariableResolver?,
    ): XDebugProcessStarter = object : XDebugProcessStarter() {
        override fun start(session: XDebugSession): XDebugProcess {
            val lldbExe = WasmRuntimeSettings.getInstance().effectiveLldbPath()
            WasmDebugRunner.notifyIfLldbMissing(project, lldbExe)
            val lldbCommandLine = GeneralCommandLine().apply {
                exePath = lldbExe
                withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            }
            LOG.info("Starting LLDB to attach to ${target.engine.displayName} gdbstub at ${target.host}:${target.port}")

            val lldbHandler = MostlySilentOSProcessHandler(lldbCommandLine)
            val lldb = LldbCommunicator(lldbHandler)
            lldb.attachToProcessHandler()
            ProcessTerminatedListener.attach(lldbHandler)
            lldbHandler.startNotify()
            lldb.sendCommand("settings set target.disable-aslr false")

            val console = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .console
                .also { it.attachToProcess(lldbHandler) }

            // No runtime handler: the runtime belongs to whoever started it,
            // and the debug process detaches from it on stop.
            val process = if (target.engine == WasmEngine.WAMR) {
                WamrDebugProcess(
                    session, console, lldbHandler, null, lldb,
                    debugHost = target.host,
                    debugPort = target.port,
                    executedWasmPath = wasmPath,
                    engine = WasmEngine.WAMR,
                )
            } else {
                WasmtimeGdbDebugProcess(
                    session, console, lldbHandler, null, lldb,
                    debugHost = target.host,
                    debugPort = target.port,
                    executedWasmPath = wasmPath,
                )
            }
            return process.also { it.dwarfVariableResolver = dwarfResolver }
        }
    }

    private fun cdpStarter(
        project: Project,
        wsUrl: String,
        engine: WasmEngine,
        wasmPath: String?,
        wasmBytes: ByteArray?,
        dwarfResolver: WasmDwarfVariableResolver?,
    ): XDebugProcessStarter {
        val sourceLocationResolver = wasmBytes?.let { bytes ->
            try {
                CdpSourceLocationResolver.create(bytes)
            } catch (e: Exception) {
                LOG.debug("Failed to create DWARF source location resolver for $wasmPath: ${e.message}")
                null
            }
        }
        return object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val handler = DetachedTargetProcessHandler().also { it.startNotify() }
                val console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(project)
                    .console
                    .also { it.attachToProcess(handler) }
                // Without a local module the process reads DWARF from the
                // module bytes the target already compiled.
                return CdpDebugProcess(
                    session = session,
                    console = console,
                    processHandler = handler,
                    wsUrl = wsUrl,
                    engine = engine,
                    dwarfVariableResolver = dwarfResolver,
                    sourceLocationResolver = sourceLocationResolver,
                    httpServer = null,
                    tempUserDataDir = null,
                    wasmFilePath = wasmPath,
                )
            }
        }
    }

    private fun chromeDevToolsPort(target: WasmAttachTarget.ChromeDevTools): Int? {
        if (target.port != 0) return target.port
        val userDataDir = target.userDataDir ?: return null
        return try {
            File(userDataDir, "DevToolsActivePort").takeIf { it.isFile }
                ?.useLines { lines -> lines.firstOrNull()?.trim()?.toIntOrNull() }
                ?.takeIf { it in 1..65535 }
        } catch (e: Exception) {
            LOG.debug("Failed to read DevToolsActivePort in $userDataDir: ${e.message}")
            null
        }
    }
}

/**
 * Stands in for a debuggee the IDE did not start. Ending the session only
 * ends this handler; the real process is left alone.
 */
private class DetachedTargetProcessHandler : ProcessHandler() {
    override fun destroyProcessImpl() = notifyProcessTerminated(0)
    override fun detachProcessImpl() = notifyProcessDetached()
    override fun detachIsDefault(): Boolean = true
    override fun getProcessInput() = null
}

/**
 * Resolves the module path from a process's command line. Relative paths
 * are tried against the process's working directory (readable on Linux
 * only) and then the project directory.
 */
internal fun resolveModulePath(path: String?, pid: Int, projectBasePath: String?): String? {
    if (path.isNullOrBlank()) return null
    val file = File(path)
    if (file.isAbsolute) return file.takeIf { it.isFile }?.absolutePath
    val baseDirs = buildList {
        if (SystemInfo.isLinux) {
            try {
                add(File("/proc/$pid/cwd").canonicalFile)
            } catch (_: Exception) {
            }
        }
        if (projectBasePath != null) add(File(projectBasePath))
    }
    return baseDirs.map { File(it, path) }.firstOrNull { it.isFile }?.absolutePath
}

private fun <T> runBlockingWork(project: Project, title: String, work: () -> T): T =
    if (ApplicationManager.getApplication().isDispatchThread) {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<T, ExecutionException> { work() },
            title,
            true,
            project,
        )
    } else {
        work()
    }

private fun runOnEdt(action: () -> Unit) {
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) {
        action()
        return
    }
    var failure: Throwable? = null
    application.invokeAndWait {
        try {
            action()
        } catch (e: Throwable) {
            failure = e
        }
    }
    val error = failure
    if (error != null) throw error
}

private const val LOOPBACK = "127.0.0.1"

private val LOG = Logger.getInstance(WasmAttachDebuggerProvider::class.java)
