package org.jetbrains.hexana.webapp

import androidx.compose.runtime.*
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.vscode.*

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
) {
    var isDragging by remember { mutableStateOf(false) }
    var loadStatus by remember { mutableStateOf(BrowserFileLoadStatus.Ready) }
    val currentOnFileLoaded = rememberUpdatedState(onFileLoaded)
    val loader = remember(readerFactory) {
        BrowserFileLoader(
            readerFactory = readerFactory,
            onStatusChanged = { loadStatus = it },
            onFileLoaded = { name, bytes -> currentOnFileLoaded.value(name, bytes) },
        )
    }
    DisposableEffect(loader) {
        onDispose { loader.dispose() }
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
            loader.select(file)
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
                    else loader.select(file)
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

        Span({
            style {
                property("font-size", "11px")
                property("opacity", "0.35")
            }
        }) { Text("WASM, ELF, Mach-O, PE, class files, ZIP, DEX and more") }
    }
}