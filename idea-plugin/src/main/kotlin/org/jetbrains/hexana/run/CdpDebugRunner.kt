package org.jetbrains.hexana.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.sun.net.httpserver.HttpServer
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildNodeJsRunArgs
import org.jetbrains.hexana.shared.generateBrowserRunnerHtml
import org.jetbrains.hexana.shared.CdpSourceLocationResolver
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A [GenericProgramRunner] that handles the **Debug** executor for
 * [WasmRunConfiguration] when the selected engine is **Browser** (Chrome)
 * or **Node.js**.
 *
 * ### Node.js
 * Launches Node.js with `--inspect-brk=0` so it pauses on the first
 * statement and listens on a random port. The runner parses the
 * `ws://` debug URL from stdout and connects a CDP debug session.
 *
 * ### Browser (Chrome)
 * Starts a local HTTP server to serve the Wasm module, then launches
 * Chrome with `--remote-debugging-port=0` and a temporary user-data
 * directory. The runner discovers the WebSocket debug URL from
 * Chrome's `/json/version` endpoint and connects a CDP debug session.
 */
class CdpDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return false
        val config = profile as? WasmRunConfiguration ?: return false
        return config.engine == WasmEngine.BROWSER || config.engine == WasmEngine.NODE_JS
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as? WasmRunConfiguration
            ?: throw ExecutionException("Expected WasmRunConfiguration")

        return when (configuration.engine) {
            WasmEngine.NODE_JS -> doExecuteNodeJs(configuration, environment)
            WasmEngine.BROWSER -> doExecuteBrowser(configuration, environment)
            else -> throw ExecutionException("CDP debug is not supported for ${configuration.engine.displayName}")
        }
    }

    // ---- Node.js debug ----

    private fun doExecuteNodeJs(
        configuration: WasmRunConfiguration,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val prep = ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                ThrowableComputable { prepareNodeJsSession(configuration) },
                "Starting Node.js debug session\u2026",
                true,
                environment.project,
            )

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(environment.project)
                    .console
                    .also { it.attachToProcess(prep.processHandler) }

                val dwarfResolver = createDwarfResolver(configuration)
                val sourceLocResolver = createSourceLocationResolver(configuration)

                return CdpDebugProcess(
                    session = session,
                    console = console,
                    processHandler = prep.processHandler,
                    wsUrl = prep.wsUrl,
                    engine = WasmEngine.NODE_JS,
                    dwarfVariableResolver = dwarfResolver,
                    sourceLocationResolver = sourceLocResolver,
                    httpServer = null,
                    tempUserDataDir = null,
                    wasmFilePath = configuration.wasmFilePath.ifBlank { null },
                )
            }
        }

        return startDebugSessionForRunner(environment, starter)
    }

    private class NodeJsPrepResult(
        val processHandler: OSProcessHandler,
        val wsUrl: String,
    )

    private fun prepareNodeJsSession(configuration: WasmRunConfiguration): NodeJsPrepResult {
        val nodePath = configuration.nodePath.ifBlank {
            WasmRuntimeSettings.getInstance().effectiveNodePath()
        }

        val input = WasmRunInput(
            wasmFilePath = configuration.wasmFilePath,
            programArguments = configuration.programArguments,
            workingDirectory = configuration.workingDirectory,
            runtimeOptions = configuration.nodeOptions,
            exportName = configuration.exportName,
        )

        val cmdLine = buildNodeJsRunArgs(input)
        val commandLine = GeneralCommandLine().apply {
            exePath = nodePath
            addParameter("--inspect-brk=0")
            addParameters(cmdLine.arguments)
            if (cmdLine.workingDirectory != null) {
                withWorkDirectory(cmdLine.workingDirectory)
            }
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        LOG.info("Starting Node.js with debug: ${commandLine.commandLineString}")

        val handler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)

        val wsUrlLatch = CountDownLatch(1)
        val collectedOutput = Collections.synchronizedList(mutableListOf<String>())
        val detectedWsUrl = arrayOfNulls<String>(1)

        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                collectedOutput.add(text)
                if (detectedWsUrl[0] == null) {
                    val match = WS_URL_REGEX.find(text)
                    if (match != null) {
                        detectedWsUrl[0] = match.value
                        wsUrlLatch.countDown()
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                wsUrlLatch.countDown()
            }
        })
        handler.startNotify()

        val ready = wsUrlLatch.await(NODE_DEBUG_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ready || detectedWsUrl[0] == null) {
            if (handler.isProcessTerminated) {
                val output = collectedOutput.joinToString("").trim()
                throw ExecutionException(
                    "Node.js process terminated before debug server started.\n\n" +
                        "Output:\n$output\n\nCommand: ${commandLine.commandLineString}"
                )
            }
            throw ExecutionException(
                "Timed out waiting for Node.js debug WebSocket URL. " +
                    "Make sure Node.js supports --inspect-brk."
            )
        }

        return NodeJsPrepResult(handler, detectedWsUrl[0]!!)
    }

    // ---- Browser (Chrome) debug ----

    private fun doExecuteBrowser(
        configuration: WasmRunConfiguration,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val prep = ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                ThrowableComputable { prepareBrowserSession(configuration) },
                "Starting Chrome debug session\u2026",
                true,
                environment.project,
            )

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(environment.project)
                    .console
                    .also { it.attachToProcess(prep.processHandler) }

                val dwarfResolver = createDwarfResolver(configuration)
                val sourceLocResolver = createSourceLocationResolver(configuration)

                return CdpDebugProcess(
                    session = session,
                    console = console,
                    processHandler = prep.processHandler,
                    wsUrl = prep.wsUrl,
                    engine = WasmEngine.BROWSER,
                    dwarfVariableResolver = dwarfResolver,
                    sourceLocationResolver = sourceLocResolver,
                    httpServer = prep.httpServer,
                    tempUserDataDir = prep.tempUserDataDir,
                    wasmFilePath = configuration.wasmFilePath.ifBlank { null },
                )
            }
        }

        return startDebugSessionForRunner(environment, starter)
    }

    private class BrowserPrepResult(
        val processHandler: OSProcessHandler,
        val wsUrl: String,
        val httpServer: HttpServer,
        val tempUserDataDir: File,
    )

    private fun prepareBrowserSession(configuration: WasmRunConfiguration): BrowserPrepResult {
        val chromePath = configuration.chromePath.ifBlank {
            WasmRuntimeSettings.getInstance().effectiveChromePath()
        }

        // Start HTTP server to serve the Wasm module
        val wasmFile = File(configuration.wasmFilePath)
        if (!wasmFile.exists()) {
            throw ExecutionException("Wasm file not found: ${wasmFile.absolutePath}")
        }

        val exportName = configuration.exportName.ifBlank { "_start" }
        val programArgs = configuration.programArguments.trim()
        val wasmBytes = wasmFile.readBytes()
        val htmlContent = generateBrowserRunnerHtml(wasmFile.name, exportName, programArgs)

        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/" || path == "/index.html" -> {
                    val bytes = htmlContent.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                path == "/${wasmFile.name}" -> {
                    exchange.responseHeaders.add("Content-Type", "application/wasm")
                    exchange.sendResponseHeaders(200, wasmBytes.size.toLong())
                    exchange.responseBody.use { it.write(wasmBytes) }
                }
                else -> {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
        }
        httpServer.executor = null
        httpServer.start()

        val serverPort = httpServer.address.port
        val pageUrl = "http://127.0.0.1:$serverPort/"

        // Create a temporary user-data directory for Chrome
        val tempUserDataDir = File(System.getProperty("java.io.tmpdir"), "hexana-chrome-${Random.nextInt()}")
        tempUserDataDir.mkdirs()

        // Launch Chrome with remote debugging
        val commandLine = GeneralCommandLine().apply {
            exePath = chromePath
            addParameter("--remote-debugging-port=0")
            addParameter("--no-first-run")
            addParameter("--no-default-browser-check")
            addParameter("--user-data-dir=${tempUserDataDir.absolutePath}")
            addParameter(pageUrl)
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        LOG.info("Starting Chrome with debug: ${commandLine.commandLineString}")

        val handler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)

        // Chrome prints "DevTools listening on ws://..." to stderr
        val wsUrlLatch = CountDownLatch(1)
        val collectedOutput = Collections.synchronizedList(mutableListOf<String>())
        val detectedWsUrl = arrayOfNulls<String>(1)

        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                collectedOutput.add(text)
                if (detectedWsUrl[0] == null) {
                    val match = WS_URL_REGEX.find(text)
                    if (match != null) {
                        detectedWsUrl[0] = match.value
                        wsUrlLatch.countDown()
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                wsUrlLatch.countDown()
            }
        })
        handler.startNotify()

        val ready = wsUrlLatch.await(CHROME_DEBUG_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ready || detectedWsUrl[0] == null) {
            httpServer.stop(0)
            tempUserDataDir.deleteRecursively()
            if (handler.isProcessTerminated) {
                val output = collectedOutput.joinToString("").trim()
                throw ExecutionException(
                    "Chrome process terminated before debug server started.\n\n" +
                        "Output:\n$output\n\nCommand: ${commandLine.commandLineString}"
                )
            }
            throw ExecutionException(
                "Timed out waiting for Chrome debug WebSocket URL. " +
                    "Make sure the Chrome path is correct and supports --remote-debugging-port."
            )
        }

        // The WebSocket URL from Chrome's stderr is the *browser-level*
        // endpoint which doesn't support Runtime/Debugger domains
        // directly. We need the *page-level* WebSocket URL instead.
        // Extract the debug port and query Chrome's /json endpoint to
        // find the page target's webSocketDebuggerUrl.
        val browserWsUrl = detectedWsUrl[0]!!
        val browserUri = URI(browserWsUrl)
        val pageWsUrl = browserUri.port.takeIf { it > 0 }
            ?.let { port -> discoverPageWebSocketUrl(browserUri.host ?: "127.0.0.1", port) }
            ?: throw ExecutionException(
                "Failed to discover Chrome page debug target. " +
                    "Browser WebSocket URL: $browserWsUrl"
            )

        return BrowserPrepResult(handler, pageWsUrl, httpServer, tempUserDataDir)
    }

    private fun createDwarfResolver(configuration: WasmRunConfiguration): WasmDwarfVariableResolver? {
        return try {
            val wasmFile = File(configuration.wasmFilePath)
            if (wasmFile.exists()) {
                WasmDwarfVariableResolver.create(wasmFile.readBytes())
            } else null
        } catch (e: Exception) {
            LOG.debug("Failed to create DWARF variable resolver: ${e.message}")
            null
        }
    }

    private fun createSourceLocationResolver(configuration: WasmRunConfiguration): CdpSourceLocationResolver? {
        return try {
            val wasmFile = File(configuration.wasmFilePath)
            if (wasmFile.exists()) {
                CdpSourceLocationResolver.create(wasmFile.readBytes())
            } else null
        } catch (e: Exception) {
            LOG.debug("Failed to create DWARF source location resolver: ${e.message}")
            null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CdpDebugRunner::class.java)
        const val RUNNER_ID = "CdpDebugRunner"

        private const val NODE_DEBUG_READY_TIMEOUT_MS = 10_000L
        private const val CHROME_DEBUG_READY_TIMEOUT_MS = 15_000L
        private const val PAGE_DISCOVERY_RETRIES = 10
        private const val PAGE_DISCOVERY_INTERVAL_MS = 500L

        /**
         * Queries the `/json` HTTP endpoint of a Chrome DevTools server at
         * [host]:[port] for the page-level WebSocket debugger URL. Chrome's
         * browser-level WebSocket (printed to stderr) doesn't support per-page
         * CDP domains like `Runtime` and `Debugger` — those require connecting
         * to a specific target.
         *
         * Retries [retries] times because the page target may not appear
         * immediately after Chrome starts.
         */
        internal fun discoverPageWebSocketUrl(
            host: String,
            port: Int,
            retries: Int = PAGE_DISCOVERY_RETRIES,
        ): String? = discoverWebSocketDebuggerUrl(host, port, retries) { target ->
            target["type"] == "page"
        }

        /**
         * Queries a Node.js inspector at [host]:[port] for its WebSocket
         * debugger URL (`/json/list`; Node exposes a single `node` target).
         */
        internal fun discoverNodeWebSocketUrl(host: String, port: Int): String? =
            discoverWebSocketDebuggerUrl(host, port, retries = 1, path = "/json/list") { true }

        @Suppress("UNCHECKED_CAST")
        private fun discoverWebSocketDebuggerUrl(
            host: String,
            port: Int,
            retries: Int,
            path: String = "/json",
            accept: (Map<String, Any?>) -> Boolean,
        ): String? {
            val hostPart = if (host.contains(':')) "[$host]" else host
            val jsonUrl = "http://$hostPart:$port$path"

            repeat(retries) { attempt ->
                try {
                    val conn = URI(jsonUrl).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = 2_000
                    conn.readTimeout = 2_000
                    val body = try {
                        conn.inputStream.bufferedReader().readText()
                    } finally {
                        conn.disconnect()
                    }

                    val targets = com.google.gson.Gson().fromJson(
                        body, List::class.java
                    ) as? List<Map<String, Any?>> ?: emptyList()

                    for (target in targets) {
                        if (!accept(target)) continue
                        val wsDebugUrl = target["webSocketDebuggerUrl"] as? String
                        if (wsDebugUrl != null) {
                            LOG.info("Discovered WebSocket debugger URL: $wsDebugUrl")
                            return wsDebugUrl
                        }
                    }
                } catch (e: Exception) {
                    LOG.debug("WebSocket debugger URL discovery attempt ${attempt + 1} at $jsonUrl failed: ${e.message}")
                }
                if (attempt < retries - 1) Thread.sleep(PAGE_DISCOVERY_INTERVAL_MS)
            }
            return null
        }

        /** Matches WebSocket debug URLs printed by Node.js and Chrome. */
        private val WS_URL_REGEX =
            Regex("""\bws://(\[[0-9a-fA-F:]+]|[\w.-]+):?(\d+)?(?:/([\w\-./]*))?\b""")

        /** Default Chrome executable name, resolved via PATH. */
        internal val DEFAULT_CHROME_PATH: String = when {
            SystemInfo.isWindows -> "chrome.exe"
            SystemInfo.isMac -> "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
            else -> "google-chrome"
        }

        /**
         * Resolves the default Chrome/Chromium executable path by
         * checking well-known installation locations before falling
         * back to a bare command name for PATH lookup.
         */
        fun resolveDefaultChromePath(): String {
            val candidates = mutableListOf<String>()
            if (SystemInfo.isWindows) {
                // Chrome is not on PATH by default on Windows; check
                // well-known installation directories.
                val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
                val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
                val localAppData = System.getenv("LOCALAPPDATA") ?: ""
                candidates.add("$programFiles\\Google\\Chrome\\Application\\chrome.exe")
                candidates.add("$programFilesX86\\Google\\Chrome\\Application\\chrome.exe")
                if (localAppData.isNotEmpty()) {
                    candidates.add("$localAppData\\Google\\Chrome\\Application\\chrome.exe")
                }
                candidates.add("$programFiles\\Chromium\\Application\\chrome.exe")
                candidates.add("$programFilesX86\\Chromium\\Application\\chrome.exe")
            } else if (SystemInfo.isMac) {
                candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
                candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium")
            } else {
                candidates.add("/usr/bin/google-chrome")
                candidates.add("/usr/bin/google-chrome-stable")
                candidates.add("/usr/bin/chromium")
                candidates.add("/usr/bin/chromium-browser")
                candidates.add("/snap/bin/chromium")
            }
            for (candidate in candidates) {
                if (File(candidate).exists()) return candidate
            }
            return DEFAULT_CHROME_PATH
        }
    }
}
