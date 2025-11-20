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
        val wasm2wat = HexanaSettings.getInstance().wasm2watExecPath
        val cmd = GeneralCommandLine(wasm2wat, "--fold-exprs", "--generate-names", file.absolutePath)
        return try {
            val processOutput = ExecUtil.execAndGetOutput(cmd)
            if (processOutput.exitCode != 0) {
                WatConversionResult.Error(processOutput.stderr)
            } else {
                WatConversionResult.Success(processOutput.stdout)
            }
        }
        catch (e: Exception) {
            WatConversionResult.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }
}