package org.jetbrains.hexana

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.hexana.graalwasm.wasmprinter.printWat
import java.io.File
import java.io.FileInputStream

@Service
class HexanaService(
    val coroutineScope : CoroutineScope,
) {

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

    @RequiresBackgroundThread
    fun convertWasmToWat(file: File, showOffsets: Boolean = HexanaSettings.getInstance().showOffsets == true): WatConversionResult {
        return try {
            return convertToWasm(file, showOffsets)
        }
        catch (e: Exception) {
            WatConversionResult.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }

    private fun convertToWasm(file: File, showOffsets: Boolean): WatConversionResult {
        val settings = HexanaSettings.getInstance()
        return if (settings.useBuiltInWasmTools == true) {
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

    private fun convertToWasmWithBuiltInTools(file: File, showOffsets: Boolean): WatConversionResult = FileInputStream(file).use { wasmInputStream ->
        val wasmBytes = wasmInputStream.readAllBytes()
        val textWithStatus = printWat(wasmBytes, foldInstructions = false, printOffsets = showOffsets)
        WatConversionResult.Success(textWithStatus.wat, textWithStatus.warnings)
    }
}