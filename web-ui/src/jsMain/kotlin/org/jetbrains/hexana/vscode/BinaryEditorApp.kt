package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.shared.PROBLEMS_TAB_NAME
import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.javabytecode.JavaClassFileParser
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.pcap.SslKeyLog
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmFeatureCatalog
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility
import org.jetbrains.hexana.shared.features.parseRuntimeTargets
import org.jetbrains.hexana.shared.features.serializeRuntimeTargets
import org.jetbrains.hexana.shared.features.serializeRuntimeTargetsSchema

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
            return provider.submoduleBytes(module.contentOffset, module.contentSize)
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
 * Holds the state for a diff view: the two binary payloads, their names, and the view the
 * diff opens on. For the size and functions diff the format (core Wasm, component, native) is sniffed
 * from the bytes by [BinaryDiffView].
 */
class DiffPayload(
    val leftData: ByteArray,
    val leftName: String,
    val rightData: ByteArray,
    val rightName: String,
    val mode: DiffViewMode = DiffViewMode.HEX,
)

/**
 * Extra tabs for formats that have a dedicated view rather than a table.
 *
 * [provider] is optional but worth passing: for a DEX it is a
 * [DexBinaryProvider] that has already parsed the file, so handing it over
 * avoids decoding the string table a second time. The DWARF tabs need it outright - they read
 * custom sections off an already-parsed wasm module and are skipped without one.
 */
fun computeExtraTabs(
    buffer: org.jetbrains.hexana.io.CommonByteBuffer,
    provider: BinaryProvider? = null,
): List<PanelTab> {
    if (ClassFileBinaryProvider.isClassFile(buffer)) {
        val classFile = try {
            JavaClassFileParser.parse(buffer.duplicate().position(0))
        } catch (_: Exception) {
            null
        }
        return if (classFile != null) classFileExtraTabs(classFile) else emptyList()
    }
    if (DexBinaryProvider.isDexFile(buffer)) {
        val dex = (provider as? DexBinaryProvider)?.parsedDexFile
            ?: try {
                DexFileParser.parse(buffer.duplicate().position(0))
            } catch (_: Exception) {
                null
            }
        return if (dex != null) dexSmaliExtraTabs(dex, buffer) else emptyList()
    }
    return dwarfExtraTabs(provider ?: return emptyList())
}

private const val MIN_PANEL_HEIGHT = 80.0
private const val DEFAULT_PANEL_HEIGHT = 250.0

@Composable
fun BinaryEditorContent(
    fileName: String,
    /**
     * Path of the open file, or blank when it has none — a nested module
     * extracted from a parent, or a payload the host only pushed bytes for.
     * Script tabs surface it as `binary.path`.
     */
    filePath: String = "",
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
    workerWasmToolbarPresentation: WorkerWasmToolbarPresentation? = null,
    workerNativeImageToolbarPresentation: WorkerNativeImageToolbarPresentation? = null,
    workerSizeTooltip: List<Pair<String, String>>? = null,
    scriptExecution: ScriptExecution? = null,
    isDecompressed: Boolean = false,
    /** Text of a key log the host just picked, or blank. */
    sslKeyLogContent: String = "",
    onSslKeyLogConsumed: () -> Unit = {},
    /**
     * Raw `features.json` pushed by the host (its ETag-validated cache, or the snapshot it
     * ships). Blank falls back to the snapshot committed in `plugins-shared`.
     */
    featureCatalogJson: String = "",
    /** Raw `.hexana/runtimes.json` pushed by the host; blank means nothing is declared. */
    runtimeTargetsJson: String = "",
    /**
     * Bumped by the host to open the target-runtimes dialog from outside the editor (the
     * `hexana.configureWasmRuntimes` command). A counter rather than a boolean so repeated
     * invocations reopen it without the caller having to reset a flag.
     */
    openRuntimeTargetsRequest: Int = 0,
) {
    val selection = remember { HexSelectionState() }
    val buffer = remember(provider) { provider.bytes }

    val host = LocalHost.current

    // Parsed once per pushed payload rather than per composition: features.json is ~40 KB.
    val featureCatalog = remember(featureCatalogJson) {
        featureCatalogJson.takeIf { it.isNotBlank() }?.let(WasmFeatureCatalog::parse)
            ?: WasmFeatureCatalog.bundled
    }
    val runtimeTargets = remember(runtimeTargetsJson) {
        if (runtimeTargetsJson.isBlank()) RuntimeTargets.EMPTY else parseRuntimeTargets(runtimeTargetsJson)
    }
    // Only built where the host can persist the file; the browser webapp cannot, and the
    // proposals badges and the Compatibility tab then behave as they always have.
    val runtimeCompatibility = remember(featureCatalog, runtimeTargets, host.canEditRuntimeTargets) {
        if (host.canEditRuntimeTargets) WasmRuntimeCompatibility(featureCatalog, runtimeTargets) else null
    }
    var showRuntimeTargetsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(openRuntimeTargetsRequest) {
        if (openRuntimeTargetsRequest > 0 && host.canEditRuntimeTargets) {
            showRuntimeTargetsDialog = true
        }
    }
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

    // PcapBinaryProvider.sslKeyLog is a plain `var`, invisible to Compose, so a
    // counter carries the change into the tab list's remember key. Installing
    // the key log here rather than in the toolbar matters: the toolbar gets
    // `provider` while HexanaPanel gets `liveProvider`, and the tab that has to
    // see the keys is the panel's.
    var sslKeyLogGeneration by remember(provider) { mutableStateOf(0) }
    var sslKeyLogSessionCount by remember(provider) { mutableStateOf(0) }
    LaunchedEffect(provider, sslKeyLogContent) {
        if (sslKeyLogContent.isBlank()) return@LaunchedEffect
        val pcap = provider as? PcapBinaryProvider
        if (pcap != null) {
            val keyLog = SslKeyLog.parse(sslKeyLogContent)
            if (keyLog.isNotEmpty) {
                pcap.sslKeyLog = keyLog
                sslKeyLogSessionCount = keyLog.sessionCount
                sslKeyLogGeneration++
            }
        }
        onSslKeyLogConsumed()
    }

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

    // `selection` is deliberately unkeyed - the cursor survives a reparse - but
    // detail bytes belong to the provider they were dissected from, and this one
    // app instance is reused for whatever the host opens next: a nested module,
    // or the same file reparsed after an edit. Neither must leave the panel
    // showing plaintext from a binary that is no longer on screen.
    LaunchedEffect(liveProvider) {
        selection.rowDetail = null
        selection.showRowDetail = false
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
            runtimeCompatibility = runtimeCompatibility,
            onOpenCompatibilityTab = if (runtimeCompatibility != null) {
                { activeTab = "Compatibility" }
            } else {
                null
            },
            // A worker presentation means the binary was parsed off the UI thread; counting
            // problems here would parse it again on this one.
            onOpenProblemsTab = if (workerWasmToolbarPresentation == null && workerNativeImageToolbarPresentation == null) {
                { activeTab = PROBLEMS_TAB_NAME }
            } else {
                null
            },
            fileTypeLabelOverride = fileTypeLabelOverride,
            workerWasmToolbarPresentation = workerWasmToolbarPresentation,
            workerNativeImageToolbarPresentation = workerNativeImageToolbarPresentation,
            workerSizeTooltip = workerSizeTooltip,
            isDecompressed = isDecompressed,
            sslKeyLogSessionCount = sslKeyLogSessionCount,
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
            // A second, independent column: the row detail panel shows bytes
            // that are not in the file, so it has nothing to do with the
            // inspector's view of the cursor and neither one closes the other.
            if (selection.showRowDetail) {
                RowDetailPanel(selection = selection)
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
            filePath = filePath,
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
            scriptExecution = scriptExecution,
            sslKeyLogGeneration = sslKeyLogGeneration,
            runtimeCompatibility = runtimeCompatibility,
            onConfigureRuntimes = if (host.canEditRuntimeTargets) {
                { showRuntimeTargetsDialog = true }
            } else {
                null
            },
        )
    }

    if (showRuntimeTargetsDialog) {
        RuntimeTargetsDialog(
            catalog = featureCatalog,
            current = runtimeTargets,
            onConfirm = { targets ->
                // The host owns the write and replies with `runtime-targets-loaded`, which is
                // also how an edit made outside the editor reaches this state.
                // The schema is generated from the same catalog the dialog offered, so the
                // names it completes on a later hand-edit match what was picked here.
                host.saveRuntimeTargets(
                    serializeRuntimeTargets(targets),
                    serializeRuntimeTargetsSchema(featureCatalog),
                )
                showRuntimeTargetsDialog = false
            },
            onCancel = { showRuntimeTargetsDialog = false },
        )
    }
}
