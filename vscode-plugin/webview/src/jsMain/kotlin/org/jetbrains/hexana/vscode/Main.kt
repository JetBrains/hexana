package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector

fun main() {
    renderComposable(rootElementId = "root") {
        WasmEditorApp()
    }
}

@Composable
fun WasmEditorApp() {
    var wasmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }

    VscodeMessageListener { message ->
        when (message.type) {
            "load-wasm" -> {
                fileName = message.fileName
                wasmBytes = message.data
            }
        }
    }

    if (wasmBytes == null) {
        Div({ classes("loading") }) {
            Text("Waiting for WebAssembly binary…")
        }
    } else {
        val bytes = wasmBytes!!
        val buffer = bytes.toCommonByteBuffer()
        val providerType = WasmBinaryProviderSelector.select(buffer)
        buffer.position(0)

        WasmEditorContent(
            fileName = fileName,
            bytes = bytes,
            providerType = providerType,
        )
    }
}

@Composable
fun WasmEditorContent(
    fileName: String,
    bytes: ByteArray,
    providerType: BinaryProviderType,
) {
    val selection = remember { HexSelectionState() }

    Div({ classes("hexana-editor") }) {
        EditorToolbar(fileName = fileName, size = bytes.size, providerType = providerType)
        HexViewerPanel(bytes = bytes, selection = selection)
        SelectionStatusBar(selection = selection, totalBytes = bytes.size)
        SectionPanel(bytes = bytes, providerType = providerType, selection = selection)
    }
}
