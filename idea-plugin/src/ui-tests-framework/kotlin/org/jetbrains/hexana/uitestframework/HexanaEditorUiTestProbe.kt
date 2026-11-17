package org.jetbrains.hexana.uitestframework

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usages.UsageViewManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.breakpoints.BinaryBreakpointRegistry
import org.jetbrains.hexana.breakpoints.BinaryIdentityProvider
import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaDiffVirtualFile
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryVirtualFileProvider
import org.jetbrains.hexana.shared.BinaryAnchor
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import org.jetbrains.hexana.providers.format.typeDisplayName
import org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphNodeKind
import org.jetbrains.hexana.wasm.analysis.typegraph.computeTypeNeighborhood
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.parseCodeEntry
import org.jetbrains.hexana.providers.format.WasmComponentDiffOrchestrator
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
import org.jetbrains.hexana.shared.CLR_IL_TAB_NAME
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.shared.WasmComponentTableTabsProvider
import org.jetbrains.hexana.ui.ScriptTabSettings
import org.jetbrains.hexana.ui.WasmFunctionNavTarget
import org.jetbrains.hexana.ui.panel.DisasmLoadState
import org.jetbrains.hexana.ui.panel.discoverDisasmRanges
import org.jetbrains.hexana.ui.panel.enrichComponentTabs
import org.jetbrains.hexana.ui.panel.loadCapstoneDisassembly
import org.jetbrains.hexana.ui.panel.WatConversionState
import org.jetbrains.hexana.wat.LightWatFile
import org.jetbrains.hexana.wat.WatUsageTarget
import org.jetbrains.hexana.wat.psi.WatComponentElement
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

private const val WIRE_VERSION = "1"
private const val SNAPSHOT_SCHEMA = "hexana-editor-snapshot"
private const val SEARCH_STATE_SCHEMA = "hexana-editor-search-state"
private const val WAT_STATE_SCHEMA = "hexana-editor-wat-state"
private const val NATIVE_DIFF_STATE_SCHEMA = "hexana-native-diff-state"
private const val TABLE_TAB_STATE_SCHEMA = "hexana-table-tab-state"
private const val WAT_FIND_USAGES_CAPTURE_STATE_SCHEMA = "hexana-wat-find-usages-capture-state"
private const val PROJECT_RELATIVE_FILE_IDENTIFIER_PREFIX = "project-relative:"
private const val ABSOLUTE_FILE_IDENTIFIER_PREFIX = "absolute:"

object HexanaEditorUiTestProbe {
    @JvmStatic
    fun frameworkVersion(): String = "1"

    @JvmStatic
    fun ping(): String = "pong"

    /** Expires all notification balloons; capture drills call this before screenshots. */
    @JvmStatic
    fun clearNotifications(): List<String> = runOnEdt {
        ProjectManager.getInstance().openProjects.forEach { project ->
            NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(Notification::class.java, project)
                .forEach { it.expire() }
        }
        payload("hexana-clear-notifications", listOf("cleared=true"))
    }

    @JvmStatic
    fun closeAllEditors(): List<String> = runOnEdt {
        ProjectManager.getInstance().openProjects.forEach { project ->
            val manager = FileEditorManager.getInstance(project)
            manager.openFiles.toList().forEach { file -> manager.closeFile(file) }
        }
        payload("hexana-close-all-editors", listOf("closed=true"))
    }

    @JvmStatic
    fun openAbsoluteFile(path: String): List<String> {
        val normalizedPath = Path.of(path).toAbsolutePath().normalize()
        val file = VfsUtil.findFile(normalizedPath, true)
            ?: throw IllegalStateException("Could not find file for screenshot capture: $normalizedPath")
        return runOnEdt {
            val project = singleOpenProject()
            FileEditorManager.getInstance(project).openFile(file, true)
            payload(
                "hexana-open-absolute-file",
                listOf(
                    "path=${normalizedPath.toString().toSystemIndependentPath()}",
                    "fileName=${file.name}",
                ),
            )
        }
    }

    @JvmStatic
    fun openAbsoluteFileWithScriptTab(path: String, scriptPath: String): List<String> {
        val normalizedPath = Path.of(path).toAbsolutePath().normalize()
        val normalizedScriptPath = Path.of(scriptPath).toAbsolutePath().normalize()
        val file = VfsUtil.findFile(normalizedPath, true)
            ?: throw IllegalStateException("Could not find file for screenshot capture: $normalizedPath")
        if (!normalizedScriptPath.toFile().isFile) {
            throw IllegalStateException("Could not find script for screenshot capture: $normalizedScriptPath")
        }
        return runOnEdt {
            val project = singleOpenProject()
            val scriptSettings = ScriptTabSettings.getInstance(project)
            scriptSettings.getScriptPaths(file.name).forEach { scriptSettings.removeScriptPath(file.name, it) }
            scriptSettings.addScriptPath(file.name, normalizedScriptPath.toString())
            FileEditorManager.getInstance(project).openFile(file, true)
            payload(
                "hexana-open-absolute-file-with-script-tab",
                listOf(
                    "path=${normalizedPath.toString().toSystemIndependentPath()}",
                    "fileName=${file.name}",
                    "scriptPath=${normalizedScriptPath.toString().toSystemIndependentPath()}",
                    "scriptName=${normalizedScriptPath.fileName}",
                ),
            )
        }
    }

    @JvmStatic
    fun activateEditor(fileIdentifier: String): List<String> = runOnEdt {
        val candidate = requireHexanaEditorCandidate(fileIdentifier)
        FileEditorManager.getInstance(candidate.project).openFile(candidate.editor.myFile, true)
        payload(
            "hexana-activate-editor",
            listOf("fileName=${candidate.editor.myFile.name}"),
        )
    }

    @JvmStatic
    fun activateStructureToolWindow(fileIdentifier: String): List<String> = runOnEdt {
        val candidate = requireHexanaEditorCandidate(fileIdentifier)
        FileEditorManager.getInstance(candidate.project).openFile(candidate.editor.myFile, true)
        val toolWindow = ToolWindowManager.getInstance(candidate.project).getToolWindow(ToolWindowId.STRUCTURE_VIEW)
            ?: throw IllegalStateException("Structure tool window is not registered")
        toolWindow.activate(null, true)
        payload(
            "hexana-activate-structure-tool-window",
            listOf(
                "fileName=${candidate.editor.myFile.name}",
                "toolWindow=${ToolWindowId.STRUCTURE_VIEW}",
            ),
        )
    }

    @JvmStatic
    fun openComponentDiff(leftPath: String, rightPath: String): List<String> {
        val left = resolveProjectFile(leftPath)
        val right = resolveProjectFile(rightPath)
        val leftBytes = left.contentsToByteArray().toCommonByteBuffer()
        val rightBytes = right.contentsToByteArray().toCommonByteBuffer()
        val leftProvider = WasmComponentBinaryProvider(leftBytes.duplicate())
        val rightProvider = WasmComponentBinaryProvider(rightBytes.duplicate())
        require(leftProvider.canProvideWasmModule()) { "Left file is not a Component Model binary: $leftPath" }
        require(rightProvider.canProvideWasmModule()) { "Right file is not a Component Model binary: $rightPath" }

        val input = BinaryDiffInput(leftBytes, rightBytes, left.name, right.name)
        val diffFile = HexanaDiffVirtualFile(input.title, input)
        return runOnEdt {
            FileEditorManager.getInstance(singleOpenProject()).openFile(diffFile, true)
            payload(
                "hexana-open-component-diff",
                listOf(
                    "fileName=${diffFile.name}",
                    "leftName=${left.name}",
                    "rightName=${right.name}",
                ),
            )
        }
    }

    @JvmStatic
    fun openPlatformHexDiff(leftPath: String, rightPath: String): List<String> {
        val left = resolveAbsoluteFile(leftPath)
        val right = resolveAbsoluteFile(rightPath)
        return runOnEdt {
            val factory = com.intellij.diff.DiffContentFactory.getInstance()
            val project = singleOpenProject()
            val request = com.intellij.diff.requests.SimpleDiffRequest(
                "Hexana byte comparison", factory.create(project, left), factory.create(project, right), left.name, right.name,
            )
            com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
            payload("hexana-platform-hex-diff", listOf("leftName=${left.name}", "rightName=${right.name}"))
        }
    }

    @JvmStatic
    fun openNativeDiff(leftPath: String, rightPath: String): List<String> {
        val left = resolveAbsoluteFile(leftPath)
        val right = resolveAbsoluteFile(rightPath)
        val input = BinaryDiffInput(
            left.contentsToByteArray().toCommonByteBuffer(),
            right.contentsToByteArray().toCommonByteBuffer(),
            left.name,
            right.name,
        )
        val diffFile = HexanaDiffVirtualFile(input.title, input)
        return runOnEdt {
            FileEditorManager.getInstance(singleOpenProject()).openFile(diffFile, true)
            payload(
                "hexana-open-native-diff",
                listOf(
                    "fileName=${diffFile.name}",
                    "leftName=${left.name}",
                    "rightName=${right.name}",
                ),
            )
        }
    }

    @JvmStatic
    fun openNestedComponent(fileIdentifier: String, componentIndex: Int): List<String> = runOnEdt {
        val parentEditor = requireHexanaEditor(fileIdentifier)
        val provider = parentEditor.binaryProvider as? WasmComponentBinaryVirtualFileProvider
            ?: throw IllegalStateException(
                "Expected a Component Model editor for '$fileIdentifier', got ${parentEditor.binaryProvider.kindId()}",
            )
        val componentsTab = WasmComponentTableTabsProvider()
            .tableTabs(provider)
            .enrichComponentTabs(provider, parentEditor.myFile, parentEditor.myProject)
            .singleOrNull { it.name == "Components" }
            ?: throw IllegalStateException("Expected a Components tab for '$fileIdentifier'")
        require(componentIndex in 0 until componentsTab.rowCount) {
            "Nested component index $componentIndex is outside the Components tab row range 0 until ${componentsTab.rowCount}"
        }
        val openAction = componentsTab.rowClickAction
            ?: throw IllegalStateException("Components tab for '$fileIdentifier' has no open-in-editor action")

        openAction(componentIndex)
        val childEditor = FileEditorManager.getInstance(parentEditor.myProject).selectedEditor as? HexanaFileEditor
            ?: throw IllegalStateException("Opening nested component $componentIndex did not select a Hexana editor")
        check(childEditor !== parentEditor) { "Opening nested component $componentIndex kept the parent editor selected" }
        payload(
            "hexana-open-nested-component",
            listOf(
                "parentFileName=${parentEditor.myFile.name}",
                "fileName=${childEditor.myFile.name}",
                "baseOffset=${childEditor.myFile.getUserData(org.jetbrains.hexana.HexanaBinaryVirtualFile.BASE_OFFSET_KEY) ?: 0}",
            ),
        )
    }

    @JvmStatic
    fun selectTab(fileIdentifier: String, tabName: String): List<String> = runOnEdt {
        val editor = requireHexanaEditor(fileIdentifier)
        val panels = editor.panelNames()
        val index = panels.indexOf(tabName)
        if (index < 0) {
            throw IllegalStateException(
                "Expected tab '$tabName' for '$fileIdentifier', but available tabs are: $panels",
            )
        }
        editor.stateHolder.setSelectedTab(index)
        editor.snapshot()
    }

    @JvmStatic
    fun setEditorTopFraction(fileIdentifier: String, topFraction: Float): List<String> = runOnEdt {
        val editor = requireHexanaEditor(fileIdentifier)
        editor.stateHolder.setTopFraction(topFraction)
        editor.snapshot()
    }

    @JvmStatic
    fun navigateToWasmFunction(fileIdentifier: String, funcIdx: Int): List<String> = runOnEdt {
        val editor = requireHexanaEditor(fileIdentifier)
        editor.requestWasmFunctionNav(WasmFunctionNavTarget(funcIdx))
        payload(
            "hexana-navigate-wasm-function",
            listOf(
                "fileName=${editor.myFile.name}",
                "funcIdx=$funcIdx",
            ),
        )
    }

    @JvmStatic
    fun topSizeState(fileIdentifier: String): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        val sizeAnalysis = editor.binaryProvider?.sizeAnalysis
        return payload(
            "hexana-top-size-state",
            listOf(
                "totalSize=${sizeAnalysis?.size ?: 0}",
                "topLevelCount=${sizeAnalysis?.children?.size ?: 0}",
            ),
        )
    }

    @JvmStatic
    fun setClipboardText(text: String): List<String> = runOnEdt {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        payload("hexana-set-clipboard-text", listOf("length=${text.length}"))
    }

    /**
     * Reports whether the Hexana Suite run configuration type is registered (i.e. the
     * org.jetbrains.hexana.suiteRunner content module loaded) and whether the SM test-runner
     * classes it needs are reachable through THAT module's classloader. Classes are looked up
     * by name only (no static references) so the probe itself never fails to link. The type is
     * registered on 2026.1+ where the platform exposes intellij.platform.smRunner /
     * intellij.platform.testRunner as module names; on 2025.2/2025.3 the module is skipped
     * and the type is absent by design.
     */
    @JvmStatic
    fun suiteConsoleClasspathState(): List<String> {
        val suiteType = ConfigurationTypeUtil.findConfigurationType("HexanaSuiteRunConfiguration")
        val loader = suiteType?.javaClass?.classLoader
        val classFlags = listOf(
            "com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil",
            "com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties",
            "com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction",
        ).map { name ->
            val loadable = loader != null && runCatching { Class.forName(name, false, loader) }.isSuccess
            "${name.substringAfterLast('.')}=$loadable"
        }
        return payload(
            "hexana-suite-console-classpath",
            classFlags + "suiteTypeRegistered=${suiteType != null}",
        )
    }

    /**
     * Adds a breakpoint on the first instruction of the first function whose name
     * contains [query] (case-insensitive). Works for both native (ELF/Mach-O/PE)
     * and WASM files.
     *
     * For native files the anchor is [BinaryAnchor.Native] keyed by the first
     * decoded instruction's virtual address. For WASM files the anchor is
     * [BinaryAnchor.Wasm] keyed by the global function index and the module-absolute
     * byte offset of the first opcode in that function's body.
     *
     * The file must already be open as a Hexana editor; its identity is computed via
     * [BinaryIdentityProvider] (SHA-256 of the underlying file bytes). Returns a
     * payload describing the breakpoint that was added.
     */
    @JvmStatic
    fun toggleBinaryBreakpointOnFirstInstruction(fileIdentifier: String, query: String): List<String> {
        val candidate = requireHexanaEditorCandidate(fileIdentifier)
        val editor = candidate.editor
        val provider = editor.binaryProvider
            ?: throw IllegalStateException("No binary provider for '$fileIdentifier'")
        val project = candidate.project
        val file = editor.myFile

        // Use the editor's already-cached identity so the breakpoint is keyed by the
        // same identity that GutterMarksModel was constructed with. Fall back to
        // computing it fresh only when cachedIdentity hasn't been set yet.
        val elfBuildId = (provider as? org.jetbrains.hexana.providers.ElfBinaryProvider)?.buildId
        val identity = editor.cachedIdentity
            ?: BinaryIdentityProvider.identityFor(file, elfBuildId)
            ?: throw IllegalStateException("Could not compute identity for '$fileIdentifier' (file=${file.url})")
        val registry = BinaryBreakpointRegistry.getInstance(project)
        val filePath = if (file.isInLocalFileSystem) file.path else null

        return when (provider) {
            is NativeBinaryProvider -> {
                val discovery = discoverDisasmRanges(provider)
                    ?: throw IllegalStateException("No disassembly ranges for '$fileIdentifier'")
                val matches = discovery.ranges.withIndex()
                    .filter { (_, r) -> r.displayName.contains(query, ignoreCase = true) }
                val targetMatch = (matches.firstOrNull { (_, r) -> r.size >= 16 } ?: matches.firstOrNull())
                    ?: discovery.ranges.withIndex().firstOrNull { (_, r) -> r.size >= 16 }
                    ?: throw IllegalStateException("No matching range for query '$query' in '$fileIdentifier'")
                val range = targetMatch.value
                val loadState = runBlocking { loadCapstoneDisassembly(discovery) }
                val catalog = (loadState as? DisasmLoadState.Loaded)?.catalog
                    ?: throw IllegalStateException(
                        "Capstone load failed for '$fileIdentifier': ${(loadState as? DisasmLoadState.Error)?.message}",
                    )
                val instructions = runBlocking { catalog.decode(targetMatch.index) }
                val firstInsn = instructions.firstOrNull()
                    ?: throw IllegalStateException("No decoded instructions in range '${range.displayName}'")
                // Anchor a few instructions in, NOT on the very first one: the chunk
                // navigator parks the body's first row exactly under the sticky
                // function header (ChunkPanelNavigator.scrollTo scrolls past the
                // header by its height, which the sticky overlay then re-covers), so
                // a mark on the first instruction is hidden after search navigation.
                val targetInsn = instructions.getOrNull(3) ?: firstInsn
                val anchor = BinaryAnchor.Native(
                    virtualAddress = targetInsn.virtualAddress,
                    symbolName = range.displayName,
                    offsetInSymbol = targetInsn.virtualAddress - firstInsn.virtualAddress,
                )
                // toggle returns false when it removed an existing breakpoint (idempotent add).
                if (!registry.toggle(identity, anchor, filePath)) {
                    registry.add(identity, anchor, filePath)
                }
                payload(
                    "hexana-toggle-breakpoint",
                    listOf(
                        "kind=native",
                        "symbol=${range.displayName}",
                        "va=0x${targetInsn.virtualAddress.toString(16)}",
                        "gutterModelReady=${editor.gutterMarksModelFlow.value != null}",
                        "added=true",
                    ),
                )
            }

            is WasmBinaryProvider -> {
                val wasmModule = provider.wasmModule
                    ?: throw IllegalStateException("WASM module parse failed for '$fileIdentifier'")
                val importFuncCount = wasmModule.importSection
                    ?.let { sec ->
                        with(sec) { provider.bytes.getImportRecords() }
                            .count { it.type is WasmTypeIndexExternalType }
                    } ?: 0
                val codeSection = wasmModule.codeSection
                    ?: throw IllegalStateException("No code section in '$fileIdentifier'")
                val codeEntryOffsets = with(codeSection) { provider.bytes.scanOffsets() }
                if (codeEntryOffsets.isEmpty()) {
                    throw IllegalStateException("Empty code section in '$fileIdentifier'")
                }
                val localFuncIdx = 0
                val globalFuncIdx = importFuncCount + localFuncIdx
                val entry = with(codeSection) { provider.bytes.parseCodeEntry(codeEntryOffsets[localFuncIdx].offset) }
                // Use the first opcode with a non-zero module-absolute offset. Singleton opcodes
                // like Unreachable / Nop carry offset==0 (they are shared objects), so fall back
                // to the estimateBodyStart formula used by the WAT renderer to derive initialCursor.
                val byteOffset = entry.opCodes
                    .filterIsInstance<OpCodeWithOffset>()
                    .firstOrNull { it.offset > 0 }
                    ?.offset?.toLong()
                    ?: (entry.offset.toLong() + 1L + entry.locals.size.toLong() * 4L)
                val funcName = wasmModule.exportSection?.let { sec ->
                    with(sec) { provider.bytes.scan() }
                        .filterIsInstance<WasmFunctionExportRecordEntry>()
                        .firstOrNull { it.index.toInt() == globalFuncIdx }
                        ?.name
                }
                val anchor = BinaryAnchor.Wasm(
                    funcIdx = globalFuncIdx,
                    byteOffset = byteOffset,
                    funcName = funcName,
                    instrOrdinal = 0,
                )
                // toggle returns false when it removed an existing breakpoint (idempotent add).
                if (!registry.toggle(identity, anchor, filePath)) {
                    registry.add(identity, anchor, filePath)
                }
                payload(
                    "hexana-toggle-breakpoint",
                    listOf(
                        "kind=wasm",
                        "funcIdx=$globalFuncIdx",
                        "byteOffset=0x${byteOffset.toString(16)}",
                        "funcName=${funcName.orEmpty()}",
                        "gutterModelReady=${editor.gutterMarksModelFlow.value != null}",
                        "added=true",
                    ),
                )
            }

            else -> throw IllegalStateException(
                "toggleBinaryBreakpointOnFirstInstruction: unsupported provider ${provider.kindId()} for '$fileIdentifier'"
            )
        }
    }

    /**
     * Sets [HexanaFileEditor.wasmByteOffsetNavFlow] to [byteOffset] so the WAT
     * (virtualized) panel scrolls to the instruction at that module-absolute byte
     * offset. Must be called after the WAT tab is selected and rendered.
     */
    @JvmStatic
    fun navigateWatToByteOffset(fileIdentifier: String, byteOffset: Int): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        editor.requestWasmByteOffsetNav(byteOffset)
        return payload("hexana-navigate-wat-byte-offset", listOf("byteOffset=0x${byteOffset.toString(16)}"))
    }

    /**
     * Scrolls the WAT (virtualized) panel to the definition row of type [typeIdx].
     * Must be called after the WAT tab is selected and rendered.
     */
    @JvmStatic
    fun navigateWatToType(fileIdentifier: String, typeIdx: Int): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        editor.requestWasmTypeNav(typeIdx)
        return payload("hexana-navigate-wat-type", listOf("typeIdx=$typeIdx"))
    }

    /**
     * Focuses the Type Graph panel on [typeIdx]. Selecting the "Type Graph"
     * editor tab is a separate [selectTab] call.
     */
    @JvmStatic
    fun showWasmTypeGraph(fileIdentifier: String, typeIdx: Int): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        editor.requestWasmTypeGraphFocus(typeIdx)
        return payload("hexana-show-wasm-type-graph", listOf("typeIdx=$typeIdx"))
    }

    /**
     * Opens the WAT (virtualized) panel's type-info popup for [typeIdx], exactly
     * as clicking a type annotation row or a GC instruction row would.
     */
    @JvmStatic
    fun showWasmTypeInfoPopup(fileIdentifier: String, typeIdx: Int): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        editor.requestWasmTypeInfoPopup(typeIdx)
        return payload("hexana-show-wasm-type-info-popup", listOf("typeIdx=$typeIdx"))
    }

    /**
     * Picks WasmGC demo targets for capture drills from the module's type graph
     * index: the struct type with a supertype that has the most direct subtypes
     * (rec-group members preferred), plus a function that references it (for the
     * instruction-popup capture). Fails on modules without GC types.
     */
    @JvmStatic
    fun wasmTypeGraphDemoTargets(fileIdentifier: String): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        val provider = editor.binaryProvider as? WasmBinaryProvider
            ?: throw IllegalStateException(
                "Expected a wasm provider for '$fileIdentifier', got ${editor.binaryProvider?.kindId()}",
            )
        val index = provider.typeGraphIndex
            ?: throw IllegalStateException("No type graph index for '$fileIdentifier'")
        if (!index.hasGcTypes) throw IllegalStateException("Module has no GC types: '$fileIdentifier'")
        // Prefer a readable focused graph: the candidate whose 2-hop neighborhood
        // is closest to ~30 nodes, not a hub type that renders as a wall of nodes.
        var best = -1
        var bestDist = Long.MAX_VALUE
        for (t in 0 until index.typeCount) {
            if (index.kind(t) != TypeGraphNodeKind.STRUCT) continue
            if (index.superTypes(t).isEmpty()) continue
            if (index.directSubtypes(t).isEmpty()) continue
            if (index.referencingFuncs(t).isEmpty()) continue
            val neighborhoodSize = computeTypeNeighborhood(
                index = index,
                focus = t,
                includeHierarchy = true,
                includeFieldRefs = true,
            ).included.size
            val dist = kotlin.math.abs(neighborhoodSize - 30).toLong() * 4 +
                (if (index.recGroup(t) >= 0) 0L else 2L)
            if (dist < bestDist) {
                bestDist = dist
                best = t
            }
        }
        if (best < 0) {
            // Dense or sparse modules: drop the referencing-funcs requirement.
            for (t in 0 until index.typeCount) {
                if (index.kind(t) != TypeGraphNodeKind.STRUCT) continue
                if (index.superTypes(t).isEmpty()) continue
                if (index.directSubtypes(t).isEmpty()) continue
                best = t
                break
            }
        }
        if (best < 0) throw IllegalStateException("No struct with supertype and subtypes in '$fileIdentifier'")
        val demoFuncIdx = index.referencingFuncs(best).firstOrNull() ?: -1
        // Hub type for the "graph opens fast on a type with >50 transitive
        // referencers" acceptance check: the smallest 1-hop neighborhood above
        // 50 (matches the acceptance bar without dragging in a 1500-node hub).
        // depth=1/hops=1 mirrors WasmTypeGraphPanel's initial state, so this
        // count equals the panel's first "showing X of Y" counter (included=).
        var hub = -1
        var hubSize = Int.MAX_VALUE
        for (t in 0 until index.typeCount) {
            if (index.kind(t) != TypeGraphNodeKind.STRUCT) continue
            val size = computeTypeNeighborhood(
                index = index,
                focus = t,
                includeHierarchy = true,
                includeFieldRefs = true,
                subtypeDepth = 1,
                fieldHops = 1,
            ).included.size
            if (size > 50 && size < hubSize) {
                hubSize = size
                hub = t
            }
        }
        if (hub < 0) hubSize = -1
        val sites = findGcInstructionSites(provider)
        return payload(
            "hexana-wasm-type-graph-demo-targets",
            listOf(
                "typeIdx=$best",
                "typeName=${provider.typeDisplayName(best)}",
                "subtypes=${index.directSubtypes(best).size}",
                "referencingTypes=${index.referencingTypes(best).size}",
                "referencingFuncs=${index.referencingFuncs(best).size}",
                "recGroup=${index.recGroup(best)}",
                "demoFuncIdx=$demoFuncIdx",
                "hubTypeIdx=$hub",
                "hubTypeName=${if (hub >= 0) provider.typeDisplayName(hub) else ""}",
                "hubNeighborhood=$hubSize",
                "totalTypes=${index.typeCount}",
                "castFuncIdx=${sites["castFuncIdx"] ?: -1}",
                "castTypeIdx=${sites["castTypeIdx"] ?: -1}",
                "structGetFuncIdx=${sites["structGetFuncIdx"] ?: -1}",
                "structGetTypeIdx=${sites["structGetTypeIdx"] ?: -1}",
            ),
        )
    }

    /**
     * Scans the code section for the first ref.cast (concrete heap type) and the
     * first struct.get, returning global funcidx + target type index for each.
     */
    private fun findGcInstructionSites(provider: WasmBinaryProvider): Map<String, Int> {
        val wasmModule = provider.wasmModule ?: return emptyMap()
        val codeSection = wasmModule.codeSection ?: return emptyMap()
        val importFuncCount = provider.importFuncCount
        val result = HashMap<String, Int>()
        fun walk(opcodes: List<OpCode>, funcIdx: Int) {
            for (op in opcodes) {
                when (op) {
                    is OpCodes.GC.RefCast, is OpCodes.GC.RefCastNullable -> {
                        if (!result.containsKey("castFuncIdx")) {
                            // Concrete heap types carry the type index in WasmHeapType.type
                            // (>= 0); abstract heap types are negative S33 values.
                            val t = when (op) {
                                is OpCodes.GC.RefCast -> op.value.type
                                is OpCodes.GC.RefCastNullable -> op.value.type
                                else -> null
                            }
                            if (t != null && t in 0..Int.MAX_VALUE.toLong()) {
                                result["castFuncIdx"] = funcIdx
                                result["castTypeIdx"] = t.toInt()
                            }
                        }
                    }
                    is OpCodes.GC.StructGet -> {
                        if (!result.containsKey("structGetFuncIdx")) {
                            result["structGetFuncIdx"] = funcIdx
                            result["structGetTypeIdx"] = op.typeIndex.toInt()
                        }
                    }
                    is OpCodes.Flow.Block -> walk(op.value, funcIdx)
                    is OpCodes.Flow.Loop -> walk(op.value, funcIdx)
                    is OpCodes.Flow.If -> walk(op.value, funcIdx)
                    is OpCodes.Flow.Else -> walk(op.value, funcIdx)
                    is OpCodes.Flow.TryTable -> walk(op.code, funcIdx)
                    else -> {}
                }
                if (result.size >= 4) return
            }
        }
        val codeEntryOffsets = with(codeSection) { provider.bytes.scanOffsets() }
        for ((localIdx, entryOffset) in codeEntryOffsets.withIndex()) {
            val entry = with(codeSection) { provider.bytes.parseCodeEntry(entryOffset.offset) }
            walk(entry.opCodes, importFuncCount + localIdx)
            if (result.size >= 4) break
        }
        return result
    }

    /**
     * Reads a wasm UI-state breadcrumb published by the wasm panels (keys:
     * "typeGraph", "typeNavResult", "typeInfo"). Empty value = not published yet.
     */
    @JvmStatic
    fun wasmUiProbeValue(fileIdentifier: String, key: String): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        val value = editor.wasmUiProbeState[key]
        return payload("hexana-wasm-ui-probe-value", listOf("key=$key", "value=${value.orEmpty()}"))
    }

    /**
     * Reads back the breakpoint state the gutter renders from for [fileIdentifier]:
     * whether the editor's [org.jetbrains.hexana.ui.panel.GutterMarksModel] is wired,
     * the editor's cached identity, how many registry breakpoints match that identity,
     * and the keys currently in the marks map. Capture tests gate the screenshot on
     * the expected mark key being present instead of sleeping and hoping.
     */
    @JvmStatic
    fun binaryGutterMarks(fileIdentifier: String): List<String> {
        val candidate = requireHexanaEditorCandidate(fileIdentifier)
        val editor = candidate.editor
        val identity = editor.cachedIdentity
        val model = editor.gutterMarksModelFlow.value
        val marks = model?.marks?.value ?: emptyMap()
        val registry = BinaryBreakpointRegistry.getInstance(candidate.project)
        val allBps = registry.breakpoints.value
        val matching = allBps.filter { bp -> identity != null && identity.matches(bp.identity) }
        return payload(
            "hexana-gutter-marks",
            buildList {
                add("modelReady=${model != null}")
                add("identitySha=${identity?.contentSha256?.take(12).orEmpty()}")
                add("registryTotal=${allBps.size}")
                add("registryMatching=${matching.size}")
                matching.take(8).forEach { bp ->
                    val key = when (val anchor = bp.anchor) {
                        is BinaryAnchor.Wasm -> anchor.byteOffset
                        is BinaryAnchor.Native -> anchor.virtualAddress.toLong()
                    }
                    add("registryKey=0x${key.toString(16)}")
                }
                add("markCount=${marks.size}")
                marks.keys.sorted().take(16).forEach { add("markKey=0x${it.toString(16)}") }
            },
        )
    }

    @JvmStatic
    fun nativeDisassemblyInstructions(fileIdentifier: String, query: String, maxLines: Int): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        val provider = editor.binaryProvider as? NativeBinaryProvider
            ?: throw IllegalStateException(
                "Expected a native binary provider for '$fileIdentifier', got ${editor.binaryProvider.kindId()}",
            )
        val discovery = discoverDisasmRanges(provider)
            ?: throw IllegalStateException("No disassembly ranges discovered for '$fileIdentifier'")
        // The panel search lists ranges whose displayName contains the query, in range
        // order. Prefer the first non-trivial (>= 16 bytes) match so the drilled body
        // renders multiple instruction rows; resultIndex is the target's position in
        // that search result list so the UI can arrow-key to it deterministically.
        val matches = discovery.ranges.withIndex()
            .filter { (_, r) -> r.displayName.contains(query, ignoreCase = true) }
        val targetMatch = matches.firstOrNull { (_, r) -> r.size >= 16 } ?: matches.firstOrNull()
        val resultIndex = targetMatch?.let { matches.indexOf(it) } ?: -1
        val rangeIndex = targetMatch?.index
            ?: discovery.ranges.indexOfFirst { it.size >= 16 }.takeIf { it >= 0 }
            ?: 0
        val range = discovery.ranges[rangeIndex]
        val loadState = runBlocking { loadCapstoneDisassembly(discovery) }
        val catalog = (loadState as? DisasmLoadState.Loaded)?.catalog
            ?: throw IllegalStateException(
                "Capstone load failed for '$fileIdentifier': ${(loadState as? DisasmLoadState.Error)?.message}",
            )
        val instructions = runBlocking { catalog.decode(rangeIndex) }
        return payload(
            "hexana-native-disasm-instructions",
            buildList {
                add("query=$query")
                add("resolvedName=${range.displayName}")
                add("rangeIndex=$rangeIndex")
                add("resultIndex=$resultIndex")
                add("virtualAddress=0x${range.virtualAddress.toString(16)}")
                add("sizeBytes=${range.size}")
                add("arch=${discovery.arch.name}")
                add("instructionCount=${instructions.size}")
                instructions.take(maxLines).forEach { insn ->
                    add("insn=0x${insn.virtualAddress.toString(16)}|${insn.mnemonic}|${insn.operands}")
                }
            },
        )
    }

    /**
     * Decodes every discovered range and returns aggregate statistics: total instruction
     * count, `.byte` fallback count, odd-virtual-address range count (must be zero once
     * Thumb bit-0 masking works), and per-range worst offenders by fallback count.
     * Bounded by [maxRanges] to keep a single probe call within the driver call timeout.
     */
    @JvmStatic
    fun nativeDisassemblySummary(fileIdentifier: String, maxRanges: Int): List<String> {
        val editor = requireHexanaEditor(fileIdentifier)
        val provider = editor.binaryProvider as? NativeBinaryProvider
            ?: throw IllegalStateException(
                "Expected a native binary provider for '$fileIdentifier', got ${editor.binaryProvider.kindId()}",
            )
        val discovery = discoverDisasmRanges(provider)
            ?: throw IllegalStateException("No disassembly ranges discovered for '$fileIdentifier'")
        val loadState = runBlocking { loadCapstoneDisassembly(discovery) }
        val catalog = (loadState as? DisasmLoadState.Loaded)?.catalog
            ?: throw IllegalStateException(
                "Capstone load failed for '$fileIdentifier': ${(loadState as? DisasmLoadState.Error)?.message}",
            )
        val rangeCount = discovery.ranges.size
        val decodedRangeCount = minOf(rangeCount, maxRanges)
        var insnCount = 0L
        var byteFallbackCount = 0L
        var oddVaRangeCount = 0
        var thumbOverrideCount = 0
        val offenders = mutableListOf<Triple<String, Int, Int>>()
        for (rangeIndex in 0 until decodedRangeCount) {
            val range = discovery.ranges[rangeIndex]
            if (range.virtualAddress and 1uL == 1uL) oddVaRangeCount++
            if (range.archOverride == Arch.THUMB) thumbOverrideCount++
            val instructions = runBlocking { catalog.decode(rangeIndex) } ?: continue
            insnCount += instructions.size
            val bytes = instructions.count { it.mnemonic == ".byte" }
            byteFallbackCount += bytes
            if (bytes > 0) offenders += Triple(range.displayName, bytes, instructions.size)
        }
        offenders.sortByDescending { it.second }
        return payload(
            "hexana-native-disasm-summary",
            buildList {
                add("arch=${discovery.arch.name}")
                add("rangeCount=$rangeCount")
                add("decodedRangeCount=$decodedRangeCount")
                add("insnCount=$insnCount")
                add("byteFallbackCount=$byteFallbackCount")
                add("oddVaRangeCount=$oddVaRangeCount")
                add("thumbOverrideCount=$thumbOverrideCount")
                offenders.take(5).forEach { (name, bytes, insns) ->
                    add("offender=$name|$bytes/$insns")
                }
            },
        )
    }

    @JvmStatic
    fun componentDiffState(fileName: String): List<String> = runOnEdt {
        val editor = requireHexanaEditor(fileName)
        val file = editor.myFile as? HexanaDiffVirtualFile
            ?: throw IllegalStateException("Expected diff virtual file for '$fileName', got ${editor.myFile::class.java.name}")
        val result = WasmComponentDiffOrchestrator.diff(
            WasmComponentBinaryProvider(file.input.left.duplicate()),
            WasmComponentBinaryProvider(file.input.right.duplicate()),
        ) ?: throw IllegalStateException("Could not compute component diff for '$fileName'")
        payload(
            "hexana-component-diff-state",
            buildList {
                add("fileName=${file.name}")
                add("leftName=${file.input.leftName}")
                add("rightName=${file.input.rightName}")
                add("totalPairings=${result.pairings.size}")
                add("differingCount=${result.differingCount}")
                result.pairings.forEachIndexed { index, pairing ->
                    val label = pairing.left?.label ?: pairing.right?.label.orEmpty()
                    add("pairing=$index|${pairing.kind}|${pairing.status}|$label")
                }
            },
        )
    }

    @JvmStatic
    fun nativeDiffState(fileName: String): List<String> = runOnEdt {
        val editor = requireHexanaEditor(fileName)
        require(editor.myFile is HexanaDiffVirtualFile) {
            "Expected diff virtual file for '$fileName', got ${editor.myFile::class.java.name}"
        }
        val state = editor.nativeDiffStateForUiTest()
        payload(
            NATIVE_DIFF_STATE_SCHEMA,
            buildList {
                add("status=${state.status}")
                add("changedRowCount=${state.changedRowCount}")
                add("error=${state.error.orEmpty()}")
                state.sections.forEach { add("section=$it") }
            },
        )
    }

    @JvmStatic
    fun archiveState(fileIdentifier: String): List<String> = runOnEdt {
        val editor = requireHexanaEditor(fileIdentifier)
        val memberEntries = when (val provider = editor.binaryProvider) {
            is ArchiveBinaryProvider -> provider.members
                .filterNot { it.isBookkeeping }
                .mapIndexed { index, member ->
                    val classification = provider.classifyMember(member)
                    "member=$index|${member.name}|${classification.displayName}|${member.size}|openable=${classification.openable}" to classification.openable
                }
            is ZipBinaryProvider -> provider.displayEntries.mapIndexed { index, entry ->
                "member=$index|${entry.name}|ZIP entry|${entry.uncompressedSize}|openable=false" to false
            }
            else -> throw IllegalStateException(
                "Expected archive provider for '$fileIdentifier', got ${editor.binaryProvider.kindId()}",
            )
        }
        payload(
            "hexana-archive-state",
            buildList {
                add("fileName=${editor.myFile.name}")
                add("memberCount=${memberEntries.size}")
                add("openableCount=${memberEntries.count { it.second }}")
                memberEntries.forEach { add(it.first) }
            },
        )
    }

    /**
     * Reports what the resolved table tab named [tabName] actually rendered:
     * its column headers and prologue text, as published by
     * `BinaryEditorView` when the tab replaced its placeholder.
     *
     * Deliberately *not* rebuilt from the live provider. A rebuilt descriptor
     * would reflect the provider's state at probe time, so a tab that resolved
     * before a late input was applied (e.g. a pcap SSL key log installed after
     * tab resolution rather than before it) would still look correct.
     *
     * `resolved=false` means no tab of that name has been published yet —
     * either resolution is still running, or the tab resolved to nothing.
     */
    @JvmStatic
    fun tableTabState(fileIdentifier: String, tabName: String): List<String> = runOnEdt {
        val editor = requireHexanaEditor(fileIdentifier)
        val columns = editor.stateHolder.tableTabColumns[tabName]
        val prologue = editor.stateHolder.tableTabPrologues[tabName]
        payload(
            TABLE_TAB_STATE_SCHEMA,
            buildList {
                add("fileName=${editor.myFile.name}")
                add("tabName=$tabName")
                add("resolved=${columns != null}")
                // Newlines would split a single value across wire entries.
                add("prologue=${prologue?.replace('\n', '|').orEmpty()}")
                columns.orEmpty().forEach { add("column=$it") }
            },
        )
    }

    @JvmStatic
    fun editorSnapshot(): List<String> = runOnEdt {
        val editor = selectedHexanaEditor()
            ?: return@runOnEdt payload(
                SNAPSHOT_SCHEMA,
                listOf(
                    "fileName=",
                    "providerKind=missing",
                    "selectedTabName=",
                    "cursorOffset=",
                ),
            )
        editor.snapshot()
    }

    @JvmStatic
    fun editorSnapshot(fileIdentifier: String): List<String> =
        runOnEdt { requireHexanaEditor(fileIdentifier).snapshot() }

    @JvmStatic
    fun searchState(): List<String> = runOnEdt {
        val editor = selectedHexanaEditor() ?: return@runOnEdt missingSearchState()
        editor.searchState()
    }

    @JvmStatic
    fun searchState(fileIdentifier: String): List<String> =
        runOnEdt { requireHexanaEditor(fileIdentifier).searchState() }

    @JvmStatic
    fun watState(): List<String> = runOnEdt {
        val editor = selectedHexanaEditor() ?: return@runOnEdt missingWatState()
        editor.watState()
    }

    @JvmStatic
    fun watState(fileIdentifier: String): List<String> =
        runOnEdt { requireHexanaEditor(fileIdentifier).watState() }

    @JvmStatic
    fun showFindUsagesForComponentDeclaration(fileIdentifier: String): List<String> = runOnEdt {
        val hexanaEditor = requireHexanaEditor(fileIdentifier)
        val watEditor = hexanaEditor.stateHolder.editor
            ?: error("The embedded WAT editor is not ready for '$fileIdentifier'")
        val watFile = FileDocumentManager.getInstance().getFile(watEditor.document) as? LightWatFile
            ?: error("Expected a LightWatFile in the embedded WAT editor for '$fileIdentifier'")
        val psiFile = PsiDocumentManager.getInstance(hexanaEditor.myProject).getPsiFile(watEditor.document)
            ?: error("Could not resolve PSI for embedded WAT file ${watFile.name}")
        val candidate = ApplicationManager.getApplication().runReadAction<WatComponentElement?> {
            SyntaxTraverser.psiTraverser(psiFile)
                .filter(WatComponentElement::class.java)
                .filter { !it.name.isNullOrBlank() }
                .map { component ->
                    component to ReferencesSearch.search(component, component.useScope).findAll().size
                }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first
        } ?: error("No referenced named component declaration was found in ${watFile.name}")
        val expectedUsages = ApplicationManager.getApplication().runReadAction<Int> {
            ReferencesSearch.search(candidate, candidate.useScope).findAll().size
        }
        val declarationOffset = candidate.nameIdentifier?.textOffset ?: candidate.textOffset
        watEditor.caretModel.moveToOffset(declarationOffset)
        watEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        watEditor.contentComponent.requestFocusInWindow()
        WatUsageTarget(candidate).findUsages()

        payload(
            "hexana-wat-find-usages-invocation",
            listOf(
                "fileName=${watFile.name}",
                "targetName=${candidate.name.orEmpty()}",
                "declarationOffset=$declarationOffset",
                "expectedUsages=$expectedUsages",
            ),
        )
    }

    @JvmStatic
    fun watFindUsagesCaptureState(): List<String> = runOnEdt {
        val project = singleOpenProject()
        val usageView = UsageViewManager.getInstance(project).selectedUsageView
        val findToolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.FIND)
        payload(
            WAT_FIND_USAGES_CAPTURE_STATE_SCHEMA,
            listOf(
                "toolWindowVisible=${findToolWindow?.isVisible == true}",
                "searchInProgress=${usageView?.isSearchInProgress ?: true}",
                "usageCount=${usageView?.usagesCount ?: 0}",
                "tabText=${usageView?.presentation?.tabText.orEmpty()}",
                "searchString=${usageView?.presentation?.searchString.orEmpty()}",
            ),
        )
    }

    private fun HexanaFileEditor.snapshot(): List<String> {
        val provider = binaryProvider
        val panelNames = panelNames()
        val selectedTabName = panelNames.getOrNull(stateHolder.currentState.value.tabIndex)
        val cursorOffset = cursorOffset()

        return payload(
            SNAPSHOT_SCHEMA,
            buildList {
                add("fileName=${myFile.name}")
                add("providerKind=${provider.kindId()}")
                add("selectedTabName=${selectedTabName.orEmpty()}")
                add("cursorOffset=${cursorOffset?.toString().orEmpty()}")
                add("panelsResolved=${stateHolder.panelsResolved}")
                panelNames.forEach { add("panel=$it") }
            },
        )
    }

    private fun requireHexanaEditor(fileIdentifier: String): HexanaFileEditor =
        requireHexanaEditorCandidate(fileIdentifier).editor

    private fun requireHexanaEditorCandidate(fileIdentifier: String): HexanaEditorCandidate =
        findHexanaEditorCandidate(fileIdentifier)
            ?: throw IllegalStateException(
                "Expected Hexana editor for '$fileIdentifier', but selected/open Hexana editors are: ${
                    selectedHexanaEditorDescriptions().ifEmpty { listOf("<none>") }.joinToString()
                }",
            )

    private fun selectedHexanaEditor(): HexanaFileEditor? {
        fun findEditor(): HexanaFileEditor? =
            selectedHexanaEditorCandidates().firstOrNull()?.editor

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return findEditor()
        }

        val result = arrayOfNulls<HexanaFileEditor>(1)
        app.invokeAndWait {
            result[0] = findEditor()
        }
        return result[0]
    }

    private fun findHexanaEditorCandidate(fileIdentifier: String): HexanaEditorCandidate? =
        runOnEdt {
            val request = FileLookupRequest.parse(fileIdentifier)
            selectedHexanaEditorCandidates()
                .firstOrNull { request.matches(it) }
        }

    private fun selectedHexanaEditorDescriptions(): List<String> =
        runOnEdt {
            selectedHexanaEditorCandidates()
                .map { it.describe() }
                .distinct()
                .toList()
        }

    private fun selectedHexanaEditorCandidates(): Sequence<HexanaEditorCandidate> =
        ProjectManager.getInstance().openProjects.asSequence()
            .flatMap { project ->
                val manager = FileEditorManager.getInstance(project)
                sequence {
                    yield(manager.selectedEditor)
                    yieldAll(manager.selectedEditors.asSequence())
                    yieldAll(manager.allEditors.asSequence())
                }.mapNotNull { editor ->
                    editor.asHexanaEditor()?.let { HexanaEditorCandidate(project, it) }
                }
            }

    private fun singleOpenProject(): Project =
        ProjectManager.getInstance().openProjects.singleOrNull()
            ?: throw IllegalStateException(
                "Expected exactly one open project, got ${ProjectManager.getInstance().openProjects.size}",
            )

    private fun resolveProjectFile(path: String): VirtualFile {
        val normalized = path.removePrefix("./")
        val basePath = singleOpenProject().basePath
            ?: throw IllegalStateException("Open project has no base path")
        val file = VfsUtil.findFile(Path.of(basePath).resolve(normalized).normalize(), true)
        return file ?: throw IllegalStateException("Could not find project file: $path")
    }

    private fun resolveAbsoluteFile(path: String): VirtualFile {
        val normalized = Path.of(path).toAbsolutePath().normalize()
        return VfsUtil.findFile(normalized, true)
            ?: throw IllegalStateException("Could not find file for native diff screenshot capture: $normalized")
    }

    private fun FileEditor?.asHexanaEditor(): HexanaFileEditor? =
        this as? HexanaFileEditor

    private data class HexanaEditorCandidate(
        val project: Project,
        val editor: HexanaFileEditor,
    ) {
        val filePath: String = editor.myFile.path.toSystemIndependentPath()
        val projectRelativePath: String? = project.basePath
            ?.toSystemIndependentPath()
            ?.removeSuffix("/")
            ?.let { basePath ->
                when {
                    filePath == basePath -> ""
                    filePath.startsWith("$basePath/") -> filePath.substring(basePath.length + 1)
                    else -> null
                }
            }

        fun describe(): String =
            "project=${project.name}, name=${editor.myFile.name}, " +
                "relative=${projectRelativePath ?: "<outside project>"}, path=$filePath"
    }

    private sealed interface FileLookupRequest {
        fun matches(candidate: HexanaEditorCandidate): Boolean

        data class ProjectRelative(val path: String) : FileLookupRequest {
            override fun matches(candidate: HexanaEditorCandidate): Boolean =
                candidate.projectRelativePath == path
        }

        data class Absolute(val path: String) : FileLookupRequest {
            override fun matches(candidate: HexanaEditorCandidate): Boolean =
                candidate.filePath == path
        }

        data class LegacyFileName(val name: String) : FileLookupRequest {
            override fun matches(candidate: HexanaEditorCandidate): Boolean =
                candidate.editor.myFile.name == name
        }

        companion object {
            fun parse(identifier: String): FileLookupRequest =
                when {
                    identifier.startsWith(PROJECT_RELATIVE_FILE_IDENTIFIER_PREFIX) -> ProjectRelative(
                        identifier.removePrefix(PROJECT_RELATIVE_FILE_IDENTIFIER_PREFIX).normalizeRelativeIdentifier(),
                    )
                    identifier.startsWith(ABSOLUTE_FILE_IDENTIFIER_PREFIX) -> Absolute(
                        identifier.removePrefix(ABSOLUTE_FILE_IDENTIFIER_PREFIX).toSystemIndependentPath(),
                    )
                    else -> LegacyFileName(identifier)
                }
        }
    }

    private fun String.normalizeRelativeIdentifier(): String =
        toSystemIndependentPath().removePrefix("./")

    private fun String.toSystemIndependentPath(): String =
        replace('\\', '/')

    private fun <T> runOnEdt(action: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return action()
        }

        var result: Result<T>? = null
        app.invokeAndWait {
            result = runCatching(action)
        }
        return result!!.getOrThrow()
    }

    private fun HexanaFileEditor.cursorOffset(): Int? {
        val cursor = stateHolder.cursorPosition
        if (cursor.line >= 0 && cursor.lineOffset >= 0) {
            // UI tests open small fixtures, so the offset fits the probe's Int contract.
            return (cursor.getByteOffset(stateHolder.bytesPerRow.value) + stateHolder.displayBaseOffset).toInt()
        }
        return stateHolder.editor?.caretModel?.offset
    }

    private fun HexanaFileEditor.searchState(): List<String> =
        payload(
            SEARCH_STATE_SCHEMA,
            buildList {
                add("query=${stateHolder.searchText.value}")
                add("binaryMode=${stateHolder.searchBinaryMode}")
                add("resultCount=${stateHolder.searchResults.value.size}")
                add("currentResultIndex=${stateHolder.currentSearchIndex.value}")
                add("cursorOffset=${cursorOffset()?.toString().orEmpty()}")
            },
        )

    private fun missingSearchState(): List<String> =
        payload(
            SEARCH_STATE_SCHEMA,
            listOf(
                "query=",
                "binaryMode=false",
                "resultCount=0",
                "currentResultIndex=-1",
                "cursorOffset=",
            ),
        )

    private fun HexanaFileEditor.watState(): List<String> {
        val panelNames = panelNames()
        val selectedTabName = panelNames.getOrNull(stateHolder.currentState.value.tabIndex).orEmpty()
        val conversionState = stateHolder.conversionStateFlow.value
        val watText = conversionState.watText() ?: stateHolder.editor?.document?.text
        val error = (conversionState as? WatConversionState.Error)?.error

        return payload(
            WAT_STATE_SCHEMA,
            buildList {
                add("selectedTabName=$selectedTabName")
                add("conversionState=${conversionState.name()}")
                add("containsModuleText=${watText?.contains("(module") == true}")
                add("containsFunctionText=${watText?.contains("(func") == true}")
                add("textSummary=${watText?.summary().orEmpty()}")
                add("error=${error.orEmpty()}")
            },
        )
    }

    private fun missingWatState(): List<String> =
        payload(
            WAT_STATE_SCHEMA,
            listOf(
                "selectedTabName=",
                "conversionState=missing",
                "containsModuleText=false",
                "containsFunctionText=false",
                "textSummary=",
                "error=",
            ),
        )

    private fun WatConversionState?.name(): String = when (this) {
        null -> "not-started"
        WatConversionState.Loading -> "loading"
        is WatConversionState.ShowEditor -> "show-editor"
        is WatConversionState.ShowLiteEditor -> "show-lite-editor"
        is WatConversionState.ShowMultifileEditor -> "show-multifile-editor"
        is WatConversionState.Error -> "error"
    }

    private fun WatConversionState?.watText(): String? = when (this) {
        is WatConversionState.ShowLiteEditor -> wat
        is WatConversionState.ShowMultifileEditor -> fileset.currentFile.contentsToByteArray().toString(Charsets.UTF_8)
        else -> null
    }

    private fun String.summary(): String =
        lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(" | ")
            .take(240)

    private fun HexanaFileEditor.panelNames(): List<String> {
        // Prefer the live tab names published by BinaryEditorView: they reflect
        // what is actually rendered (placeholder removals, promoted "Group: Sub"
        // grouped tabs). The synthetic reconstruction below remains only as a
        // fallback for editors probed before their panel list composes.
        val livePanelNames = stateHolder.panelNames
        if (livePanelNames.isNotEmpty()) return livePanelNames
        val provider = binaryProvider ?: return emptyList()
        val tablePanelNames = TableTabsProviderRegistry.providers.flatMap { tabsProvider ->
            tabsProvider.tableTabs(provider).map { it.name }
        }
        val extraPanelNames = when (provider) {
            is GenericBinaryProvider -> emptyList()
            is WasmBinaryVirtualFileProvider -> {
                if (provider.canProvideWasmModule()) {
                    listOf("Top", "Monos", "Garbage", "Dominators", "Wat", "WAT (virtualized)") +
                        dwarfPanels(provider, stateHolder).map { it.description }
                } else {
                    listOf("Something is wrong :(", "Wat")
                }
            }
            is WasmComponentBinaryVirtualFileProvider -> {
                if (provider.canProvideWasmModule()) {
                    listOf("Dependencies", "Wat", "WAT (virtualized)")
                } else {
                    listOf("Something is wrong :(", "Wat")
                }
            }
            is WasmBinaryProvider -> {
                if (provider.canProvideWasmModule()) {
                    listOf("Top", "Monos", "Garbage", "Dominators", "WAT (virtualized)") +
                        dwarfPanels(provider, stateHolder).map { it.description }
                } else {
                    emptyList()
                }
            }
            is WasmComponentBinaryProvider -> {
                if (provider.canProvideWasmModule()) listOf("Dependencies", "WAT (virtualized)") else emptyList()
            }
            is ArchiveBinaryProvider -> listOf("Members")
            is PeBinaryProvider -> buildList {
                add("Top")
                if (provider.isManaged) add(CLR_IL_TAB_NAME)
                if (!provider.hasOnlyManagedCode) addAll(listOf("Disassembly", "Disassembly (virtualized)"))
            }
            is NativeBinaryProvider -> listOf("Top", "Disassembly", "Disassembly (virtualized)")
            else -> emptyList()
        }
        val scriptPanelNames = ScriptTabSettings.getInstance(myProject)
            .getScriptPaths(myFile.name)
            .map { it.substringAfterLast('/') }
        return (tablePanelNames + extraPanelNames + scriptPanelNames).distinct()
    }

    private fun BinaryProvider?.kindId(): String =
        when (this) {
            null -> "missing"
            is WasmComponentBinaryProvider -> "component-wasm"
            is WasmBinaryProvider -> "core-wasm"
            is GenericBinaryProvider -> "generic"
            else -> this::class.java.simpleName.ifEmpty { "unknown" }
        }

    private fun payload(schema: String, entries: List<String>): List<String> =
        buildList {
            add("schema=$schema")
            add("version=$WIRE_VERSION")
            addAll(entries)
        }
}
