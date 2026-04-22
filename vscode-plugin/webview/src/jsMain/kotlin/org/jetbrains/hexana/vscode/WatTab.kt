package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProviderType

/**
 * WAT tab content — converts the raw wasm bytes to WAT text using the
 * embedded `wasmprinter_wrapper.wasm` module, the same converter that
 * the IntelliJ plugin runs via GraalWasm.
 */
@Composable
fun WatTabContent(
    bytes: ByteArray,
    providerType: BinaryProviderType,
) {
    when (providerType) {
        is BinaryProviderType.Wasm,
        is BinaryProviderType.WasmComponent -> WasmPrinterWat(bytes)
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun WasmPrinterWat(bytes: ByteArray) {
    var watText by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bytes) {
        try {
            WasmPrinterJs.ensureInitialized()
            val result = WasmPrinterJs.printWat(bytes, foldInstructions = false)
            watText = result.wat
            warning = result.warnings
        } catch (e: Throwable) {
            error = e.message ?: "Failed to convert WASM to WAT"
        }
    }

    when {
        error != null -> Div { Text("Error: $error") }
        watText == null -> Div { Text("Converting…") }
        else -> {
            if (warning != null) {
                Div({
                    style {
                        property("color", "var(--vscode-editorWarning-foreground, #cca700)")
                        property("margin-bottom", "8px")
                        property("font-size", "12px")
                    }
                }) {
                    Text("Warning: $warning")
                }
            }
            WatTextView(watText!!)
        }
    }
}

@Composable
private fun WatTextView(watText: String) {
    Div({
        classes("hexana-wat-view")
        style {
            property("font-family", "var(--hexana-font, monospace)")
            property("font-size", "12px")
            property("line-height", "1.5")
            property("white-space", "pre")
            property("overflow-x", "auto")
            property("tab-size", "2")
        }
    }) {
        Pre({
            style {
                property("margin", "0")
                property("font-family", "inherit")
            }
        }) {
            Text(watText)
        }
    }
}
