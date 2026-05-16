package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider

/**
 * WAT tab content — converts the raw wasm bytes to WAT text using the
 * embedded `wasmprinter_wrapper.wasm` module, the same converter that
 * the IntelliJ plugin runs via GraalWasm, and opens the result in a
 * native VS Code editor tab (Monaco) for syntax highlighting and
 * full editor features.
 */
@Composable
fun WatTabContent(
    provider: BinaryProvider,
) {
    when (provider) {
        is WasmBinaryProvider,
        is WasmComponentBinaryProvider -> WasmPrinterWat(provider.bytes.toByteArray())
        else -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun WasmPrinterWat(bytes: ByteArray) {
    var status by remember { mutableStateOf<WatConversionStatus>(WatConversionStatus.Converting) }

    LaunchedEffect(bytes) {
        status = WatConversionStatus.Converting
        try {
            WasmPrinterJs.ensureInitialized()
            val result = WasmPrinterJs.printWat(bytes, foldInstructions = false)
            postShowWatToHost(result.wat, result.warnings)
            status = WatConversionStatus.Opened(result.warnings)
        } catch (e: Throwable) {
            status = WatConversionStatus.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }

    when (val s = status) {
        is WatConversionStatus.Converting -> Div({
            style {
                property("padding", "8px 0")
                property("opacity", "0.7")
            }
        }) {
            Text("Converting to WAT…")
        }
        is WatConversionStatus.Opened -> {
            if (s.warning != null) {
                Div({
                    style {
                        property("color", "var(--vscode-editorWarning-foreground, #cca700)")
                        property("margin-bottom", "8px")
                        property("font-size", "12px")
                    }
                }) {
                    Text("Warning: ${s.warning}")
                }
            }
            Div({
                style {
                    property("padding", "8px 0")
                    property("opacity", "0.7")
                }
            }) {
                Text("WAT output opened in editor tab.")
            }
        }
        is WatConversionStatus.Error -> Div { Text("Error: ${s.message}") }
    }
}

private sealed class WatConversionStatus {
    data object Converting : WatConversionStatus()
    data class Opened(val warning: String?) : WatConversionStatus()
    data class Error(val message: String) : WatConversionStatus()
}
