package org.jetbrains.hexana.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpServer
import org.jetbrains.hexana.shared.generateBrowserRunnerHtml
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress

class BrowserCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = BrowserProcessHandler(configuration)
        val console = createConsole(executor)
        console?.attachToProcess(handler)
        handler.startNotify()
        return DefaultExecutionResult(console, handler)
    }

    override fun startProcess(): ProcessHandler {
        return BrowserProcessHandler(configuration)
    }

    companion object {
        private val LOG = Logger.getInstance(BrowserCommandLineState::class.java)
    }
}

private class BrowserProcessHandler(
    private val configuration: WasmRunConfiguration,
) : ProcessHandler() {

    @Volatile
    private var server: HttpServer? = null

    override fun startNotify() {
        super.startNotify()
        try {
            doStart()
        } catch (e: Exception) {
            notifyTextAvailable("Failed to start: ${e.message}\n", ProcessOutputType.STDERR)
            destroyProcessImpl()
        }
    }

    private fun doStart() {
        val wasmFile = File(configuration.wasmFilePath)
        if (!wasmFile.exists()) {
            notifyTextAvailable("Wasm file not found: ${wasmFile.absolutePath}\n", ProcessOutputType.STDERR)
            destroyProcessImpl()
            return
        }

        val exportName = configuration.exportName.ifBlank { "_start" }
        val programArgs = configuration.programArguments.trim()

        val wasmBytes = wasmFile.readBytes()
        val htmlContent = generateBrowserRunnerHtml(wasmFile.name, exportName, programArgs)

        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        this.server = httpServer

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

        val port = httpServer.address.port
        val url = "http://127.0.0.1:$port/"

        notifyTextAvailable("Serving WebAssembly module at $url\n", ProcessOutputType.STDOUT)
        notifyTextAvailable("Export: $exportName\n", ProcessOutputType.STDOUT)
        notifyTextAvailable("Open the browser developer console to see the output.\n", ProcessOutputType.STDOUT)
        notifyTextAvailable("Stop this run configuration to shut down the server.\n\n", ProcessOutputType.STDOUT)

        BrowserUtil.browse(url)
    }

    override fun destroyProcessImpl() {
        server?.stop(0)
        server = null
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        destroyProcessImpl()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}

