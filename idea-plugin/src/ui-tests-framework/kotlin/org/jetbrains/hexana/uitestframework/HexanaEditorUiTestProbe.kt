package org.jetbrains.hexana.uitestframework

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
import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.HexanaComponentDiffVirtualFile
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaNativeDiffVirtualFile
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryVirtualFileProvider
import org.jetbrains.hexana.providers.classifyArchiveMember
import org.jetbrains.hexana.providers.format.WasmComponentDiffOrchestrator
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
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

        val diffFile = HexanaComponentDiffVirtualFile(
            name = "${left.name} ↔ ${right.name}",
            leftBuffer = leftBytes,
            rightBuffer = rightBytes,
            leftName = left.name,
            rightName = right.name,
        )
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
    fun openNativeDiff(leftPath: String, rightPath: String): List<String> {
        val left = resolveAbsoluteFile(leftPath)
        val right = resolveAbsoluteFile(rightPath)
        val diffFile = HexanaNativeDiffVirtualFile(
            name = "${left.name} ↔ ${right.name}",
            leftBuffer = left.contentsToByteArray().toCommonByteBuffer(),
            rightBuffer = right.contentsToByteArray().toCommonByteBuffer(),
            leftName = left.name,
            rightName = right.name,
        )
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
        val file = editor.myFile as? HexanaComponentDiffVirtualFile
            ?: throw IllegalStateException("Expected component diff virtual file for '$fileName', got ${editor.myFile::class.java.name}")
        val result = WasmComponentDiffOrchestrator.diff(
            WasmComponentBinaryProvider(file.leftBuffer.duplicate()),
            WasmComponentBinaryProvider(file.rightBuffer.duplicate()),
        ) ?: throw IllegalStateException("Could not compute component diff for '$fileName'")
        payload(
            "hexana-component-diff-state",
            buildList {
                add("fileName=${file.name}")
                add("leftName=${file.leftName}")
                add("rightName=${file.rightName}")
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
        require(editor.myFile is HexanaNativeDiffVirtualFile) {
            "Expected native diff virtual file for '$fileName', got ${editor.myFile::class.java.name}"
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
        val provider = editor.binaryProvider as? ArchiveBinaryProvider
            ?: throw IllegalStateException("Expected archive provider for '$fileIdentifier', got ${editor.binaryProvider.kindId()}")
        val members = provider.members.filterNot { it.isBookkeeping }
        val classifications = members.map { member ->
            member to classifyArchiveMember(provider.bytes, member.contentRange)
        }
        payload(
            "hexana-archive-state",
            buildList {
                add("fileName=${editor.myFile.name}")
                add("memberCount=${members.size}")
                add("openableCount=${classifications.count { it.second.openable }}")
                classifications.forEachIndexed { index, (member, cls) ->
                    add("member=$index|${member.name}|${cls.displayName}|${member.size}|openable=${cls.openable}")
                }
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
            return cursor.getByteOffset(stateHolder.bytesPerRow.value) + stateHolder.displayBaseOffset
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
