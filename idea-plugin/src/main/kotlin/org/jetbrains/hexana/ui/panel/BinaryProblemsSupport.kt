package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.features.RuntimeTargetsService
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.shared.ProblemMenuItem
import org.jetbrains.hexana.shared.ProblemNavigator
import org.jetbrains.hexana.shared.inspections.BinaryProblem
import org.jetbrains.hexana.shared.inspections.InspectionContext
import org.jetbrains.hexana.shared.inspections.selectionIn
import org.jetbrains.hexana.shared.inspections.toBinaryProblem

/**
 * The problems only IntelliJ knows about for the file at [filePath]: the findings of the
 * script checks last run against it, from [ScriptFindingsStore].
 */
internal fun scriptCheckProblems(project: Project, filePath: String?): List<BinaryProblem> =
    ScriptFindingsStore.getInstance(project).findingsFor(filePath).map { it.toBinaryProblem() }

/**
 * Everything the shared inspections need from IntelliJ — the same inputs the Problems tab
 * is built from, so the toolbar badge and the tab count the same problems.
 */
internal fun ideInspectionContext(project: Project, filePath: String?): InspectionContext =
    InspectionContext(
        runtimeCompatibility = RuntimeTargetsService.getInstance(project).compatibility(),
        hostProblems = scriptCheckProblems(project, filePath),
    )

/**
 * How a Problems row navigates in IntelliJ. A problem inside a Wasm function opens it in
 * the virtualized WAT view; anything else with a position scrolls the hex view to it. A
 * runtime-compatibility problem selects the Compatibility tab instead.
 * Wasm modules additionally get the two WAT context-menu entries.
 */
internal fun BinaryProvider.problemNavigator(state: HexViewStateHolder): ProblemNavigator {
    val wasm = this as? WasmBinaryVirtualFileProvider
    val project = state.project

    val fileSize = bytes.size

    fun revealBytes(problem: BinaryProblem) {
        val selection = problem.byteRange?.selectionIn(fileSize) ?: return
        state.scrollHexViewTo(selection.offset, selection.size)
    }

    fun showInPlainWat(problem: BinaryProblem) {
        val provider = wasm ?: return
        val selection = problem.byteRange?.selectionIn(fileSize) ?: return
        // The plain Wat tab's editor scrolls to the nearest annotated line through the same
        // channel the virtualized panel uses for structural navigation.
        FileEditorManager.getInstance(project).openFile(provider.file, false)
            .filterIsInstance<HexanaFileEditor>().firstOrNull()
            // A Wasm module is far below 2 GiB, so its offsets fit an Int.
            ?.requestWasmByteOffsetNav(selection.offset.toInt())
        state.requestSelectTabByDescription("Wat")
    }

    return ProblemNavigator(
        open = { problem ->
            val funcIdx = problem.funcIdx
            if (funcIdx != null && wasm != null) {
                navigateToFunctionInWat(wasm.file, project, funcIdx)
            } else {
                revealBytes(problem)
            }
        },
        menuItems = if (wasm == null) emptyList() else listOf(
            ProblemMenuItem(
                label = "Show in WAT (virtualized)",
                isVisible = { it.funcIdx != null },
                action = { problem -> problem.funcIdx?.let { navigateToFunctionInWat(wasm.file, project, it) } },
            ),
            ProblemMenuItem(
                label = "Show in Wat",
                isVisible = { it.byteRange?.selectionIn(fileSize) != null },
                action = ::showInPlainWat,
            ),
        ),
        selectTab = state::requestSelectTabByDescription,
    )
}
