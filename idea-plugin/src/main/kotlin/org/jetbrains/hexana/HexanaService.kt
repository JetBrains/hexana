package org.jetbrains.hexana

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.CoroutineScope
import java.io.File

@Service
class HexanaService(
    val coroutineScope : CoroutineScope,
) {

    companion object {
        @JvmStatic
        fun getInstance(): HexanaService = service()
    }

    sealed interface WatConversionResult {
        class Success(val wat: String) : WatConversionResult
        class Error(val error: String) : WatConversionResult
    }

    @RequiresBackgroundThread
    fun convertWasmToWat(file: File): WatConversionResult {
        val wasmTools = HexanaSettings.getInstance().wasmToolsExecPath
        val cmd = GeneralCommandLine(wasmTools, "print", "--fold-instructions", "--name-unnamed", file.absolutePath)
        return try {
            val processOutput = ExecUtil.execAndGetOutput(cmd)
            when (processOutput.exitCode) {
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
        catch (e: Exception) {
            WatConversionResult.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }
}