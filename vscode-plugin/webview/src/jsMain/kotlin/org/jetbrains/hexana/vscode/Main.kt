package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.shared.PdbCompanionLoader
import org.jetbrains.hexana.shared.WasmSymbolMapCompanionLoader
import org.jetbrains.hexana.shared.blazor.BlazorBootConfig
import org.jetbrains.hexana.shared.blazor.BlazorBundle
import org.jetbrains.hexana.shared.blazor.BlazorIntegrity
import org.jetbrains.hexana.shared.blazor.MonoAotAssembly
import org.jetbrains.hexana.shared.blazor.MonoAotReport
import org.jetbrains.hexana.shared.companionFileName
import org.jetbrains.hexana.shared.companionJoin
import org.jetbrains.hexana.shared.companionParentDirectory
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
    // `hexana.tabsPanelPosition` is "right"; pushed by the host on ready and on every change.
    var tabsPanelOnRight by remember { mutableStateOf(false) }
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
    // Moving the tabs updates this editor at once; the host persists the setting and pushes it
    // to every other open editor.
    val tabsPanelDock = remember(tabsPanelOnRight) {
        TabsPanelDock(tabsPanelOnRight) { onRight ->
            tabsPanelOnRight = onRight
            postSetTabsPanelPositionToHost(onRight)
        }
    }
    CompositionLocalProvider(LocalHost provides vscodeHost, LocalTabsPanelDock provides tabsPanelDock) {
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
                    if (message.settingKey == "tabsPanelOnRight") {
                        tabsPanelOnRight = message.settingValue
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
            // The parsers that read the whole file (ELF, Mach-O, ZIP, tar, ar, PDB, Parquet, Arrow)
            // get all of it too: what they read past the head is fetched from the host in rounds,
            // each rebuilding the provider below over more of the file.
            val structureFetcher = remember(buffer, partialFileSize) {
                partialFileSize?.takeIf { it > buffer.size }?.let { size -> StructureBytesFetcher(buffer, size, ::readByteRangeFromHost) }
            }
            val wholeFile = structureFetcher?.bytes

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
            val forcedProvider = remember(bytes, filePath, overrideParser, wholeFile) {
                overrideParser?.let { createBinaryProvider(it, buffer, sourcePath = filePath, fileSize = partialFileSize, source = wholeFile) }
            }
            val provider = remember(bytes, filePath, fileName, forcedProvider, wholeFile) {
                forcedProvider ?: createBinaryProvider(
                    buffer, sourcePath = filePath, fileName = fileName, fileSize = partialFileSize, source = wholeFile,
                )
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
            // The same goes for a Wasm module's companions: a Blazor WebAssembly assembly's Portable PDB
            // and a stripped Emscripten module's symbol map (`dotnet.native.js.symbols`). Without a
            // directory listing these are the names derived from the file's own; the effect below lists
            // the directory and adds the fingerprinted ones a publish writes.
            val modulePath = filePath.ifBlank { null }
            val pdbCandidates = remember(provider) {
                when (provider) {
                    is PeBinaryProvider -> PdbCompanionLoader.candidatePaths(provider)
                    is WasmBinaryProvider -> provider.webcil?.let { PdbCompanionLoader.candidatePaths(it, modulePath) }.orEmpty()
                    else -> emptyList()
                }
            }
            val symbolMapCandidates = remember(provider) {
                (provider as? WasmBinaryProvider)?.let { WasmSymbolMapCompanionLoader.candidatePaths(it, modulePath) }.orEmpty()
            }
            val waitsForCompanions = pdbCandidates.isNotEmpty() || symbolMapCandidates.isNotEmpty()
            var pdbReadyFor by remember(bytes) { mutableStateOf<BinaryProvider?>(null) }
            // The Blazor WebAssembly app a `_framework` module belongs to. Not waited for: the Blazor
            // App tab joins the others when it arrives.
            var blazorBundle by remember(provider) { mutableStateOf<BlazorBundle?>(null) }
            var monoAot by remember(provider) { mutableStateOf<MonoAotReport?>(null) }
            LaunchedEffect(provider) {
                val wasm = provider as? WasmBinaryProvider
                val directory = modulePath?.takeIf { wasm != null }?.let(::companionParentDirectory)
                // One listing serves the fingerprinted companions and the Blazor App tab.
                val listing = directory?.let { listDirectoryOrNull(host, it) }
                val siblings = listing.orEmpty().filter { it.isFile }.map { it.name }
                // Marked ready however the loop ends, or the editor would wait on this forever.
                try {
                    val pdbs = if (wasm != null && listing != null) {
                        wasm.webcil?.let { PdbCompanionLoader.candidatePaths(it, modulePath, siblings) }.orEmpty()
                    } else {
                        pdbCandidates
                    }
                    if (pdbs.isNotEmpty()) loadPdbCompanion(provider, pdbs, host)
                    if (wasm != null) {
                        val maps = if (listing != null) WasmSymbolMapCompanionLoader.candidatePaths(wasm, modulePath, siblings) else symbolMapCandidates
                        if (maps.isNotEmpty()) loadSymbolMapCompanion(wasm, maps, host)
                    }
                } finally {
                    pdbReadyFor = provider
                }
                if (wasm != null && directory != null && listing != null && modulePath != null) {
                    val bundle = loadBlazorBundle(host, directory, listing, companionFileName(modulePath))
                    blazorBundle = bundle
                    if (bundle != null) monoAot = loadMonoAotReport(host, wasm, bundle)
                }
            }
            // What the editor shows, paired with whether its parser was a fallback. Normally the
            // provider above; while a *replacement* waits for its PDB — a parser chosen for the
            // file — the one already on screen stays there instead of a full-page loading text,
            // which made every switch to or from a PE provider blink. Only the first provider
            // for these bytes has nothing to fall back to.
            val current = ShownEditor(provider, overrideFailure ?: providerBindings.error, providerBindings)
            val lastShown = remember(bytes) { arrayOfNulls<ShownEditor>(1) }
            val shown = if (!waitsForCompanions || pdbReadyFor === provider) current else lastShown[0]
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
                blazorBundle = blazorBundle,
                monoAot = monoAot,
                coverageOverride = structureFetcher?.coverageOverride,
                // Each fetch round rebuilds the provider; the selected tab stays.
                tabStateKey = if (structureFetcher != null) buffer else null,
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

/** The entries of [directory], or null when the host cannot list it. */
private suspend fun listDirectoryOrNull(host: Host, directory: String): List<HostDirectoryEntry>? = try {
    host.listDirectory(directory).await()
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    null
}

/**
 * Reads the Blazor WebAssembly app [currentFileName] belongs to from [directory], whose entries are
 * [listing]: the first boot manifest, then a second listing in which the extension host hashes every
 * file the manifest names, so integrity is checked without moving the files across the bus.
 */
private suspend fun loadBlazorBundle(
    host: Host,
    directory: String,
    listing: List<HostDirectoryEntry>,
    currentFileName: String,
): BlazorBundle? {
    val files = listing.filter { it.isFile }
    for (candidate in BlazorBundle.configCandidates(files.map { it.name })) {
        val size = files.firstOrNull { it.name == candidate }?.size ?: continue
        if (size > BlazorBundle.MAX_CONFIG_SIZE) continue
        val text = try {
            host.readBinaryFile(companionJoin(directory, candidate)).await().decodeToString()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            continue
        }
        val config = BlazorBootConfig.parse(text) ?: continue
        // Not part of this app build: no tab, and nothing hashed for it.
        if (config.resources.none { it.fileName == currentFileName }) return null
        val hashed = try {
            host.listDirectory(directory, config.resources.map { it.fileName }).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            files
        }
        val sizes = hashed.mapNotNull { entry -> entry.size?.let { entry.name to it } }.toMap()
        val hashes = hashed.mapNotNull { entry -> entry.sha256?.let { entry.name to it } }.toMap()
        return BlazorBundle.assemble(directory, candidate, config, sizes, currentFileName) { resource ->
            hashes[resource.fileName]?.let { BlazorBundle.integrityOf(resource, it) } ?: BlazorIntegrity.NotChecked
        }
    }
    return null
}

/**
 * The .NET AOT breakdown of [wasm], the app's runtime module: reads the app's assemblies through the
 * extension host for their metadata. Nothing is read for an interpreter-only build or without symbol names.
 */
private suspend fun loadMonoAotReport(host: Host, wasm: WasmBinaryProvider, bundle: BlazorBundle): MonoAotReport? {
    val files = MonoAotReport.assembliesToRead(wasm, bundle).takeIf { it.isNotEmpty() } ?: return null
    val assemblies = files.map { (name, file) ->
        val bytes = try {
            host.readBinaryFile(companionJoin(bundle.directory, file)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        MonoAotAssembly(name, bytes?.let(MonoAotReport::metadataOf))
    }
    return MonoAotReport.build(wasm, assemblies)
}

/** Reads [candidates] in order through the extension host and attaches the first symbol map that fits. */
private suspend fun loadSymbolMapCompanion(provider: WasmBinaryProvider, candidates: List<String>, host: Host) {
    for (path in candidates) {
        val bytes = try {
            host.readBinaryFile(path).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            continue
        }
        if (bytes.size > WasmSymbolMapCompanionLoader.MAX_AUTOMATIC_SYMBOL_MAP_SIZE) continue
        if (WasmSymbolMapCompanionLoader.attach(provider, path, bytes)) break
    }
}

/**
 * Reads [candidates] in order through the extension host and attaches the first matching PDB.
 * A missing file is answered with a failure at once; only a slow disk waits.
 */
private suspend fun loadPdbCompanion(provider: BinaryProvider, candidates: List<String>, host: Host) {
    for (path in candidates) {
        val pdbBytes = try {
            host.readBinaryFile(path).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            continue
        }
        val match = when (provider) {
            is PeBinaryProvider -> PdbCompanionLoader.attach(provider, path, pdbBytes)
            is WasmBinaryProvider -> provider.webcil?.let { PdbCompanionLoader.attach(it, pdbBytes) }
            else -> null
        }
        if (match == PdbMatch.Matches) break
    }
}
