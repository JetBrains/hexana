package org.jetbrains.hexana.uitestframework

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryVirtualFileProvider
import org.jetbrains.hexana.dwarf.ui.dwarfPanels
import org.jetbrains.hexana.shared.TableTabsProviderRegistry
import org.jetbrains.hexana.ui.panel.WatConversionState

private const val WIRE_VERSION = "1"
private const val SNAPSHOT_SCHEMA = "hexana-editor-snapshot"
private const val SEARCH_STATE_SCHEMA = "hexana-editor-search-state"
private const val WAT_STATE_SCHEMA = "hexana-editor-wat-state"
private const val PROJECT_RELATIVE_FILE_IDENTIFIER_PREFIX = "project-relative:"
private const val ABSOLUTE_FILE_IDENTIFIER_PREFIX = "absolute:"

object HexanaEditorUiTestProbe {
    @JvmStatic
    fun frameworkVersion(): String = "1"

    @JvmStatic
    fun ping(): String = "pong"

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
                panelNames.forEach { add("panel=$it") }
            },
        )
    }

    private fun requireHexanaEditor(fileIdentifier: String): HexanaFileEditor =
        findHexanaEditor(fileIdentifier)
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

    private fun findHexanaEditor(fileIdentifier: String): HexanaFileEditor? =
        runOnEdt {
            val request = FileLookupRequest.parse(fileIdentifier)
            selectedHexanaEditorCandidates()
                .firstOrNull { request.matches(it) }
                ?.editor
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
                    listOf("Top", "Wat")
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
                if (provider.canProvideWasmModule()) listOf("Top") else emptyList()
            }
            else -> emptyList()
        }
        return (tablePanelNames + extraPanelNames).distinct()
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
