package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.javabytecode.JavaClassFileParser
import org.jetbrains.hexana.providers.*

fun main() {
    renderComposable(rootElementId = "root") {
        BinaryEditorApp()
    }
}

/**
 * Extracts nested module bytes from a parent binary at the given member index.
 *
 * Supports two parent formats:
 * - **Wasm component**: uses [WasmComponentBinaryProvider] to locate the core
 *   module at [moduleIndex] by its content offset/size.
 * - **Archive** (Unix `ar` / COFF): uses [ArchiveBinaryProvider] to find the
 *   member whose [PeArchiveMember.index] matches [moduleIndex].
 *
 * @return the extracted bytes, or `null` if extraction fails.
 */
private fun extractNestedModuleBytes(parentData: ByteArray, moduleIndex: Int): ByteArray? {
    val buffer = parentData.toCommonByteBuffer()
    buffer.position(0)

    // Try as wasm component first.
    when (WasmBinaryProviderSelector.select(buffer)) {
        BinaryProviderType.WasmComponent -> {
            val provider = WasmComponentBinaryProvider(buffer)
            val module = provider.modules.getOrNull(moduleIndex) ?: return null
            val result = ByteArray(module.contentSize)
            val src = provider.bytes
            src.position(module.contentOffset)
            src.get(result, 0, module.contentSize)
            return result
        }
        else -> {}
    }

    // Try as archive.
    buffer.position(0)
    val nativeProvider = nativeOrGenericBinaryProvider(buffer)
    if (nativeProvider is ArchiveBinaryProvider) {
        val member = nativeProvider.members
            .filterNot { it.isBookkeeping }
            .find { it.index == moduleIndex }
            ?: return null
        return nativeProvider.bytes
            .slice(member.contentRange.offset, member.contentRange.size)
            .toByteArray()
    }

    return null
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

/**
 * Holds the state for a diff view: the two binary payloads and their names.
 */
class DiffPayload(
    val leftData: ByteArray,
    val leftName: String,
    val rightData: ByteArray,
    val rightName: String,
)

/**
 * Holds the state for a native binary diff view.
 */
class NativeDiffPayload(
    val leftData: ByteArray,
    val leftName: String,
    val rightData: ByteArray,
    val rightName: String,
)

@Composable
fun BinaryEditorApp() {
    var wasmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var filePath by remember { mutableStateOf("") }
    var diffPayload by remember { mutableStateOf<DiffPayload?>(null) }
    var nativeDiffPayload by remember { mutableStateOf<NativeDiffPayload?>(null) }
    var expiredMessage by remember { mutableStateOf<String?>(null) }
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
            "load-diff" -> {
                val left = message.leftData
                val right = message.rightData
                if (left != null && right != null) {
                    diffPayload = DiffPayload(left, message.leftName, right, message.rightName)
                }
            }
            "load-native-diff" -> {
                val left = message.leftData
                val right = message.rightData
                if (left != null && right != null) {
                    nativeDiffPayload = NativeDiffPayload(left, message.leftName, right, message.rightName)
                }
            }
            "load-expired" -> {
                expiredMessage = message.message.ifBlank {
                    "This diff session has expired. Please run \"Compare WASM With…\" again."
                }
            }
            "load-nested-module" -> {
                val parentData = message.parentData
                val idx = message.moduleIndex
                if (parentData != null && idx >= 0) {
                    val extracted = extractNestedModuleBytes(parentData, idx)
                    if (extracted != null) {
                        fileName = message.fileName
                        filePath = ""
                        wasmBytes = extracted
                        configuredRuntimeId = message.wasmRuntime
                    } else {
                        expiredMessage = "Failed to extract nested module from parent. Please reopen from the parent file."
                    }
                }
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

    if (expiredMessage != null) {
        Div({ classes("loading") }) {
            Text(expiredMessage!!)
        }
        return
    }

    val ndp = nativeDiffPayload
    if (ndp != null) {
        NativeDiffView(
            leftData = ndp.leftData,
            leftName = ndp.leftName,
            rightData = ndp.rightData,
            rightName = ndp.rightName,
        )
        return
    }

    val dp = diffPayload
    if (dp != null) {
        WasmDiffView(
            leftData = dp.leftData,
            leftName = dp.leftName,
            rightData = dp.rightData,
            rightName = dp.rightName,
        )
        return
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
            onScriptTabRemoved = { path ->
                scriptTabs.removeAll { it.path == path }
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
    onScriptTabRemoved: (path: String) -> Unit = {},
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
            onScriptTabRemoved = onScriptTabRemoved,
        )
    }
}
