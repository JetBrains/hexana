package org.jetbrains.hexana

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.hexana.analytics.*
import org.jetbrains.hexana.graalwasm.wasmprinter.printWat
import java.io.File
import java.io.FileInputStream

@Service
class HexanaService(
    val coroutineScope : CoroutineScope,
) : Disposable {
    private val analyticsInitialization = MutableStateFlow<AnalyticsInitializationState>(
        AnalyticsInitializationState.Pending
    )

    /**
     * Single-slot dispatcher that every transition of the global analytics
     * client runs on — the initial build and each consent-driven rebuild.
     *
     * [HexanaAnalytics] holds one process-wide client, so two rebuilds running
     * at once interleave their retire/install steps: the loser's client is
     * installed and then never torn down, and for a moment the service reports
     * Ready against a client that is about to be retired. Rapid toggling is the
     * obvious way in, but a rebuild whose flush takes its full timeout widens
     * the window enough that a second toggle need not be rapid at all.
     *
     * Serialised by dispatcher rather than by lock, because the work inside is
     * blocking: [flushAndShutdownAnalytics] joins a flush thread for up to two
     * seconds. A lock would be the wrong shape either way round — a coroutine
     * `Mutex` would be a suspending primitive held across a blocking call, and
     * a JVM monitor would park a second IO thread for as long as the first
     * one's flush runs. With parallelism 1 the queued rebuild is simply not
     * dispatched until the running one returns, and holds no thread while it
     * waits. Captures stay on plain [Dispatchers.IO] so they never queue behind
     * a flush. Same primitive, and the same reasoning, as `capstoneDispatcher`
     * in `NativeDisassemblyPanelVirtualized`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val analyticsLifecycleDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        coroutineScope.launch(analyticsLifecycleDispatcher) {
            initializeAnalytics()
        }
        subscribeToAnalyticsConsentChanges()
    }

    /**
     * Exists so this service can act as the parent disposable for the settings
     * message-bus connection.
     *
     * Deliberately does not flush: dispose can run on the EDT under a write
     * action, and the flush is a blocking network call. IDE exit — the case
     * that actually loses events — is handled before this, by
     * [org.jetbrains.hexana.analytics.HexanaAnalyticsLifecycleListener].
     */
    override fun dispose() = Unit

    companion object {
        @JvmStatic
        fun getInstance(): HexanaService = service()

        const val WAT_EDITOR_MAX_FILE_SIZE = 3 * 1024 * 1024

        const val NOTIFICATION_GROUP = "hexana"
    }

    sealed interface WatConversionResult {
        class Success(val wat: String, val warning: String? = null) : WatConversionResult
        class Error(val error: String) : WatConversionResult
    }

    /**
     * Rebuilds the analytics client when the user flips the opt-out.
     *
     * Turning analytics off has to take effect at once, so the live client is
     * flushed — delivering only what the user had consented to at capture time
     * — and then torn down. Turning it back on is free: [PostHogJvmClient] only
     * allocates the SDK on the first capture that consent allows. This mirrors
     * `reinitAnalytics()` in the VS Code extension.
     */
    private fun subscribeToAnalyticsConsentChanges() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            HexanaSettings.TOPIC,
            object : HexanaSettingsListener {
                override fun sendAnalyticsChanged(enabled: Boolean) {
                    // Set synchronously, while still inside syncPublisher, so
                    // that by the time the setter returns the service is
                    // already known to be re-initializing. Doing it inside the
                    // coroutine instead would leave a window where the stale
                    // Ready state is observable and captures race the rebuild.
                    analyticsInitialization.value = AnalyticsInitializationState.Pending
                    // Ordered, not concurrent, in both directions: the old
                    // client is fully retired before the replacement is
                    // installed, and the pair is serialised against every other
                    // rebuild by [analyticsLifecycleDispatcher], which also
                    // preserves the order the toggles arrived in.
                    coroutineScope.launch(analyticsLifecycleDispatcher) {
                        flushAndShutdownAnalytics()
                        initializeAnalytics()
                    }
                }
            },
        )
    }

    private fun initializeAnalytics() {
        try {
            HexanaAnalytics.initialize(
                config = AnalyticsConfig(),
                consentProvider = IntellijConsentProvider(),
                distinctIdProvider = UuidDistinctIdProvider(),
                globalProperties = ideAnalyticsGlobalProperties(),
            )
            analyticsInitialization.value = AnalyticsInitializationState.Ready
        } catch (error: Throwable) {
            analyticsInitialization.value = AnalyticsInitializationState.Failed
            if (error is CancellationException) {
                throw error
            }
            if (error is VirtualMachineError || error is LinkageError) {
                throw error
            }
            HexanaLog.LOG.warn("Failed to initialize Hexana analytics", error)
        }
    }

    fun captureAnalytics(event: HexanaEvent): Job = coroutineScope.launch(Dispatchers.IO) {
        when (analyticsInitialization.first { it != AnalyticsInitializationState.Pending }) {
            AnalyticsInitializationState.Ready -> HexanaAnalytics.client.capture(event)
            AnalyticsInitializationState.Pending,
            AnalyticsInitializationState.Failed -> Unit
        }
    }

    suspend fun awaitAnalyticsInitialization() {
        analyticsInitialization.first { it != AnalyticsInitializationState.Pending }
    }

    @RequiresBackgroundThread
    fun convertWasmToWat(file: File, showOffsets: Boolean = HexanaSettings.getInstance().showOffsets == true): WatConversionResult {
        return try {
            return convertToWasm(file, showOffsets)
        }
        catch (e: Exception) {
            WatConversionResult.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }

    /**
     * Converts in-memory wasm bytes (nested modules/components opened from a
     * table tab, which have no path on disk) using the built-in printer. The
     * external wasm-tools setting only applies to file-backed conversions.
     */
    @RequiresBackgroundThread
    fun convertWasmToWat(wasmBytes: ByteArray, showOffsets: Boolean = HexanaSettings.getInstance().showOffsets == true): WatConversionResult {
        return try {
            val textWithStatus = printWat(wasmBytes, foldInstructions = false, printOffsets = showOffsets)
            WatConversionResult.Success(textWithStatus.wat, textWithStatus.warnings)
        } catch (e: Exception) {
            WatConversionResult.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }

    private fun convertToWasm(file: File, showOffsets: Boolean): WatConversionResult {
        val settings = HexanaSettings.getInstance()
        return if (settings.useBuiltInWasmTools == true || file.path.isHttpUrl()) {
            convertToWasmWithBuiltInTools(file, showOffsets)
        } else {
            convertToWasmWithExternalTools(file, showOffsets)
        }

    }

    private fun convertToWasmWithExternalTools(file: File, showOffsets: Boolean): WatConversionResult {
        val wasmTools = HexanaSettings.getInstance().wasmToolsPath
        val args = mutableListOf("print", "--fold-instructions", "--name-unnamed")
        if (showOffsets) {
            args.add("--print-offsets")
        }
        args.add(file.absolutePath)
        val cmd = GeneralCommandLine(wasmTools, *args.toTypedArray())
        val processOutput = ExecUtil.execAndGetOutput(cmd)
        return when (processOutput.exitCode) {
            0 -> {
                WatConversionResult.Success(processOutput.stdout)
            }
            1 -> {
                // We treat this as a warning, so we just pust stderr as a comment to a supposedly still valid output
                val stdErrAsComment = "(;${processOutput.stderr.removeSuffix("\n")});) \n"
                WatConversionResult.Success(stdErrAsComment + processOutput.stdout)
            }
            else -> {
                WatConversionResult.Error(processOutput.stderr)
            }
        }
    }

    private fun convertToWasmWithBuiltInTools(file: File, showOffsets: Boolean): WatConversionResult {
        if (file.path.isHttpUrl()) {
            val wasmBytes = readRemoteResponseBlocking(normalizeHttpPath(file))
            val textWithStatus = printWat(wasmBytes, foldInstructions = false, printOffsets = showOffsets)
            return WatConversionResult.Success(textWithStatus.wat, textWithStatus.warnings)
        }
        return FileInputStream(file).use { wasmInputStream ->
            val wasmBytes = wasmInputStream.readAllBytes()
            val textWithStatus = printWat(wasmBytes, foldInstructions = false, printOffsets = showOffsets)
            WatConversionResult.Success(textWithStatus.wat, textWithStatus.warnings)
        }
    }

    private fun normalizeHttpPath(file: File): String = (if (file.path.startsWith("http:")) "http://" else "https://") +
            file.path.removePrefix("http://").removePrefix("https://").removePrefix("http:/").removePrefix("https:/")

    private enum class AnalyticsInitializationState {
        Pending,
        Ready,
        Failed,
    }
}
