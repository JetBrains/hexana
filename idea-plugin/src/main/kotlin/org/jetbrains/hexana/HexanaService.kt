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
    }

    sealed interface WatConversionResult {
        class Success(val wat: String, val warning: String? = null) : WatConversionResult
        class Error(val error: String) : WatConversionResult
    }

    @RequiresBackgroundThread
    fun convertWasmToWat(file: File): WatConversionResult {
        return try {
            return convertToWasm(file)
        }
        catch (e: Exception) {
            WatConversionResult.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }

    private fun convertToWasm(file: File): WatConversionResult {
        val settings = HexanaSettings.getInstance()
        return if (settings.useBuiltInWasmTools == true) {
            convertToWasmWithBuiltInTools(file)
        } else {
            convertToWasmWithExternalTools(file)
        }

    }

    private fun convertToWasmWithExternalTools(file: File): WatConversionResult {
        val wasmTools = HexanaSettings.getInstance().wasmToolsPath
        val cmd = GeneralCommandLine(wasmTools, "print", "--fold-instructions", "--name-unnamed", file.absolutePath)
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

    private fun convertToWasmWithBuiltInTools(file: File): WatConversionResult.Success = FileInputStream(file).use { wasmInputStream ->
        val wasmBytes = wasmInputStream.readAllBytes()
        WatConversionResult.Success(printWat(wasmBytes))
    }
}