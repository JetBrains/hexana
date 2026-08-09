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

@Composable
fun WebAppRoot() {
    var wasmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    // Navigation stack: each entry is (fileName, bytes) for parent modules.
    val navStack = remember { mutableStateListOf<Pair<String, ByteArray>>() }

    val scriptTabs = remember { mutableStateListOf<ScriptTabState>() }
    var dialogPickedPath by remember { mutableStateOf("") }
    var dialogPickedContent by remember { mutableStateOf("") }
    var showSemanticHighlighting by remember { mutableStateOf(true) }

    val openModule: (Int, String, ByteArray) -> Unit = { _, newFileName, data ->
        val current = wasmBytes
        if (current != null) {
            navStack.add(fileName to current)
        }
        fileName = newFileName
        wasmBytes = data
    }

    val navigateToParent: () -> Unit = {
        val parent = navStack.removeLastOrNull()
        if (parent != null) {
            fileName = parent.first
            wasmBytes = parent.second
        }
    }

    val onScriptFilePicked: (String, String) -> Unit = { path, content ->
        dialogPickedPath = path
        dialogPickedContent = content
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

    CompositionLocalProvider(LocalHost provides host) {
        val bytes = wasmBytes
        if (bytes == null) {
            LandingView(onFileLoaded = { name, data ->
                fileName = name
                wasmBytes = data
            })
        } else {
            val buffer = bytes.toCommonByteBuffer()
            buffer.position(0)

            val provider = remember(buffer, fileName) {
                when (WasmBinaryProviderSelector.select(buffer)) {
                    BinaryProviderType.Wasm -> WasmBinaryProvider(buffer)
                    BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(buffer)
                    else -> nativeOrGenericBinaryProvider(buffer, fileName)
                }
            }

            val extraTabs = remember(buffer) { computeExtraTabs(buffer) }

            val parentFileName = navStack.lastOrNull()?.first

            BinaryEditorContent(
                fileName = fileName,
                parentFileName = parentFileName,
                provider = provider,
                extraTabs = extraTabs,
                scriptTabs = scriptTabs,
                showSemanticHighlighting = showSemanticHighlighting,
                dialogPickedPath = dialogPickedPath,
                dialogPickedContent = dialogPickedContent,
                onDialogPickedPathConsumed = {
                    dialogPickedPath = ""
                    dialogPickedContent = ""
                },
                onScriptTabConfirmed = { path, content ->
                    if (scriptTabs.none { it.path == path }) {
                        scriptTabs.add(ScriptTabState(path, content))
                        host.scriptTabAdded(path)
                    }
                },
                onScriptTabRemoved = { path ->
                    scriptTabs.removeAll { it.path == path }
                },
            )
        }
    }
}

@Composable
internal fun LandingView(
    onFileLoaded: (name: String, bytes: ByteArray) -> Unit,
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
                    currentOnFileLoaded.value(pendingCoreFileName, bytes)
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
                if (requiresCoreParserWorker(bytes)) {
                    pendingCoreFileName = file.name
                    pendingCoreBytes = bytes
                    // The worker receives its own transferable copy. The UI bytes remain usable after transfer.
                    parserController.parse(copyForParserWorker(bytes), PARSER_WORKER_TIMEOUT_MS)
                } else {
                    pendingCoreBytes = null
                    // Components and other formats keep their existing main-thread path (MCP-184+).
                    currentOnFileLoaded.value(file.name, bytes)
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
 * Only core Wasm is gated in MCP-182. Provider construction remains on the main thread after this
 * validation boundary; moving provider/lazy UI analysis is explicitly deferred to MCP-184 and later.
 */
internal fun requiresCoreParserWorker(bytes: ByteArray): Boolean =
    bytes.size >= 8 && bytes[0] == 0.toByte() && bytes[1] == 0x61.toByte() && bytes[2] == 0x73.toByte() &&
        bytes[3] == 0x6d.toByte() && bytes[4] == 1.toByte() && bytes[5] == 0.toByte()

/** Copies UI-owned bytes before transfer; transferring this buffer detaches only the worker copy. */
internal fun copyForParserWorker(bytes: ByteArray): ArrayBuffer =
    bytes.copyOf().unsafeCast<Int8Array>().buffer
