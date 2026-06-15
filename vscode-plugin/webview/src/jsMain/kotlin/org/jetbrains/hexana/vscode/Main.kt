package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider

fun main() {
    renderComposable(rootElementId = "root") {
        WasmEditorApp()
    }
}

@Composable
fun WasmEditorApp() {
    var wasmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var filePath by remember { mutableStateOf("") }

    VscodeMessageListener { message ->
        when (message.type) {
            "load-wasm" -> {
                fileName = message.fileName
                filePath = message.filePath
                wasmBytes = message.data
                configuredRuntimeId = message.wasmRuntime
            }
        }
    }

    if (wasmBytes == null) {
        Div({ classes("loading") }) {
            Text("Waiting for binary…")
        }
    } else {
        val bytes = wasmBytes!!
        val buffer = bytes.toCommonByteBuffer()
        buffer.position(0)

        if (isZipFile(buffer)) {
            ZipEditorContent(
                fileName = fileName,
                buffer = buffer,
            )
        } else {
            val provider = when (WasmBinaryProviderSelector.select(buffer)) {
                BinaryProviderType.Wasm -> WasmBinaryProvider(buffer)
                BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(buffer)
                else -> nativeOrGenericBinaryProvider(buffer, filePath)
            }

            WasmEditorContent(
                fileName = fileName,
                provider = provider,
            )
        }
    }
}

private const val MIN_PANEL_HEIGHT = 80.0
private const val DEFAULT_PANEL_HEIGHT = 250.0

@Composable
fun WasmEditorContent(
    fileName: String,
    provider: BinaryProvider,
) {
    val selection = remember { HexSelectionState() }
    val buffer = remember(provider) { provider.bytes }
    var panelHeight by remember { mutableStateOf(DEFAULT_PANEL_HEIGHT) }
    var activeTab by remember { mutableStateOf("Sections") }

    val hasTopTab = remember(provider) {
        provider is WasmBinaryProvider || provider is WasmComponentBinaryProvider
    }

    // Edit state: tracks in-memory byte modifications.
    val editState = remember(provider) {
        HexEditState(buffer.toByteArray())
    }
    var overwriteShadow by remember { mutableStateOf<OverwriteShadow?>(null) }

    // Reparse counter: incremented after overwrite/discard to rebuild the provider.
    var reparseCounter by remember { mutableStateOf(0) }

    // Live provider rebuilt from edited bytes when reparseCounter changes.
    val liveProvider = remember(reparseCounter) {
        if (reparseCounter == 0) provider
        else {
            val liveBuffer = editState.toBuffer()
            try {
                when (provider) {
                    is WasmBinaryProvider -> WasmBinaryProvider(liveBuffer)
                    is WasmComponentBinaryProvider -> WasmComponentBinaryProvider(liveBuffer)
                    else -> GenericBinaryProvider(liveBuffer)
                }
            } catch (_: Throwable) {
                GenericBinaryProvider(liveBuffer)
            }
        }
    }

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
        EditorToolbar(
            fileName = fileName,
            size = buffer.size,
            provider = provider,
            onSizeClick = if (hasTopTab) {{ activeTab = "Top" }} else null,
            editState = editState,
            overwriteShadow = overwriteShadow,
            onOverwriteShadowChanged = { overwriteShadow = it },
            onReparseAfterOverwrite = { reparseCounter++ },
        )
        HexViewerPanel(bytes = buffer, selection = selection, editState = editState)
        SelectionStatusBar(selection = selection, totalBytes = buffer.size)
        ResizeDivider { deltaY ->
            // Dragging down → shrink the panel (panel is below the divider)
            panelHeight = (panelHeight - deltaY).coerceAtLeast(MIN_PANEL_HEIGHT)
        }
        HexanaPanel(
            provider = liveProvider,
            selection = selection,
            fileName = fileName,
            heightPx = panelHeight,
            activeTab = activeTab,
            onActiveTabChange = { activeTab = it },
        )
    }
}
