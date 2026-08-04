package org.jetbrains.hexana

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
) {
    private val analyticsInitialization = MutableStateFlow<AnalyticsInitializationState>(
        AnalyticsInitializationState.Pending
    )

    init {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val appInfo = ApplicationInfo.getInstance()
                HexanaAnalytics.initialize(
                    config = AnalyticsConfig(),
                    consentProvider = IntellijConsentProvider(),
                    distinctIdProvider = UuidDistinctIdProvider(),
                    globalProperties = mapOf(
                        "ide_type" to appInfo.versionName,
                        "ide_build" to appInfo.build.asString()
                    )
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
    }

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
