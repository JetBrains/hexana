package org.jetbrains.hexana.firmware.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.firmware.Dts2ReplRunner
import org.jetbrains.hexana.firmware.HexanaFirmwareSettings
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.DEFAULT_UART_PERIPHERAL
import org.jetbrains.hexana.providers.firmware.parseDtsConsoleUart
import org.jetbrains.hexana.providers.firmware.renodeLibraryReplFor
import org.jetbrains.hexana.providers.firmware.ReplResult
import org.jetbrains.hexana.providers.firmware.ReplSource
import org.jetbrains.hexana.providers.firmware.buildRescScript
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Ring buffer capacity for Renode stdout (used by the watchdog peripheral diagnostic). */
private const val RING_BUFFER_CAPACITY = 200

/**
 * CommandLineState for running ELF firmware under Renode.
 *
 * Execution steps (all off EDT — [startProcess] runs on a pooled thread):
 * 1. Determine `.repl` source: library path or generated via dts2repl.
 * 2. Allocate a free TCP port for the UART socket terminal.
 * 3. Build and write a temporary `.resc` file.
 * 4. Launch: `renode --disable-xwt --console <resc>`.
 * 5. Start a coroutine on [Dispatchers.IO] to connect to the UART socket and pump
 *    lines into the IDE console.
 * 6. Pump Renode's own stdout/stderr into a bounded ring buffer and forward
 *    WARNING lines to the console.
 * 7. Arm the startup watchdog (scans ring buffer for peripheral access warnings on timeout).
 *
 * @param configuration The run configuration.
 * @param environment The execution environment.
 * @param selection Resolved [BackendSelection.Ok] (must have [Backend.Renode]).
 * @param gdbStubPort When non-null, `machine StartGdbServer <port>` is emitted in the
 *   `.resc` and emulation stays frozen for GDB attach.
 */
class RenodeCommandLineState(
    private val configuration: FirmwareRunConfiguration,
    environment: ExecutionEnvironment,
    private val selection: BackendSelection.Ok,
    val gdbStubPort: Int? = null,
) : CommandLineState(environment) {

    private val LOG = Logger.getInstance(RenodeCommandLineState::class.java)

    /** Temporary .resc file created in [startProcess]; deleted on process termination. */
    @Volatile private var tempResc: File? = null

    /** Bounded ring buffer of recent Renode stdout/stderr lines (last 200). */
    private val ringBuffer = ArrayDeque<String>(RING_BUFFER_CAPACITY + 1)

    /** Ring buffer accessor for the watchdog. Thread-safe copy-on-read. */
    private fun ringBufferSnapshot(): List<String> = synchronized(ringBuffer) { ringBuffer.toList() }

    override fun startProcess(): ProcessHandler {
        val settings = HexanaFirmwareSettings.getInstance()
        val renodePath = settings.effectiveRenodesPath()
            ?: throw ExecutionException("Renode executable not found. Set the path in Settings → Build → Firmware Emulator.")

        // The merged DTS drives both platform generation and the console UART name.
        // effectiveDtsPath falls back to the zephyr.dts discovered next to the ELF.
        val dtsText = configuration.effectiveDtsPath()?.let { p ->
            File(p).takeIf { it.exists() }?.readText()
        }

        // Determine .repl source.
        val replPath: String?
        val libraryPlatform: String?
        when (selection.replSource) {
            ReplSource.Library -> {
                replPath = null
                libraryPlatform = selection.getLibraryPlatform()
            }
            ReplSource.Generated, null -> {
                if (dtsText == null) throw ExecutionException(
                    "zephyr.dts is required for Renode platform generation but was not found. " +
                        "Set the DTS path in the run configuration."
                )
                val boardHint = null // profile boardId would go here
                val runner = Dts2ReplRunner()
                val result = try {
                    runner.convert(dtsText, boardHint)
                } catch (e: Exception) {
                    throw ExecutionException("dts2repl conversion failed: ${e.message}", e)
                }
                when (result) {
                    is ReplResult.Failed -> throw ExecutionException(
                        "dts2repl conversion failed: ${result.message}"
                    )
                    is ReplResult.Ok -> {
                        val replFile = FileUtil.createTempFile("hexana-renode-", ".repl", true)
                        replFile.writeText(result.replText)
                        replPath = replFile.absolutePath
                        libraryPlatform = null
                    }
                }
            }
        }

        // Allocate a free TCP port for the UART socket terminal.
        val uartPort = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: IOException) {
            throw ExecutionException("Failed to allocate a free TCP port for UART terminal: ${e.message}", e)
        }

        val extraLines = configuration.extraRescLines
            .lines()
            .filter { it.isNotBlank() }

        val rescText = buildRescScript(
            replPath = replPath,
            libraryPlatform = libraryPlatform,
            elfPath = configuration.elfPath,
            uartPort = uartPort,
            uartPeripheral = dtsText?.let(::parseDtsConsoleUart) ?: DEFAULT_UART_PERIPHERAL,
            extraLines = extraLines,
            gdbPort = gdbStubPort,
        )

        val rescFile = FileUtil.createTempFile("hexana-renode-", ".resc", true)
        rescFile.writeText(rescText)
        tempResc = rescFile

        LOG.info("Renode launch: $renodePath --disable-xwt --console ${rescFile.absolutePath}")
        LOG.debug("Generated .resc:\n$rescText")

        val cmdLine = GeneralCommandLine().apply {
            exePath = renodePath
            addParameter("--disable-xwt")
            addParameter("--console")
            addParameter(rescFile.absolutePath)
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        val handler = KillableColoredProcessHandler(cmdLine)
        ProcessTerminatedListener.attach(handler)
        registerNoOrphan(handler)

        // Cleanup temp files on termination.
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                tempResc?.delete()
                replPath?.let { p -> if (p.contains("hexana-renode-")) File(p).delete() }
            }
        })

        // Pump Renode stdout/stderr into ring buffer; forward WARNINGs.
        startRenodeOutputPump(handler, uartPort)

        return handler
    }

    override fun execute(
        executor: com.intellij.execution.Executor,
        runner: com.intellij.execution.runners.ProgramRunner<*>,
    ): com.intellij.execution.ExecutionResult {
        val result = super.execute(executor, runner)
        val console = result.executionConsole as? ConsoleView
        val handler = result.processHandler

        val replDesc = if (selection.replSource == ReplSource.Generated) {
            "a generated platform from zephyr.dts"
        } else {
            "a library platform"
        }
        console?.print(
            "Running under Renode emulation on $replDesc: unmodeled peripherals read zero / " +
                "ignore writes, timing differs, radios are not simulated.\n",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )

        val firstOutputSeen = AtomicBoolean(false)
        armStartupWatchdog(
            handler = handler,
            timeoutSeconds = configuration.startupTimeoutSeconds,
            firstOutputSeen = firstOutputSeen,
            ringBuffer = ::ringBufferSnapshot,
            isRenode = true,
            console = console ?: return result,
        )

        // Start UART reader on Dispatchers.IO; pump lines into console.
        val uartPort = extractUartPort()
        if (uartPort != null && console != null) {
            startUartPump(uartPort, handler, console, firstOutputSeen)
        }

        return result
    }

    /** Extracts the uartPort stored during [startProcess] via a field set approach. */
    @Volatile private var _uartPort: Int = 0

    private fun extractUartPort(): Int? = _uartPort.takeIf { it != 0 }

    /**
     * Starts a background thread that connects to the Renode UART socket and pumps
     * lines into the IDE console.
     *
     * Retries the socket connection with a 500ms backoff for up to
     * [configuration.startupTimeoutSeconds] total.
     */
    private fun startUartPump(
        uartPort: Int,
        handler: ProcessHandler,
        console: ConsoleView,
        firstOutputSeen: AtomicBoolean,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val deadline = System.currentTimeMillis() + configuration.startupTimeoutSeconds * 1000L
            var socket: Socket? = null
            while (System.currentTimeMillis() < deadline && !handler.isProcessTerminated) {
                try {
                    socket = Socket("localhost", uartPort)
                    break
                } catch (_: IOException) {
                    Thread.sleep(500)
                }
            }
            val conn = socket ?: return@executeOnPooledThread
            try {
                conn.getInputStream().bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null && !handler.isProcessTerminated) {
                        if (line.isNotBlank()) {
                            firstOutputSeen.compareAndSet(false, true)
                        }
                        console.print("$line\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        line = reader.readLine()
                    }
                }
            } catch (_: IOException) {
                // Normal on process termination.
            } finally {
                runCatching { conn.close() }
            }
        }
    }

    /**
     * Pumps Renode's own stdout/stderr into [ringBuffer]; WARNING lines are also
     * forwarded to the IDE console as SYSTEM_OUTPUT.
     *
     * This is attached as a [ProcessAdapter] rather than a separate socket because
     * Renode's monitor output (with `--console`) goes to its own stdout.
     */
    private fun startRenodeOutputPump(handler: ProcessHandler, uartPort: Int) {
        _uartPort = uartPort
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                val text = event.text.trimEnd()
                if (text.isBlank()) return
                synchronized(ringBuffer) {
                    ringBuffer.addLast(text)
                    while (ringBuffer.size > RING_BUFFER_CAPACITY) ringBuffer.removeFirst()
                }
            }
        })
    }
}

/**
 * Resolves the Renode library platform path for this selection. Returns a bare library
 * alias (no `@` prefix — that is added by [buildRescScript]).
 *
 * The board id is quoted inside [BackendSelection.Ok.rationale]; resolution goes through
 * [renodeLibraryReplFor], which applies slash-qualifier normalization. Fails loudly
 * instead of silently substituting a wrong platform.
 */
private fun BackendSelection.Ok.getLibraryPlatform(): String {
    val boardId = rationale.substringAfter("'", "").substringBefore("'", "")
    return renodeLibraryReplFor(boardId) ?: throw ExecutionException(
        "No Renode library platform found for board '$boardId'. " +
            "Use the generated platform source (dts2repl) instead."
    )
}
