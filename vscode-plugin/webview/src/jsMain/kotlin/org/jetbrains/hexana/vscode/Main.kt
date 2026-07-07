package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.javabytecode.JavaClassFileParser
import org.jetbrains.hexana.providers.*

fun main() {
    renderComposable(rootElementId = "root") {
        BinaryEditorApp()
    }
}

/**
 * Holds the state for a single script tab: its file path and the latest
 * content read from the extension host.
 */
class ScriptTabState(
    val path: String,
    content: String = "",
) {
    var content by mutableStateOf(content)
}

@Composable
fun BinaryEditorApp() {
    var wasmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var filePath by remember { mutableStateOf("") }
    val scriptTabs = remember { mutableStateListOf<ScriptTabState>() }
    var dialogPickedPath by remember { mutableStateOf("") }
    var dialogPickedContent by remember { mutableStateOf("") }

    VscodeMessageListener { message ->
        when (message.type) {
            "load-wasm" -> {
                fileName = message.fileName
                filePath = message.filePath
                wasmBytes = message.data
                configuredRuntimeId = message.wasmRuntime
            }
            "script-file-picked" -> {
                if (message.scriptPath.isNotBlank()) {
                    dialogPickedPath = message.scriptPath
                    dialogPickedContent = message.scriptContent
                }
            }
            "script-file-content" -> {
                val tab = scriptTabs.find { it.path == message.scriptPath }
                if (tab != null) {
                    tab.content = message.scriptContent
                }
            }
            "restore-script-tabs" -> {
                for (path in message.scriptPaths) {
                    if (scriptTabs.none { it.path == path }) {
                        scriptTabs.add(ScriptTabState(path))
                        postReadScriptFileToHost(path)
                    }
                }
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

        val provider = remember(buffer, filePath) {
            when (WasmBinaryProviderSelector.select(buffer)) {
                BinaryProviderType.Wasm -> WasmBinaryProvider(buffer)
                BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(buffer)
                else -> nativeOrGenericBinaryProvider(buffer, filePath)
            }
        }

        val extraTabs = remember(buffer) {
            computeExtraTabs(buffer)
        }

        BinaryEditorContent(
            fileName = fileName,
            provider = provider,
            extraTabs = extraTabs,
            scriptTabs = scriptTabs,
            dialogPickedPath = dialogPickedPath,
            dialogPickedContent = dialogPickedContent,
            onDialogPickedPathConsumed = {
                dialogPickedPath = ""
                dialogPickedContent = ""
            },
            onScriptTabConfirmed = { path, content ->
                if (scriptTabs.none { it.path == path }) {
                    scriptTabs.add(ScriptTabState(path, content))
                    postScriptTabAddedToHost(path)
                }
            },
        )
    }
}

private fun computeExtraTabs(buffer: org.jetbrains.hexana.io.CommonByteBuffer): List<PanelTab> {
    if (ClassFileBinaryProvider.isClassFile(buffer)) {
        val classFile = try {
            JavaClassFileParser.parse(buffer.duplicate().position(0))
        } catch (_: Exception) {
            null
        }
        return if (classFile != null) classFileExtraTabs(classFile) else emptyList()
    }
    if (DexBinaryProvider.isDexFile(buffer)) {
        val dexFile = try {
            DexFileParser.parse(buffer.duplicate().position(0))
        } catch (_: Exception) {
            null
        }
        return if (dexFile != null) dexFileExtraTabs(dexFile, buffer) else emptyList()
    }
    return emptyList()
}

private const val MIN_PANEL_HEIGHT = 80.0
private const val DEFAULT_PANEL_HEIGHT = 250.0

@Composable
fun BinaryEditorContent(
    fileName: String,
    provider: BinaryProvider,
    extraTabs: List<PanelTab> = emptyList(),
    scriptTabs: List<ScriptTabState> = emptyList(),
    dialogPickedPath: String = "",
    dialogPickedContent: String = "",
    onDialogPickedPathConsumed: () -> Unit = {},
    onScriptTabConfirmed: (path: String, content: String) -> Unit = { _, _ -> },
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
            extraTabs = extraTabs,
            scriptTabs = scriptTabs,
            dialogPickedPath = dialogPickedPath,
            dialogPickedContent = dialogPickedContent,
            onDialogPickedPathConsumed = onDialogPickedPathConsumed,
            onScriptTabConfirmed = onScriptTabConfirmed,
        )
    }
}
