package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.CommonByteBuffer
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

private const val MIN_PANEL_HEIGHT = 80.0
private const val DEFAULT_PANEL_HEIGHT = 250.0

@Composable
fun WasmEditorContent(
    fileName: String,
    bytes: ByteArray,
    providerType: BinaryProviderType,
) {
    val selection = remember { HexSelectionState() }
    val buffer = remember(bytes) { bytes.toCommonByteBuffer() }
    var panelHeight by remember { mutableStateOf(DEFAULT_PANEL_HEIGHT) }

    Div({
        classes("hexana-editor")
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            width(100.percent)
            height(100.percent)
            property("overflow", "hidden")
        }
    }) {
        EditorToolbar(fileName = fileName, size = bytes.size, providerType = providerType, buffer = buffer, wasmBytes = bytes)
        HexViewerPanel(bytes = bytes, selection = selection)
        SelectionStatusBar(selection = selection, totalBytes = bytes.size)
        ResizeDivider { deltaY ->
            // Dragging down → shrink the panel (panel is below the divider)
            panelHeight = (panelHeight - deltaY).coerceAtLeast(MIN_PANEL_HEIGHT)
        }
        HexanaPanel(
            bytes = bytes,
            buffer = buffer,
            providerType = providerType,
            selection = selection,
            fileName = fileName,
            heightPx = panelHeight,
        )
    }
}
