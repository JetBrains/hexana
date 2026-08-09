package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.javabytecode.JavaClassFileParser
import org.jetbrains.hexana.providers.*

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
fun extractNestedModuleBytes(parentData: ByteArray, moduleIndex: Int): ByteArray? {
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

fun computeExtraTabs(buffer: org.jetbrains.hexana.io.CommonByteBuffer): List<PanelTab> {
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
    parentFileName: String? = null,
    provider: BinaryProvider,
    extraTabs: List<PanelTab> = emptyList(),
    capabilities: WebUiCapabilities = WebUiCapabilities(),
    scriptTabs: List<ScriptTabState> = emptyList(),
    dialogPickedPath: String = "",
    dialogPickedContent: String = "",
    onDialogPickedPathConsumed: () -> Unit = {},
    onScriptTabConfirmed: (path: String, content: String) -> Unit = { _, _ -> },
    onScriptTabRemoved: (path: String) -> Unit = {},
    showSemanticHighlighting: Boolean = true,
    initialStructureRanges: List<StructureRange>? = null,
    fileTypeLabelOverride: String? = null,
    initialActiveTab: String = "Sections",
    onLegacyReparseRequested: ((ByteArray) -> Unit)? = null,
) {
    val selection = remember { HexSelectionState() }
    val buffer = remember(provider) { provider.bytes }
    var panelHeight by remember { mutableStateOf(DEFAULT_PANEL_HEIGHT) }
    var activeTab by remember(provider, initialActiveTab) { mutableStateOf(initialActiveTab) }
    var showDataInspector by remember { mutableStateOf(false) }

    val hasTopTab = remember(provider, extraTabs) {
        provider is WasmBinaryProvider || provider is WasmComponentBinaryProvider || extraTabs.any { it.label == "Top" }
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
            parentFileName = parentFileName,
            size = buffer.size,
            provider = provider,
            onSizeClick = if (hasTopTab) {{ activeTab = "Top" }} else null,
            editState = editState,
            overwriteShadow = overwriteShadow,
            onOverwriteShadowChanged = { overwriteShadow = it },
            onReparseAfterOverwrite = {
                onLegacyReparseRequested?.invoke(editState.getEditedBytes()) ?: run { reparseCounter++ }
            },
            onOpenSbomTab = { activeTab = "SBOM" },
            fileTypeLabelOverride = fileTypeLabelOverride,
        )
        Div({
            style {
                display(DisplayStyle.Flex)
                property("flex", "1 1 0")
                property("overflow", "hidden")
                property("min-height", "0")
            }
        }) {
            HexViewerPanel(
                bytes = buffer,
                selection = selection,
                editState = editState,
                structureRanges = remember(provider, initialStructureRanges, showSemanticHighlighting) {
                    if (showSemanticHighlighting) (initialStructureRanges ?: provider.structureRanges).sortedBy { it.offset } else emptyList()
                },
                onTabChangeRequest = { tabName -> activeTab = tabName },
            )
            if (showDataInspector) {
                DataInspectorPanel(bytes = buffer, selection = selection)
            }
        }
        SelectionStatusBar(
            selection = selection,
            totalBytes = buffer.size,
            showDataInspector = showDataInspector,
            onToggleDataInspector = { showDataInspector = !showDataInspector },
        )
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
            capabilities = capabilities,
            scriptTabs = scriptTabs,
            dialogPickedPath = dialogPickedPath,
            dialogPickedContent = dialogPickedContent,
            onDialogPickedPathConsumed = onDialogPickedPathConsumed,
            onScriptTabConfirmed = onScriptTabConfirmed,
            onScriptTabRemoved = onScriptTabRemoved,
        )
    }
}
