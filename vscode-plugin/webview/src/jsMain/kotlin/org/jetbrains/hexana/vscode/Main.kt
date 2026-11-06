package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.shared.PdbCompanionLoader
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.shared.features.ParserRef
import org.jetbrains.hexana.shared.features.parseFileAssociations
import org.jetbrains.hexana.shared.features.serializeFileAssociations
import org.jetbrains.hexana.shared.features.serializeFileAssociationsSchema
import org.jetbrains.hexana.shared.scripting.PROVIDER_SCRIPTS_DIR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await

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
    var expiredMessage by remember { mutableStateOf<String?>(null) }
    val scriptTabs = remember { mutableStateListOf<ScriptTabState>() }
    var dialogPickedPath by remember { mutableStateOf("") }
    var dialogPickedContent by remember { mutableStateOf("") }
    var pickedKeyLogContent by remember { mutableStateOf("") }
    var showSemanticHighlighting by remember { mutableStateOf(true) }
    var isDecompressed by remember { mutableStateOf(false) }
    // Size on disk when the host loaded only the head of the file; see `hexana.maxLoadedFileSizeMB`.
    var partialFileSize by remember { mutableStateOf<Long?>(null) }
    var canRunApk by remember { mutableStateOf(false) }
    var featureCatalogJson by remember { mutableStateOf("") }
    var runtimeTargetsJson by remember { mutableStateOf("") }
    var openRuntimeTargetsRequest by remember { mutableStateOf(0) }
    var fileAssociationsJson by remember { mutableStateOf("") }
    var fileAssociationKey by remember { mutableStateOf("") }
    var openFileAssociationsRequest by remember { mutableStateOf(0) }
    var providerScripts by remember { mutableStateOf<List<ProviderScriptSource>>(emptyList()) }
    var providerScriptsUntrusted by remember { mutableStateOf(false) }
    // A provider script the host just created from this editor's menu, to assign to its file.
    var createdProviderId by remember { mutableStateOf<String?>(null) }
    // Row the host asked to select, e.g. for "Reveal in DEX" from an APK's editor.
    var tableRowSelection by remember { mutableStateOf<TableRowSelectionRequest?>(null) }

    // Global arrow-key handler for panel-table rows.  VSCode webviews
    // intercept keydown events before they reach focused DOM elements, so
    // per-row addEventListener("keydown") never fires.  A single window-
    // level listener (the same pattern HexViewerPanel uses) checks whether
    // the active element is a panel-table row and performs navigation.
    PanelTableRowKeyboardHandler()

    val vscodeHost = remember(canRunApk) { VscodeHost(canRunApk = canRunApk) }
    CompositionLocalProvider(LocalHost provides vscodeHost) {
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
                    partialFileSize = message.fileSize
                    canRunApk = message.canRunApk
                    message.showSemanticHighlighting?.let { showSemanticHighlighting = it }
                    // Pushed with the payload so the badges have their verdicts on first paint.
                    if (message.featureCatalogJson.isNotBlank()) {
                        featureCatalogJson = message.featureCatalogJson
                    }
                    runtimeTargetsJson = message.runtimeTargetsJson
                    fileAssociationsJson = message.fileAssociationsJson
                    fileAssociationKey = message.fileAssociationKey
                    message.providerScripts?.let { providerScripts = it }
                    providerScriptsUntrusted = message.providerScriptsUntrusted
                }
                // Sent when a provider script is added, edited or removed, and when the
                // workspace becomes trusted.
                "provider-scripts-loaded" -> {
                    providerScripts = message.providerScripts.orEmpty()
                    providerScriptsUntrusted = message.providerScriptsUntrusted
                }
                // Sent after a parser is chosen from the format badge or the dialog, and
                // whenever the file changes on disk. The provider below re-keys on the
                // parser it names for this file, so only the editors it affects reparse.
                "file-associations-loaded" -> {
                    fileAssociationsJson = message.fileAssociationsJson
                }
                "provider-script-created" -> {
                    if (message.providerId.isNotBlank()) createdProviderId = message.providerId
                }
                // The `hexana.configureFileAssociations` command.
                "open-file-associations-dialog" -> {
                    openFileAssociationsRequest++
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
                "select-table-row" -> {
                    if (message.tabName.isNotEmpty() && message.rowIndex >= 0) {
                        tableRowSelection = TableRowSelectionRequest(
                            message.tabName,
                            message.rowIndex,
                            requestId = (tableRowSelection?.requestId ?: 0) + 1,
                        )
                    }
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
                // Both kinds render the same view, which picks the format from the bytes. The
                // host says which view it opens on: Hexana's Compare Files asks for the Hexana
                // diff, VS Code's own Compare Selected for the hex diff.
                "load-diff", "load-native-diff" -> {
                    val left = message.leftData
                    val right = message.rightData
                    if (left != null && right != null) {
                        val mode = if (message.diffMode == "hexana") DiffViewMode.HEXANA else DiffViewMode.HEX
                        diffPayload = DiffPayload(left, message.leftName, right, message.rightName, mode)
                    }
                }
                "load-expired" -> {
                    expiredMessage = message.message.ifBlank {
                        "This diff session has expired. Please run \"Hexana: Compare Files\" again."
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
                            // A member re-extracted from its parent has no file of its own.
                            fileAssociationKey = ""
                            wasmBytes = extracted
                            configuredRuntimeId = message.wasmRuntime
                        } else {
                            expiredMessage = "Failed to extract nested module from parent. Please reopen from the parent file."
                        }
                    }
                }
                "binary-file-content" -> {
                    // A script tab is suspended on `open(path)` waiting for this.
                    completeScriptBinaryRead(
                        message.requestId,
                        message.data,
                        message.message.takeIf { it.isNotBlank() },
                    )
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

        val dp = diffPayload
        if (dp != null) {
            val input = remember(dp) {
                BinaryDiffInput(dp.leftData.toCommonByteBuffer(), dp.rightData.toCommonByteBuffer(), dp.leftName, dp.rightName)
            }
            DiffModeView(input, dp.mode)
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
            val pagedBytes = remember(buffer, partialFileSize) {
                partialFileSize?.takeIf { it > buffer.size }?.let { size -> PagedHexBytes(buffer, size, ::readByteRangeFromHost) }
            }

            // `fileName` rather than `filePath` drives the extension-based half of the
            // format decision: the host inflates a `.gz` before pushing it and reports
            // the stripped name there, while `filePath` still points at the compressed
            // file on disk.
            // A parser chosen in `.hexana/file_associations.json` beats detection; one that
            // cannot read the bytes falls back to it, and the badge says so. Keyed on the
            // parser rather than the whole JSON, so an association added for another file
            // does not reparse this one.
            val associations = remember(fileAssociationsJson) { parseFileAssociations(fileAssociationsJson) }
            val chosenParser = associations.parserFor(fileAssociationKey.ifBlank { null })
            // What the provider scripts register, learnt against an empty binary; re-evaluated
            // only when a script changes, not when this file's choice does.
            val providerCatalog = remember(providerScripts, providerScriptsUntrusted) {
                loadProviderCatalog(providerScripts, providerScriptsUntrusted)
            }
            val scripted = (chosenParser as? ParserRef.Scripted)?.let { providerCatalog.provider(it.id)?.second?.info }
            // The built-in parser the choice reads the file with: itself, or the one a scripted
            // provider builds on.
            val overrideParser = when (chosenParser) {
                is ParserRef.Builtin -> chosenParser.parser
                is ParserRef.Scripted -> scripted?.base
                null -> null
            }
            val forcedProvider = remember(bytes, filePath, overrideParser) {
                overrideParser?.let { createBinaryProvider(it, buffer, sourcePath = filePath, fileSize = partialFileSize) }
            }
            val provider = remember(bytes, filePath, fileName, forcedProvider) {
                forcedProvider ?: createBinaryProvider(buffer, sourcePath = filePath, fileName = fileName, fileSize = partialFileSize)
            }
            val overrideFailure = when {
                chosenParser is ParserRef.Scripted && scripted == null ->
                    if (providerCatalog.untrusted) {
                        "Provider scripts do not run in an untrusted workspace, so ${chosenParser.id} is not available."
                    } else {
                        "No script in $PROVIDER_SCRIPTS_DIR defines ${chosenParser.id}."
                    }
                overrideParser != null && forcedProvider == null ->
                    if (scripted != null) {
                        "${scripted.name} builds on the ${overrideParser.displayName} parser, which cannot read this file."
                    } else {
                        "The ${overrideParser.displayName} parser cannot read this file."
                    }
                else -> null
            }
            // Assigns a provider the user just created from the menu to this file. The script's
            // own push follows from the watcher; until it arrives the badge reads "not loaded".
            LaunchedEffect(createdProviderId) {
                val id = createdProviderId ?: return@LaunchedEffect
                createdProviderId = null
                val key = fileAssociationKey.ifBlank { null } ?: return@LaunchedEffect
                host.saveFileAssociations(
                    serializeFileAssociations(associations.with(key, ParserRef.Scripted(id))),
                    serializeFileAssociationsSchema(providerCatalog.providers),
                )
            }
            // What the scripts contribute to this file: the provider's regions, and the tabs
            // that attach to it. Evaluated against the file itself, as on the JVM.
            val scriptedInEffect = scripted?.takeIf { overrideFailure == null }
            val providerBindings = remember(provider, providerCatalog, scriptedInEffect) {
                bindBrowserProviderScripts(providerCatalog, provider, fileName, filePath, scriptedInEffect?.id)
            }

            // A PE image's PDB lives next to it. Load it before any tab is built, so the IL view,
            // Methods and Debug Info show its source lines on first paint instead of rebuilding.
            val pdbCandidates = remember(provider) {
                (provider as? PeBinaryProvider)?.let { PdbCompanionLoader.candidatePaths(it) }.orEmpty()
            }
            var pdbReadyFor by remember(bytes) { mutableStateOf<BinaryProvider?>(null) }
            LaunchedEffect(provider) {
                val pe = provider as? PeBinaryProvider
                // Marked ready however the loop ends, or the editor would wait on this forever.
                try {
                    if (pe != null && pdbCandidates.isNotEmpty()) loadPdbCompanion(pe, pdbCandidates, host)
                } finally {
                    pdbReadyFor = provider
                }
            }
            // What the editor shows, paired with whether its parser was a fallback. Normally the
            // provider above; while a *replacement* waits for its PDB — a parser chosen for the
            // file — the one already on screen stays there instead of a full-page loading text,
            // which made every switch to or from a PE provider blink. Only the first provider
            // for these bytes has nothing to fall back to.
            val current = ShownEditor(provider, overrideFailure ?: providerBindings.error, providerBindings)
            val lastShown = remember(bytes) { arrayOfNulls<ShownEditor>(1) }
            val shown = if (pdbCandidates.isEmpty() || pdbReadyFor === provider) current else lastShown[0]
            if (shown == null) {
                Div({ classes("loading") }) {
                    Text("Loading debug symbols…")
                }
                return@CompositionLocalProvider
            }
            SideEffect { lastShown[0] = shown }
            val shownProvider = shown.provider

            val extraTabs = remember(bytes, shownProvider, shown.bindings) {
                computeExtraTabs(buffer, shownProvider) +
                    shown.bindings.tabs.map(::providerScriptPanelTab)
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
                provider = shownProvider,
                isDecompressed = isDecompressed,
                partialFileSize = partialFileSize,
                // The hex view covers the whole of a file the webview holds only the head of,
                // fetching pages from the host as they scroll into view.
                hexBytes = pagedBytes,
                onSearchFile = if (pagedBytes != null) ::searchFileOnHost else null,
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
                fileAssociationsJson = fileAssociationsJson,
                fileAssociationKey = fileAssociationKey,
                parserOverrideFailure = shown.failure,
                parserLabelOverride = shown.bindings.provider?.name,
                scriptedProviders = providerCatalog.providers,
                scriptStructureRanges = shown.bindings.ranges,
                openFileAssociationsRequest = openFileAssociationsRequest,
                tableRowSelection = tableRowSelection,
            )
        }
    }
}

/**
 * What the editor is showing: the provider, why its parser choice is not fully in effect (or
 * `null`), and what the provider scripts contribute — kept together so a replacement waiting
 * for its PDB never mixes one provider's tabs with another's label.
 */
private class ShownEditor(
    val provider: BinaryProvider,
    val failure: String?,
    val bindings: BrowserProviderBindings,
)

/**
 * Reads [candidates] in order through the extension host and attaches the first matching PDB.
 * A missing file is answered with a failure at once; only a slow disk waits.
 */
private suspend fun loadPdbCompanion(pe: PeBinaryProvider, candidates: List<String>, host: Host) {
    for (path in candidates) {
        val pdbBytes = try {
            host.readBinaryFile(path).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            continue
        }
        if (PdbCompanionLoader.attach(pe, path, pdbBytes) == PdbMatch.Matches) break
    }
}
