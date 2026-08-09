package org.jetbrains.hexana.webapp

import androidx.compose.runtime.*
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.vscode.*
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.files.File

fun main() {
    renderComposable(rootElementId = "root") {
        WebAppRoot()
    }
}

private data class BrowserLoadedFile(
    val fileName: String,
    val bytes: ByteArray,
    val workerResponse: BoundedParserWorkerSuccess?,
    val legacyWasmTab: String?,
)

internal fun interface LegacyWasmProviderFactory {
    fun create(bytes: ByteArray, fileName: String): BinaryProvider
}

internal val defaultLegacyWasmProviderFactory = LegacyWasmProviderFactory { bytes, fileName ->
    val buffer = bytes.toCommonByteBuffer()
    buffer.position(0)
    when (WasmBinaryProviderSelector.select(buffer)) {
        BinaryProviderType.Wasm -> WasmBinaryProvider(buffer)
        BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(buffer)
        else -> nativeOrGenericBinaryProvider(buffer, fileName)
    }
}

@Composable
internal fun WebAppRoot(
    legacyWasmProviderFactory: LegacyWasmProviderFactory = defaultLegacyWasmProviderFactory,
) {
    var wasmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var workerResponse by remember { mutableStateOf<BoundedParserWorkerSuccess?>(null) }
    var legacyWasmTab by remember { mutableStateOf<String?>(null) }
    val navStack = remember { mutableStateListOf<BrowserLoadedFile>() }
    val capabilities = remember { BrowserCapabilityPolicy.createWebUiCapabilities() }

    val scriptTabs = remember { mutableStateListOf<ScriptTabState>() }
    var dialogPickedPath by remember { mutableStateOf("") }
    var dialogPickedContent by remember { mutableStateOf("") }
    var showSemanticHighlighting by remember { mutableStateOf(true) }

    val openModule: (Int, String, ByteArray) -> Unit = { _, newFileName, data ->
        val current = wasmBytes
        if (current != null) {
            navStack.add(BrowserLoadedFile(fileName, current, workerResponse, legacyWasmTab))
        }
        fileName = newFileName
        wasmBytes = data
        workerResponse = null
        // MCP-186 owns nested-module reparsing. This path is entered only after an explicit module-open action.
        legacyWasmTab = "Sections"
    }

    val navigateToParent: () -> Unit = {
        val parent = navStack.removeLastOrNull()
        if (parent != null) {
            fileName = parent.fileName
            wasmBytes = parent.bytes
            workerResponse = parent.workerResponse
            legacyWasmTab = parent.legacyWasmTab
        }
    }

    val onScriptFilePicked: (String, String) -> Unit = { path, content ->
        dialogPickedPath = path
        dialogPickedContent = content
    }
    val onDialogPickedPathConsumed: () -> Unit = {
        dialogPickedPath = ""
        dialogPickedContent = ""
    }

    val host = remember(navStack.size) {
        BrowserHost(
            currentFileName = { fileName },
            currentBytes = { wasmBytes },
            openModule = openModule,
            navigateToParent = navigateToParent,
            onScriptFilePicked = onScriptFilePicked,
            onScriptFileContent = { path, content ->
                val tab = scriptTabs.find { it.path == path }
                tab?.let { it.content = content }
            },
        )
    }
    val onScriptTabConfirmed: (String, String) -> Unit = { path, content ->
        if (scriptTabs.none { it.path == path }) {
            scriptTabs.add(ScriptTabState(path, content))
            host.scriptTabAdded(path)
        }
    }
    val onScriptTabRemoved: (String) -> Unit = { path ->
        scriptTabs.removeAll { it.path == path }
    }

    CompositionLocalProvider(LocalHost provides host) {
        val bytes = wasmBytes
        if (bytes == null) {
            LandingView(onFileLoaded = { name, data, response ->
                fileName = name
                wasmBytes = data
                workerResponse = response
                legacyWasmTab = null
            })
        } else {
            val response = workerResponse
            if (response != null) {
                WorkerBackedWasmContent(
                    fileName = fileName,
                    parentFileName = navStack.lastOrNull()?.fileName,
                    bytes = bytes,
                    response = response,
                    capabilities = capabilities,
                    scriptTabs = scriptTabs,
                    dialogPickedPath = dialogPickedPath,
                    dialogPickedContent = dialogPickedContent,
                    onDialogPickedPathConsumed = onDialogPickedPathConsumed,
                    onScriptTabConfirmed = onScriptTabConfirmed,
                    onScriptTabRemoved = onScriptTabRemoved,
                    legacyWasmTab = legacyWasmTab,
                    onLegacyWasmTabChanged = { tab -> legacyWasmTab = tab },
                    onLegacyWasmReparseRequested = { editedBytes ->
                        wasmBytes = editedBytes
                        workerResponse = null
                        legacyWasmTab = "Sections"
                    },
                    legacyWasmProviderFactory = legacyWasmProviderFactory,
                    showSemanticHighlighting = showSemanticHighlighting,
                )
            } else {
                val provider = remember(bytes, fileName) { legacyWasmProviderFactory.create(bytes, fileName) }
                val extraTabs = remember(provider) { computeExtraTabs(provider.bytes.duplicate().position(0)) }

                val parentFileName = navStack.lastOrNull()?.fileName

                BinaryEditorContent(
                    fileName = fileName,
                    parentFileName = parentFileName,
                    provider = provider,
                    extraTabs = extraTabs,
                    capabilities = capabilities,
                    scriptTabs = scriptTabs,
                    dialogPickedPath = dialogPickedPath,
                    dialogPickedContent = dialogPickedContent,
                    onDialogPickedPathConsumed = onDialogPickedPathConsumed,
                    onScriptTabConfirmed = onScriptTabConfirmed,
                    onScriptTabRemoved = onScriptTabRemoved,
                    showSemanticHighlighting = showSemanticHighlighting,
                    initialActiveTab = legacyWasmTab ?: "Sections",
                )
            }
        }
    }
}

@Composable
internal fun WorkerBackedWasmContent(
    fileName: String,
    parentFileName: String?,
    bytes: ByteArray,
    response: BoundedParserWorkerSuccess,
    capabilities: WebUiCapabilities,
    legacyWasmTab: String?,
    onLegacyWasmTabChanged: (String?) -> Unit,
    onLegacyWasmReparseRequested: (ByteArray) -> Unit,
    legacyWasmProviderFactory: LegacyWasmProviderFactory = defaultLegacyWasmProviderFactory,
    showSemanticHighlighting: Boolean = true,
    scriptTabs: List<ScriptTabState> = emptyList(),
    dialogPickedPath: String = "",
    dialogPickedContent: String = "",
    onDialogPickedPathConsumed: () -> Unit = {},
    onScriptTabConfirmed: (path: String, content: String) -> Unit = { _, _ -> },
    onScriptTabRemoved: (path: String) -> Unit = {},
) {
    if (legacyWasmTab == null) {
        WorkerWasmEditorContent(
            fileName = fileName,
            parentFileName = parentFileName,
            bytes = bytes,
            response = response,
            capabilities = capabilities,
            scriptTabs = scriptTabs,
            dialogPickedPath = dialogPickedPath,
            dialogPickedContent = dialogPickedContent,
            onDialogPickedPathConsumed = onDialogPickedPathConsumed,
            onScriptTabConfirmed = onScriptTabConfirmed,
            onScriptTabRemoved = onScriptTabRemoved,
            onLegacyWasmViewRequested = onLegacyWasmTabChanged,
            onLegacyWasmReparseRequested = onLegacyWasmReparseRequested,
        )
        return
    }

    // MCP-186 owns these explicit legacy paths (WAT, edits, and nested-module reparsing).
    val provider = remember(bytes, fileName, legacyWasmTab) { legacyWasmProviderFactory.create(bytes, fileName) }
    val extraTabs = remember(provider) { computeExtraTabs(provider.bytes.duplicate().position(0)) }
    BinaryEditorContent(
        fileName = fileName,
        parentFileName = parentFileName,
        provider = provider,
        extraTabs = extraTabs,
        capabilities = capabilities,
        scriptTabs = scriptTabs,
        dialogPickedPath = dialogPickedPath,
        dialogPickedContent = dialogPickedContent,
        onDialogPickedPathConsumed = onDialogPickedPathConsumed,
        onScriptTabConfirmed = onScriptTabConfirmed,
        onScriptTabRemoved = onScriptTabRemoved,
        showSemanticHighlighting = showSemanticHighlighting,
        initialActiveTab = legacyWasmTab,
    )
}

@Composable
internal fun LandingView(
    onFileLoaded: (name: String, bytes: ByteArray, response: BoundedParserWorkerSuccess?) -> Unit,
    readerFactory: BrowserFileReaderFactory = domBrowserFileReaderFactory,
    workerFactory: ParserWorkerFactory = browserParserWorkerFactory,
    workerScheduler: ParserWorkerScheduler = browserParserWorkerScheduler,
) {
    var isDragging by remember { mutableStateOf(false) }
    var isReading by remember { mutableStateOf(false) }
    var loadStatus by remember { mutableStateOf(BrowserFileLoadStatus.Ready) }
    var parserState by remember { mutableStateOf<ParserWorkerState>(ParserWorkerState.Ready) }
    var retainedFile by remember { mutableStateOf<File?>(null) }
    var pendingCoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCoreFileName by remember { mutableStateOf("") }
    val currentOnFileLoaded = rememberUpdatedState(onFileLoaded)
    val parserController = remember(workerFactory, workerScheduler) {
        ParserWorkerController(
            workerFactory = workerFactory,
            scheduler = workerScheduler,
            onStateChanged = { state ->
                parserState = state
                if (state is ParserWorkerState.Parsed) {
                    val bytes = pendingCoreBytes ?: return@ParserWorkerController
                    pendingCoreBytes = null
                    currentOnFileLoaded.value(pendingCoreFileName, bytes, state.response)
                } else if (state is ParserWorkerState.TimedOut || state is ParserWorkerState.Cancelled || state is ParserWorkerState.Failed) {
                    // Retry deliberately re-reads retainedFile; do not retain a potentially 50 MiB prior read.
                    pendingCoreBytes = null
                    pendingCoreFileName = ""
                }
            },
        )
    }
    val loader = remember(readerFactory, parserController) {
        BrowserFileLoader(
            readerFactory = readerFactory,
            onStatusChanged = {
                loadStatus = it
                isReading = it == BrowserFileLoadStatus.Reading
            },
            onFileLoaded = { file, bytes ->
                isReading = false
                retainedFile = file
                if (requiresWasmParserWorker(bytes)) {
                    pendingCoreFileName = file.name
                    pendingCoreBytes = bytes
                    // The worker receives its own transferable copy. The UI bytes remain usable after transfer.
                    parserController.parse(copyForParserWorker(bytes), PARSER_WORKER_TIMEOUT_MS)
                } else {
                    pendingCoreBytes = null
                    currentOnFileLoaded.value(file.name, bytes, null)
                }
            },
        )
    }
    DisposableEffect(loader, parserController) {
        onDispose {
            loader.dispose()
            parserController.dispose()
        }
    }

    val selectFile: (File) -> Unit = { file ->
        parserController.cancel()
        // A fresh read replaces a terminal parser state; Retry must not remain clickable while it is in flight.
        parserState = ParserWorkerState.Ready
        loader.select(file)
    }

    Div({
        attr("data-file-drop-zone", "true")
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("align-items", "center")
            property("justify-content", "center")
            width(100.percent)
            height(100.percent)
            property("gap", "16px")
            property("background", "var(--vscode-editor-background, #1e1e1e)")
            if (isDragging) property("outline", "2px dashed var(--vscode-focusBorder, #007fd4)")
        }
        onDragOver { event ->
            event.preventDefault()
            isDragging = true
        }
        onDragLeave { isDragging = false }
        onDrop { event ->
            event.preventDefault()
            isDragging = false
            val file = event.dataTransfer?.files?.item(0)
            if (file == null) {
                loader.cancelPickerSelection()
                return@onDrop
            }
            selectFile(file)
        }
    }) {
        Span({
            style {
                property("font-size", "48px")
                property("opacity", "0.3")
            }
        }) { Text("🔍") }

        Span({
            style {
                property("font-size", "18px")
                property("font-weight", "600")
                property("opacity", "0.7")
            }
        }) { Text("Hexana Binary Inspector") }

        Span({
            style {
                property("font-size", "13px")
                property("opacity", "0.5")
            }
        }) { Text("Drop a binary file here, or click to open") }

        Button({
            style {
                property("padding", "8px 20px")
                property("font-size", "13px")
                property("border-radius", "4px")
                property("border", "none")
                property("cursor", "pointer")
                property("background", "var(--vscode-button-background, #0e639c)")
                property("color", "var(--vscode-button-foreground, #fff)")
            }
            onClick {
                openFilePicker { file ->
                    if (file == null) loader.cancelPickerSelection()
                    else selectFile(file)
                }
            }
        }) { Text("Open file…") }

        Span({
            attr("role", "status")
            attr("aria-live", "polite")
            attr("data-file-load-status", loadStatus.name)
            style {
                property("font-size", "12px")
                property("min-height", "18px")
                property("opacity", if (loadStatus.isError) "0.9" else "0.6")
                if (loadStatus.isError) {
                    property("color", "var(--vscode-inputValidation-errorForeground, #f48771)")
                }
            }
        }) { Text(loadStatus.message) }

        val parserMessage = when (val state = parserState) {
            ParserWorkerState.Ready -> null
            is ParserWorkerState.Parsing -> "Parsing…"
            is ParserWorkerState.Parsed -> null
            ParserWorkerState.TimedOut -> "Timed out while parsing."
            ParserWorkerState.Cancelled -> "Cancelled."
            is ParserWorkerState.Failed -> "Failed to parse the core WebAssembly module."
        }
        if (parserMessage != null) {
            Span({
                attr("role", "status")
                attr("aria-live", "polite")
                attr("data-parser-worker-state", parserMessage)
                style {
                    property("font-size", "12px")
                    property("min-height", "18px")
                    property("opacity", "0.9")
                    if (parserState is ParserWorkerState.Failed || parserState == ParserWorkerState.TimedOut) {
                        property("color", "var(--vscode-inputValidation-errorForeground, #f48771)")
                    }
                }
            }) { Text(parserMessage) }
        }
        if (parserState is ParserWorkerState.Parsing) {
            Button({
                attr("data-parser-worker-action", "cancel")
                onClick { parserController.cancel() }
            }) { Text("Cancel") }
        } else if (!isReading && retainedFile != null && (parserState == ParserWorkerState.TimedOut || parserState == ParserWorkerState.Cancelled || parserState is ParserWorkerState.Failed)) {
            Button({
                attr("data-parser-worker-action", "retry")
                onClick { selectFile(requireNotNull(retainedFile)) }
            }) { Text("Retry") }
        }

        Span({
            style {
                property("font-size", "11px")
                property("opacity", "0.35")
            }
        }) { Text("WASM, ELF, Mach-O, PE, class files, ZIP, DEX and more") }
    }
}

/**
 * The DedicatedWorker owns the initial core-Wasm and component-model analysis path.
 */
internal fun requiresWasmParserWorker(bytes: ByteArray): Boolean =
    bytes.size >= 8 && bytes[0] == 0.toByte() && bytes[1] == 0x61.toByte() && bytes[2] == 0x73.toByte() &&
        bytes[3] == 0x6d.toByte() && bytes[5] == 0.toByte() &&
        (bytes[4] == 1.toByte() || bytes[4] == 0x0d.toByte())

/** Copies UI-owned bytes before transfer; transferring this buffer detaches only the worker copy. */
internal fun copyForParserWorker(bytes: ByteArray): ArrayBuffer =
    bytes.copyOf().unsafeCast<Int8Array>().buffer
