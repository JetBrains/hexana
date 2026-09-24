package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.shared.diff.BinaryDiffProviderRegistry
import org.jetbrains.hexana.shared.diff.BinaryDiffSession
import org.jetbrains.hexana.shared.diff.DiffDetailSource
import org.jetbrains.hexana.shared.diff.DiffItemRef
import org.jetbrains.hexana.shared.diff.DiffOpenRequest
import org.jetbrains.hexana.shared.diff.DiffTabContent
import org.jetbrains.hexana.shared.diff.DiffTabContext
import org.jetbrains.hexana.shared.diff.DiffTextLine
import org.jetbrains.hexana.shared.diff.SideBySideDiff

/**
 * The VS Code diff view for every format — the counterpart of IntelliJ's
 * `BinaryDiffEditorView`. Tabs and the detail pane come from the format's
 * [org.jetbrains.hexana.shared.diff.BinaryDiffProvider]; nothing here is
 * format-specific.
 *
 * A row that opens another diff (a pair of component submodules) pushes it onto an
 * in-webview stack with a back link, rather than asking the extension for a new
 * editor; a single binary (a one-sided submodule) opens through [Host.openModule].
 * Each stack entry owns its [DiffScreenState], so going back restores the parent as it
 * was — computed tabs, selected tab and row — instead of re-running its diff.
 */
@Composable
fun BinaryDiffView(input: BinaryDiffInput) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val stack = remember(input) {
        val entries = mutableStateListOf<DiffScreenState>()
        lateinit var open: (DiffOpenRequest) -> Unit
        open = { request ->
            scope.launch {
                // The request's factory copies the submodule bytes out of the parent; let the
                // click paint first and run it on the macrotask dispatcher, off the composition.
                awaitAnimationFrame()
                when (request) {
                    is DiffOpenRequest.Diff -> {
                        val child = withContext(Dispatchers.Default) { request.input() }
                        entries.add(DiffScreenState(child, scope, open))
                    }
                    is DiffOpenRequest.Single -> {
                        val bytes = withContext(Dispatchers.Default) { request.bytes().toByteArray() }
                        host.openModule(0, request.title, bytes)
                    }
                }
            }
        }
        entries.add(DiffScreenState(input, scope, open))
        entries
    }
    DisposableEffect(input) { onDispose { stack.forEach { it.dispose() } } }

    val current = stack.last()
    key(current) {
        DiffScreen(
            state = current,
            onBack = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex).dispose() }) else null,
        )
    }
}

/**
 * Everything one diff screen has computed or selected, kept outside its composition so it
 * survives being covered by a nested diff. Work runs in a child of the view's scope and is
 * cancelled when the entry is popped.
 */
private class DiffScreenState(
    val input: BinaryDiffInput,
    parentScope: CoroutineScope,
    open: (DiffOpenRequest) -> Unit,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val jobs = HashMap<Int, Job>()

    val session: BinaryDiffSession? = BinaryDiffProviderRegistry.forInput(input)?.session(input)
    var selectedTab by mutableStateOf(0)
    var selectedRef by mutableStateOf<DiffItemRef?>(null)
    var rendered by mutableStateOf<Pair<DiffItemRef, SideBySideDiff>?>(null)
    var banner by mutableStateOf<List<DiffTextLine>>(emptyList())
    val contents = mutableStateMapOf<Int, Result<DiffTabContent>>()
    val selection = HexSelectionState()
    private val context = DiffTabContext(select = { selectedRef = it }, open = open)

    init {
        session?.let { s ->
            scope.launch {
                awaitAnimationFrame()
                banner = withContext(Dispatchers.Default) { runCatching { s.banner() }.getOrDefault(emptyList()) }
            }
        }
    }

    // The webview has one thread, so "async" here means: let the loading message paint,
    // then compute on a macrotask dispatcher, where the providers' `yield()` points hand
    // the thread back to the browser between chunks. Each tab is computed once per entry.
    fun ensureComputed(index: Int): Job = jobs.getOrPut(index) {
        val s = session!!
        scope.launch(Dispatchers.Default) {
            val result = try {
                Result.success(s.tabs[index].compute(context))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            contents[index] = result
        }
    }

    fun dispose() = job.cancel()
}

@Composable
private fun DiffScreen(state: DiffScreenState, onBack: (() -> Unit)?) {
    val input = state.input
    val session = state.session

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            width(100.percent)
            height(100.percent)
            property("overflow", "hidden")
            property("font-family", "var(--vscode-editor-font-family, 'Menlo', 'Monaco', 'Courier New', monospace)")
            property("font-size", "12px")
            property("color", "var(--hexana-fg, #ccc)")
            property("background", "var(--vscode-editor-background, #1e1e1e)")
        }
    }) {
        Div({
            style {
                property("padding", "8px 12px")
                property("border-bottom", "1px solid var(--hexana-border, #333)")
                property("font-weight", "bold")
                property("flex-shrink", "0")
            }
        }) {
            if (onBack != null) {
                Span({
                    attr("role", "link")
                    tabIndex(0)
                    style {
                        property("cursor", "pointer")
                        property("color", "var(--vscode-textLink-foreground, #3794ff)")
                        property("margin-right", "12px")
                    }
                    onClick { onBack() }
                }) { Text("← Back") }
            }
            Text(input.title)
        }
        if (session == null) {
            Div({ style { property("padding", "8px 12px") } }) {
                Text("These two binaries are not the same kind and cannot be compared.")
            }
        } else {
            DiffSessionContent(state, session)
        }
    }
}

@Composable
private fun DiffSessionContent(state: DiffScreenState, session: BinaryDiffSession) {
    val selectedTab = state.selectedTab
    LaunchedEffect(state, selectedTab) {
        awaitAnimationFrame()
        state.ensureComputed(selectedTab).join()
        // Prefetch the other tabs in the background, so switching to them is instant.
        session.tabs.indices.forEach { state.ensureComputed(it) }
    }

    if (session.tabs.size > 1) {
        DiffTabBar(session.tabs.map { it.name }, selectedTab) { state.selectedTab = it }
    }

    Div({
        style {
            property("flex", "1")
            property("min-height", "0")
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("overflow", "hidden")
            property("padding", "8px 12px")
        }
    }) {
        if (state.banner.isNotEmpty()) {
            Div({ style { property("padding-bottom", "6px") } }) { TextLines(state.banner) }
            DiffDivider()
        }
        val result = state.contents[selectedTab]
        when {
            result == null -> Div { Text(session.tabs[selectedTab].loadingMessage) }
            result.isFailure -> Div { Text(result.exceptionOrNull()?.message ?: "Failed to compare.") }
            else -> DiffTabBody(result.getOrThrow(), state.selection)
        }
    }

    session.detail?.let { detail ->
        Div({
            style {
                property("border-top", "2px solid var(--hexana-border, #333)")
                property("flex", "0 0 40%")
                property("min-height", "120px")
                display(DisplayStyle.Flex)
                property("flex-direction", "column")
                property("overflow", "hidden")
                property("padding", "8px 12px")
            }
        }) {
            SideBySideDetailPanel(detail, state)
        }
    }
}

@Composable
private fun DiffTabBody(content: DiffTabContent, selection: HexSelectionState) {
    if (content.header.isNotEmpty()) {
        Div({ style { property("padding-bottom", "6px") } }) { TextLines(content.header) }
        DiffDivider()
    }
    val table = content.table
    if (table == null) {
        content.emptyMessage?.let { Div({ style { property("padding", "8px 0") } }) { Text(it) } }
        return
    }
    TableTabContent(table, selection)
    content.footer?.let {
        Div({ style { property("padding", "4px 0"); property("opacity", "0.7") } }) { Text(it) }
    }
}

@Composable
private fun DiffTabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("flex-shrink", "0")
        }
    }) {
        for ((i, label) in labels.withIndex()) {
            val isActive = i == selected
            Div({
                style {
                    property("padding", "6px 16px")
                    property("cursor", "pointer")
                    property("user-select", "none")
                    property("border-bottom", if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                    property("color", if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                }
                onClick { onSelect(i) }
            }) {
                Text(label)
            }
        }
    }
}

/**
 * Renders the selected item side by side. The result is kept on [state], so returning to
 * this screen does not render it again, and the rows are virtualized: a large function is
 * tens of thousands of lines, far too many DOM nodes to create at once.
 */
@Composable
private fun SideBySideDetailPanel(detail: DiffDetailSource, state: DiffScreenState) {
    val selected = state.selectedRef
    LaunchedEffect(state, selected) {
        if (selected == null || state.rendered?.first == selected) return@LaunchedEffect
        state.rendered = null
        awaitAnimationFrame()
        state.rendered = selected to withContext(Dispatchers.Default) { detail.render(selected) }
    }
    val diff = state.rendered?.takeIf { it.first == selected }?.second
    when {
        selected == null -> Div({ style { property("opacity", "0.7") } }) { Text(detail.placeholder) }
        diff == null -> Div({ style { property("opacity", "0.7") } }) { Text("Rendering…") }
        else -> {
            Div({ style { property("font-weight", "bold"); property("padding-bottom", "4px"); property("flex-shrink", "0") } }) {
                Text(diff.title)
            }
            val empty = diff.emptyMessage
            if (empty != null) {
                Div({ style { property("opacity", "0.7") } }) { Text(empty) }
                return
            }
            val rows = diff.rows
            VirtualizedList(itemCount = rows.size, itemHeightPx = SIDE_BY_SIDE_ROW_HEIGHT_PX) { i ->
                val row = rows[i]
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        width(100.percent)
                        height(100.percent)
                    }
                }) {
                    SideCell(
                        gutter = if (row.leftRemoved) "-" else " ",
                        text = row.left,
                        isHighlighted = row.leftRemoved,
                        color = "var(--vscode-diffEditor-removedTextBackground, rgba(255,0,0,0.15))",
                    )
                    SideCell(
                        gutter = if (row.rightAdded) "+" else " ",
                        text = row.right,
                        isHighlighted = row.rightAdded,
                        color = "var(--vscode-diffEditor-insertedTextBackground, rgba(0,255,0,0.15))",
                    )
                }
            }
        }
    }
}

private const val SIDE_BY_SIDE_ROW_HEIGHT_PX = 18

@Composable
private fun SideCell(gutter: String, text: String?, isHighlighted: Boolean, color: String) {
    Div({
        style {
            property("flex", "1")
            property("min-width", "0")
            property("overflow", "hidden")
            property("text-overflow", "ellipsis")
            property("white-space", "pre")
            property("line-height", "${SIDE_BY_SIDE_ROW_HEIGHT_PX}px")
            property("padding-right", "8px")
            if (isHighlighted) {
                property("background", color)
            }
        }
    }) {
        Text(if (text == null) "" else "$gutter $text")
    }
}

@Composable
private fun TextLines(lines: List<DiffTextLine>) {
    for (line in lines) {
        Div({ if (line.bold) style { property("font-weight", "bold") } }) { Text(line.text) }
    }
}

@Composable
private fun DiffDivider() {
    Div({
        style {
            height(1.px)
            property("background", "var(--hexana-border, #333)")
            property("margin", "4px 0")
        }
    })
}

/** Suspends until the browser is about to paint, so state set before this call is visible first. */
private suspend fun awaitAnimationFrame() {
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val handle = window.requestAnimationFrame { cont.resumeWith(Result.success(Unit)) }
        cont.invokeOnCancellation { window.cancelAnimationFrame(handle) }
    }
    // One frame renders the pending state; the next callback runs after that paint.
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val handle = window.requestAnimationFrame { cont.resumeWith(Result.success(Unit)) }
        cont.invokeOnCancellation { window.cancelAnimationFrame(handle) }
    }
}
