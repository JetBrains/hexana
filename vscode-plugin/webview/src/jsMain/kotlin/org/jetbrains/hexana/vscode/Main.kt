package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*

fun main() {
    renderComposable(rootElementId = "root") {
        BinaryEditorApp()
    }
}

@Composable
fun BinaryEditorApp() {
    var wasmBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var filePath by remember { mutableStateOf("") }
    var parentFileName by remember { mutableStateOf<String?>(null) }
    var diffPayload by remember { mutableStateOf<DiffPayload?>(null) }
    var nativeDiffPayload by remember { mutableStateOf<NativeDiffPayload?>(null) }
    var expiredMessage by remember { mutableStateOf<String?>(null) }
    val scriptTabs = remember { mutableStateListOf<ScriptTabState>() }
    var dialogPickedPath by remember { mutableStateOf("") }
    var dialogPickedContent by remember { mutableStateOf("") }
    var pickedKeyLogContent by remember { mutableStateOf("") }
    var showSemanticHighlighting by remember { mutableStateOf(true) }
    var isDecompressed by remember { mutableStateOf(false) }
    var featureCatalogJson by remember { mutableStateOf("") }
    var runtimeTargetsJson by remember { mutableStateOf("") }
    var openRuntimeTargetsRequest by remember { mutableStateOf(0) }

    // Global arrow-key handler for panel-table rows.  VSCode webviews
    // intercept keydown events before they reach focused DOM elements, so
    // per-row addEventListener("keydown") never fires.  A single window-
    // level listener (the same pattern HexViewerPanel uses) checks whether
    // the active element is a panel-table row and performs navigation.
    PanelTableRowKeyboardHandler()

    CompositionLocalProvider(LocalHost provides VscodeHost()) {
        val host = LocalHost.current

        VscodeMessageListener { message ->
            when (message.type) {
                "load-wasm" -> {
                    fileName = message.fileName
                    filePath = message.filePath
                    wasmBytes = message.data
                    parentFileName = message.parentFileName
                    configuredRuntimeId = message.wasmRuntime
                    isDecompressed = message.decompressed
                    message.showSemanticHighlighting?.let { showSemanticHighlighting = it }
                    // Pushed with the payload so the badges have their verdicts on first paint.
                    if (message.featureCatalogJson.isNotBlank()) {
                        featureCatalogJson = message.featureCatalogJson
                    }
                    runtimeTargetsJson = message.runtimeTargetsJson
                }
                // Sent after a save from the settings dialog, and whenever the file changes
                // on disk (an edit by hand, a git pull, a teammate's commit).
                "runtime-targets-loaded" -> {
                    runtimeTargetsJson = message.runtimeTargetsJson
                }
                // The `hexana.configureWasmRuntimes` command, so the settings dialog is
                // reachable from the command palette and not only from the Compatibility tab.
                "open-runtime-targets-dialog" -> {
                    openRuntimeTargetsRequest++
                }
                // Sent when a background refresh replaces the host's cached catalog.
                "feature-catalog-loaded" -> {
                    if (message.featureCatalogJson.isNotBlank()) {
                        featureCatalogJson = message.featureCatalogJson
                    }
                }
                "update-setting" -> {
                    if (message.settingKey == "showSemanticHighlighting") {
                        showSemanticHighlighting = message.settingValue
                    }
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
                "ssl-key-log-picked" -> {
                    if (message.keyLogContent.isNotBlank()) {
                        pickedKeyLogContent = message.keyLogContent
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
                            host.readScriptFile(path)
                        }
                    }
                }
            }
        }

        if (expiredMessage != null) {
            Div({ classes("loading") }) {
                Text(expiredMessage!!)
            }
            return@CompositionLocalProvider
        }

        val ndp = nativeDiffPayload
        if (ndp != null) {
            NativeDiffView(
                leftData = ndp.leftData,
                leftName = ndp.leftName,
                rightData = ndp.rightData,
                rightName = ndp.rightName,
            )
            return@CompositionLocalProvider
        }

        val dp = diffPayload
        if (dp != null) {
            WasmDiffView(
                leftData = dp.leftData,
                leftName = dp.leftName,
                rightData = dp.rightData,
                rightName = dp.rightName,
            )
            return@CompositionLocalProvider
        }

        if (wasmBytes == null) {
            Div({ classes("loading") }) {
                Text("Waiting for binary…")
            }
        } else {
            val bytes = wasmBytes!!
            // Remembered, because a buffer wrapper compares by identity: built
            // fresh on each recomposition it would be a new key for the
            // provider below, so any state change here - picking a key log,
            // toggling highlighting - would silently swap in a newly parsed
            // provider and drop everything installed on the old one.
            val buffer = remember(bytes) { bytes.toCommonByteBuffer() }
            buffer.position(0)

            val provider = remember(bytes, filePath) {
                when (WasmBinaryProviderSelector.select(buffer)) {
                    BinaryProviderType.Wasm -> WasmBinaryProvider(buffer)
                    BinaryProviderType.WasmComponent -> WasmComponentBinaryProvider(buffer)
                    else -> nativeOrGenericBinaryProvider(buffer, filePath)
                }
            }

            val extraTabs = remember(bytes, provider) {
                computeExtraTabs(buffer, provider)
            }

            // Remembered for the same reason as the buffer above: the panel
            // keys its whole tab list on `sbomVulnerabilityCheck`, so a fresh
            // wrapper per recomposition rebuilds every tab from scratch - which
            // for a capture with a key log means re-deriving the TLS keys and
            // re-decrypting the records, in composition, on the one thread a
            // webview has.
            val capabilities = remember {
                WebUiCapabilities(
                    sbomVulnerabilityCheck = SbomVulnerabilityContribution { sbom, onReportReady ->
                        VulnBar(sbom, onReportReady)
                    },
                    // The extension bundles examples/scripting/*.js and can copy
                    // one into the workspace.
                    bundledScriptExamples = true,
                )
            }

            BinaryEditorContent(
                fileName = fileName,
                filePath = filePath,
                parentFileName = parentFileName,
                provider = provider,
                isDecompressed = isDecompressed,
                extraTabs = extraTabs,
                capabilities = capabilities,
                scriptTabs = scriptTabs,
                showSemanticHighlighting = showSemanticHighlighting,
                dialogPickedPath = dialogPickedPath,
                dialogPickedContent = dialogPickedContent,
                onDialogPickedPathConsumed = {
                    dialogPickedPath = ""
                    dialogPickedContent = ""
                },
                sslKeyLogContent = pickedKeyLogContent,
                onSslKeyLogConsumed = { pickedKeyLogContent = "" },
                onScriptTabConfirmed = { path, content ->
                    if (scriptTabs.none { it.path == path }) {
                        scriptTabs.add(ScriptTabState(path, content))
                        host.scriptTabAdded(path)
                    }
                },
                onScriptTabRemoved = { path ->
                    scriptTabs.removeAll { it.path == path }
                },
                featureCatalogJson = featureCatalogJson,
                runtimeTargetsJson = runtimeTargetsJson,
                openRuntimeTargetsRequest = openRuntimeTargetsRequest,
            )
        }
    }
}
