package org.jetbrains.hexana.webapp

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.vscode.*
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.files.File
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

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
    createBinaryProvider(bytes.toCommonByteBuffer(), fileName)
}

@Composable
internal fun WebAppRoot(
    legacyWasmProviderFactory: LegacyWasmProviderFactory = defaultLegacyWasmProviderFactory,
    telemetryFactory: WebAppTelemetryFactory = browserWebAppTelemetryFactory,
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
    val telemetry = remember { telemetryFactory.create() }
    var telemetryEnabled by remember { mutableStateOf(telemetry.isEnabled) }

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
            onTabSelected = telemetry::captureTabSelected,
        )
    }
    DisposableEffect(host) {
        onDispose(host::dispose)
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
            LandingView(
                onFileLoaded = { name, data, response ->
                    fileName = name
                    wasmBytes = data
                    workerResponse = response
                    legacyWasmTab = null
                    telemetry.captureBinaryOpened(data.size, response?.presentation?.format)
                },
                telemetryEnabled = telemetryEnabled,
                onTelemetryEnabledChange = { enabled ->
                    telemetry.setEnabled(enabled)
                    telemetryEnabled = enabled
                },
                releaseInfo = bundledWebappReleaseInfo(),
            )
        } else {
            val response = workerResponse
            if (response != null) {
                Span({
                    attr("data-parser-worker-state", "parsed")
                    attr("data-parser-worker-format", response.presentation.format)
                    attr("hidden", "")
                })
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
                // Malformed nested-module or post-edit bytes can throw here; this runs on the
                // main thread inside `remember`, so an uncaught throw would crash the whole tab.
                val provider = remember(bytes, fileName) {
                    try {
                        legacyWasmProviderFactory.create(bytes, fileName)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (provider == null) {
                    LegacyParseFailureNotice(
                        fileName = fileName,
                        onBack = {
                            if (navStack.isNotEmpty()) {
                                navigateToParent()
                            } else {
                                fileName = ""
                                wasmBytes = null
                            }
                        },
                    )
                } else {
                    val extraTabs = remember(provider) { computeExtraTabs(provider.bytes.duplicate().position(0), provider) }

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
}

@Composable
internal fun TelemetryConsentControl(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Button({
        attr("data-telemetry-consent-toggle", "true")
        attr("aria-label", if (enabled) "Disable anonymous usage analytics" else "Enable anonymous usage analytics")
        attr("aria-pressed", enabled.toString())
        style {
            property("margin-top", "12px")
            property("padding", "0")
            property("border", "none")
            property("background", "transparent")
            property("color", "var(--vscode-textLink-foreground, #3794ff)")
            property("font-size", "11px")
            property("cursor", "pointer")
        }
        onClick { onEnabledChange(!enabled) }
    }) { Text("Anonymous usage analytics: ${if (enabled) "On — turn off" else "Off — turn on"}") }
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
    // Construction runs on the main thread inside `remember`; catch so a malformed
    // module cannot crash the tab the way an uncaught throw out of composition would.
    val provider = remember(bytes, fileName, legacyWasmTab) {
        try {
            legacyWasmProviderFactory.create(bytes, fileName)
        } catch (_: Throwable) {
            null
        }
    }
    if (provider == null) {
        LegacyParseFailureNotice(
            fileName = fileName,
            onBack = { onLegacyWasmTabChanged(null) },
        )
        return
    }
    val extraTabs = remember(provider) { computeExtraTabs(provider.bytes.duplicate().position(0), provider) }
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

/**
 * Shown in place of [BinaryEditorContent] when the main-thread legacy provider
 * construction throws. Mirrors the worker path's `ParserWorkerState.Failed`
 * presentation (same `data-parser-worker-state` marker and error styling) so a
 * malformed nested module or post-edit reparse degrades to a visible, recoverable
 * message instead of crashing the tab.
 */
@Composable
private fun LegacyParseFailureNotice(fileName: String, onBack: () -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("align-items", "center")
            property("justify-content", "center")
            width(100.percent)
            height(100.percent)
            property("gap", "12px")
        }
    }) {
        Span({
            attr("role", "status")
            attr("aria-live", "polite")
            attr("data-parser-worker-state", "failed")
            style {
                property("font-size", "13px")
                property("color", "var(--vscode-inputValidation-errorForeground, #f48771)")
            }
        }) { Text("Failed to parse \"$fileName\".") }
        Button({
            attr("data-parser-worker-action", "back")
            onClick { onBack() }
        }) { Text("Back") }
    }
}

@Composable
internal fun LandingView(
    onFileLoaded: (name: String, bytes: ByteArray, response: BoundedParserWorkerSuccess?) -> Unit,
    telemetryEnabled: Boolean = true,
    onTelemetryEnabledChange: (Boolean) -> Unit = {},
    readerFactory: BrowserFileReaderFactory = domBrowserFileReaderFactory,
    workerFactory: ParserWorkerFactory = browserParserWorkerFactory,
    workerScheduler: ParserWorkerScheduler = browserParserWorkerScheduler,
    releaseInfo: WebappReleaseInfo? = null,
) {
    var isDragging by remember { mutableStateOf(false) }
    var isReading by remember { mutableStateOf(false) }
    var loadStatus by remember { mutableStateOf(BrowserFileLoadStatus.Ready) }
    var parserState by remember { mutableStateOf<ParserWorkerState>(ParserWorkerState.Ready) }
    var retainedFile by remember { mutableStateOf<File?>(null) }
    var pendingCoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCoreFileName by remember { mutableStateOf("") }
    var releaseNotesOpen by remember { mutableStateOf(false) }
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
                pendingCoreFileName = file.name
                pendingCoreBytes = bytes
                // The worker receives its own transferable copy. The UI bytes remain usable after transfer.
                parserController.parse(copyForParserWorker(bytes), PARSER_WORKER_TIMEOUT_MS, file.name)
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
            is ParserWorkerState.Failed -> "Failed to parse the file in the Worker."
        }
        val parserStateName = when (parserState) {
            ParserWorkerState.Ready -> "ready"
            is ParserWorkerState.Parsing -> "parsing"
            is ParserWorkerState.Parsed -> "parsed"
            ParserWorkerState.TimedOut -> "timed_out"
            ParserWorkerState.Cancelled -> "cancelled"
            is ParserWorkerState.Failed -> "failed"
        }
        if (parserMessage != null) {
            Span({
                attr("role", "status")
                attr("aria-live", "polite")
                attr("data-parser-worker-state", parserStateName)
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

        releaseInfo?.let { info ->
            ReleaseNotesControl(
                info = info,
                open = releaseNotesOpen,
                onOpenChange = { releaseNotesOpen = it },
            )
        }

        TelemetryConsentControl(
            enabled = telemetryEnabled,
            onEnabledChange = onTelemetryEnabledChange,
        )
    }
}

@Composable
private fun ReleaseNotesControl(
    info: WebappReleaseInfo,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
) {
    var triggerElement by remember { mutableStateOf<HTMLElement?>(null) }
    var drawerElement by remember { mutableStateOf<HTMLElement?>(null) }
    var drawerEntered by remember { mutableStateOf(false) }
    val currentTriggerElement = rememberUpdatedState(triggerElement)
    val currentDrawerElement = rememberUpdatedState(drawerElement)
    val closeAndFocusTrigger: () -> Unit = {
        onOpenChange(false)
        window.requestAnimationFrame { currentTriggerElement.value?.focus() }
    }
    val closeOnKeyboard: (org.jetbrains.compose.web.events.SyntheticKeyboardEvent) -> Unit = { event ->
        val keyboard = event.nativeEvent as? KeyboardEvent
        when (keyboard?.key) {
            "Escape" -> {
                keyboard.preventDefault()
                closeAndFocusTrigger()
            }
            "Tab" -> {
                currentDrawerElement.value?.let { drawer -> trapDrawerFocus(drawer, keyboard) }
            }
        }
    }
    DisposableEffect(open) {
        val body = document.body
        val previousOverflow = body?.style?.getPropertyValue("overflow")
        if (open) {
            body?.style?.setProperty("overflow", "hidden")
            window.requestAnimationFrame { drawerEntered = true }
        } else {
            drawerEntered = false
        }
        onDispose {
            if (open) body?.style?.setProperty("overflow", previousOverflow ?: "")
        }
    }
    Div({ attr("data-release-notes", "true") }) {
        Button({
            id("hexana-release-notes-trigger")
            attr("data-release-notes-trigger", "true")
            attr("aria-label", "Release notes for Hexana ${info.version}")
            attr("aria-expanded", open.toString())
            attr("aria-controls", "hexana-release-notes-drawer")
            ref { element ->
                triggerElement = element
                onDispose {
                    if (currentTriggerElement.value === element) triggerElement = null
                }
            }
            onClick { onOpenChange(!open) }
            onKeyDown { event ->
                val keyboard = event.nativeEvent as? KeyboardEvent
                when (keyboard?.key) {
                    "Enter", " " -> {
                        keyboard.preventDefault()
                        onOpenChange(!open)
                    }
                    "Escape" -> {
                        keyboard.preventDefault()
                        closeAndFocusTrigger()
                    }
                }
            }
            style {
                property("padding", "8px 12px")
                property("border", "1px solid var(--vscode-button-secondaryBorder, #6b6b6b)")
                property("border-radius", "4px")
                property("background", "transparent")
                property("color", "var(--vscode-button-secondaryForeground, #ffffff)")
                property("font-size", "12px")
                property("font-weight", "600")
                property("cursor", "pointer")
            }
        }) { Text("Release notes") }
        if (open) {
            Div({
                attr("data-release-notes-overlay", "true")
                attr("aria-hidden", "false")
                style {
                    property("position", "fixed")
                    property("inset", "0")
                    property("z-index", "1000")
                    property("display", "flex")
                    property("justify-content", "flex-end")
                    property("isolation", "isolate")
                }
            }) {
                Div({
                    attr("data-release-notes-backdrop", "true")
                    attr("aria-hidden", "true")
                    onClick { closeAndFocusTrigger() }
                    style {
                        property("position", "absolute")
                        property("inset", "0")
                        property("background", "rgba(0, 0, 0, 0.58)")
                    }
                })
                Div({
                id("hexana-release-notes-drawer")
                attr("data-release-notes-drawer", "true")
                attr("role", "dialog")
                attr("aria-modal", "true")
                attr("aria-labelledby", "hexana-release-notes-title")
                attr("tabindex", "-1")
                ref { element ->
                    drawerElement = element
                    window.requestAnimationFrame {
                        element.querySelector("[data-release-notes-close]")?.unsafeCast<HTMLElement>()?.focus()
                    }
                    onDispose {
                        if (currentDrawerElement.value === element) drawerElement = null
                    }
                }
                onKeyDown(closeOnKeyboard)
                style {
                    property("position", "relative")
                    property("width", "min(560px, 100vw)")
                    property("height", "100dvh")
                    property("display", "flex")
                    property("flex-direction", "column")
                    property("background", "var(--vscode-editorWidget-background, #252526)")
                    property("border-left", "1px solid var(--vscode-editorWidget-border, #454545)")
                    property("box-shadow", "-16px 0 36px rgba(0, 0, 0, 0.38)")
                    property("transform", if (drawerEntered) "translateX(0)" else "translateX(100%)")
                    property("transition", "transform 180ms ease-out")
                }
            }) {
                Div({
                    attr("data-release-notes-header", "true")
                    style {
                        property("display", "flex")
                        property("align-items", "flex-start")
                        property("justify-content", "space-between")
                        property("gap", "16px")
                        property("padding", "24px 24px 18px")
                        property("border-bottom", "1px solid var(--vscode-editorWidget-border, #454545)")
                    }
                }) {
                    Div {
                        H2({
                            id("hexana-release-notes-title")
                            attr("data-release-notes-title", "true")
                            style { property("margin", "0 0 8px") }
                        }) { Text("Release notes") }
                        Div({ attr("data-release-notes-version", "true") }) { Text("Version ${info.version}") }
                        Div({
                            attr("data-release-notes-revision", "true")
                            style { property("font-size", "12px"); property("opacity", "0.7") }
                        }) { Text("Revision ${info.revision}") }
                        Div({ style { property("margin-top", "12px") } }) {
                            ReleaseNotesExternalLink("github", info.repositoryUrl, "GitHub")
                            Text(" · ")
                            ReleaseNotesExternalLink("website", info.websiteUrl, "Hexana website")
                        }
                    }
                    Button({
                        attr("data-release-notes-close", "true")
                        attr("aria-label", "Close release notes")
                        onClick { closeAndFocusTrigger() }
                        style {
                            property("padding", "6px 9px")
                            property("border", "1px solid var(--vscode-button-secondaryBorder, #6b6b6b)")
                            property("border-radius", "4px")
                            property("background", "transparent")
                            property("color", "var(--vscode-button-secondaryForeground, #ffffff)")
                            property("cursor", "pointer")
                        }
                    }) { Text("Close") }
                }
                Div({
                    attr("data-release-notes-body", "true")
                    style {
                        property("flex", "1")
                        property("min-height", "0")
                        property("overflow-y", "auto")
                        property("overscroll-behavior", "contain")
                        property("padding", "24px")
                        property("line-height", "1.55")
                    }
                }) {
                    StructuredChangelog(info.changelog)
                }
            }
            }
        }
    }
}

@Composable
private fun ReleaseNotesExternalLink(kind: String, href: String, label: String) {
    A(href = href, {
        attr("data-release-notes-link", kind)
        attr("target", "_blank")
        attr("rel", "noopener noreferrer")
    }) { Text(label) }
}

private sealed interface ChangelogBlock {
    data class Heading(val level: Int, val text: String) : ChangelogBlock
    data class Paragraph(val text: String) : ChangelogBlock
    data class Bullets(val items: List<String>) : ChangelogBlock
}

private fun parseChangelog(changelog: String): List<ChangelogBlock> {
    val blocks = mutableListOf<ChangelogBlock>()
    val paragraph = mutableListOf<String>()
    val bullets = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += ChangelogBlock.Paragraph(paragraph.joinToString(" "))
            paragraph.clear()
        }
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            blocks += ChangelogBlock.Bullets(bullets.toList())
            bullets.clear()
        }
    }
    changelog.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> {
                flushParagraph()
                flushBullets()
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flushParagraph()
                bullets += line.drop(2).trim()
            }
            line.startsWith("#") -> {
                val markerLength = line.takeWhile { it == '#' }.length
                if (markerLength in 1..6 && line.getOrNull(markerLength) == ' ') {
                    flushParagraph()
                    flushBullets()
                    blocks += ChangelogBlock.Heading(markerLength, line.drop(markerLength + 1).trim())
                } else {
                    flushBullets()
                    paragraph += line
                }
            }
            else -> {
                flushBullets()
                paragraph += line
            }
        }
    }
    flushParagraph()
    flushBullets()
    return blocks
}

@Composable
private fun StructuredChangelog(changelog: String) {
    var inReleaseHistory = false
    var suppressCurrentRelease = false
    val blocks = parseChangelog(changelog)
    blocks.forEachIndexed { index, block ->
        if (block is ChangelogBlock.Heading && block.level == 2) {
            inReleaseHistory = true
            val nextRelease = (index + 1 until blocks.size).firstOrNull {
                blocks[it] is ChangelogBlock.Heading && (blocks[it] as ChangelogBlock.Heading).level == 2
            } ?: blocks.size
            suppressCurrentRelease = block.text == "[Unreleased]" && blocks.subList(index + 1, nextRelease).isEmpty()
        }
        if (!inReleaseHistory || suppressCurrentRelease) return@forEachIndexed
        when (block) {
            is ChangelogBlock.Heading -> when (block.level) {
                2 -> ReleaseNotesReleaseHeading(block.text)
                else -> H5({
                    attr("data-release-notes-category", "true")
                    style { property("margin", "18px 0 8px") }
                }) { ChangelogInlineText(block.text) }
            }
            is ChangelogBlock.Paragraph -> P({
                attr("data-release-notes-paragraph", "true")
                style { property("margin", "0 0 12px") }
            }) { ChangelogInlineText(block.text) }
            is ChangelogBlock.Bullets -> Ul({
                attr("data-release-notes-list", "true")
                style { property("margin", "0 0 14px"); property("padding-left", "22px") }
            }) {
                block.items.forEach { item ->
                    Li { ChangelogInlineText(item) }
                }
            }
        }
    }
}

private val datedReleaseHeading = Regex("""^\[?(.+?)\]?\s+-\s+(\d{4}-\d{2}-\d{2})$""")

@Composable
private fun ReleaseNotesReleaseHeading(text: String) {
    val match = datedReleaseHeading.matchEntire(text)
    val version = match?.groupValues?.get(1) ?: text
    val date = match?.groupValues?.get(2)
    Hr({
        attr("data-release-notes-release-separator", "true")
        attr("role", "separator")
        style {
            property("margin", "30px 0 18px")
            property("border", "0")
            property("border-top", "1px solid var(--vscode-editorWidget-border, #454545)")
        }
    })
    Div({
        attr("data-release-notes-release", "true")
        attr("aria-label", date?.let { "$version, released $it" } ?: version)
        style {
            property("display", "flex")
            property("align-items", "baseline")
            property("justify-content", "space-between")
            property("gap", "12px")
            property("flex-wrap", "wrap")
        }
    }) {
        H3({
            attr("data-release-notes-release-version", "true")
            style {
                property("margin", "0")
                property("font-size", "18px")
                property("letter-spacing", "-0.01em")
            }
        }) { ChangelogInlineText(version) }
        date?.let {
            Span({
                attr("data-release-notes-release-date", "true")
                attr("aria-label", "Released $it")
                style {
                    property("padding", "3px 8px")
                    property("border-radius", "999px")
                    property("background", "var(--vscode-badge-background, #4d4d4d)")
                    property("color", "var(--vscode-badge-foreground, #ffffff)")
                    property("font-size", "12px")
                    property("font-weight", "600")
                    property("white-space", "nowrap")
                }
            }) { Text(it) }
        }
    }
}

@Composable
private fun ChangelogInlineText(text: String) {
    val linkPattern = Regex("""\[(.+?)\x5D\(([^)\s]+)\)""")
    var consumed = 0
    linkPattern.findAll(text).forEach { match ->
        if (match.range.first > consumed) Text(text.substring(consumed, match.range.first))
        val label = match.groupValues[1]
        val href = match.groupValues[2].trim()
        if (isSafeChangelogLink(href)) {
            A(href = href, {
                attr("data-release-notes-changelog-link", "true")
                attr("target", "_blank")
                attr("rel", "noopener noreferrer")
            }) { Text(label) }
        } else {
            Span({ attr("data-release-notes-unsafe-link", "true") }) { Text(label) }
        }
        consumed = match.range.last + 1
    }
    if (consumed < text.length) Text(text.substring(consumed))
}

private fun isSafeChangelogLink(href: String): Boolean {
    val normalized = href.lowercase()
    return !href.any(Char::isWhitespace) && (normalized.startsWith("https://") || normalized.startsWith("http://"))
}

private fun trapDrawerFocus(drawer: HTMLElement, event: KeyboardEvent) {
    val focusable = drawer.querySelectorAll("a[href], button:not([disabled])")
    if (focusable.length == 0) return
    val first = focusable.item(0).unsafeCast<HTMLElement>()
    val last = focusable.item(focusable.length - 1).unsafeCast<HTMLElement>()
    val active = document.activeElement
    if (event.shiftKey && (active === first || active === drawer)) {
        event.preventDefault()
        last.focus()
    } else if (!event.shiftKey && (active === last || active === drawer)) {
        event.preventDefault()
        first.focus()
    }
}

/** Copies UI-owned bytes before transfer; transferring this buffer detaches only the worker copy. */
internal fun copyForParserWorker(bytes: ByteArray): ArrayBuffer =
    bytes.copyOf().unsafeCast<Int8Array>().buffer
