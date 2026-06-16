package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.format.RowKind
import org.jetbrains.hexana.providers.format.WasmHexanaWatRenderer
import org.jetbrains.hexana.providers.format.WasmParsedBinary
import org.jetbrains.hexana.providers.format.WasmSectionKinds
import org.jetbrains.hexana.providers.format.buildWasmChunkTree
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * WAT tab content — for core Wasm modules, renders an inline virtualized
 * WAT view similar to the IntelliJ plugin's `WasmPanelWatVirtualized`.
 * For component-model binaries, falls back to the wasmprinter-based
 * conversion that opens WAT in a native VS Code editor tab.
 */
@Composable
fun WatTabContent(
    provider: BinaryProvider,
) {
    when (provider) {
        is WasmBinaryProvider -> VirtualizedWatView(provider)
        is WasmComponentBinaryProvider -> WasmPrinterWat(provider.bytes.toByteArray())
        else -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

// ---------------------------------------------------------------------------
// Virtualized WAT view for core Wasm modules
// ---------------------------------------------------------------------------

private class WatPanelState(
    val provider: WasmBinaryProvider,
    val chunkTree: org.jetbrains.hexana.providers.format.WasmChunkTree,
    val renderer: WasmHexanaWatRenderer,
    val snapshot: org.jetbrains.hexana.document.BinaryDocumentSnapshot,
)

private fun buildWatPanelState(provider: WasmBinaryProvider): WatPanelState {
    val snapshot = binaryDocumentOf(provider.bytes).snapshot()
    val parsed = WasmParsedBinary.fromProvider(provider, snapshot)
    return WatPanelState(
        provider = provider,
        chunkTree = buildWasmChunkTree(parsed),
        renderer = WasmHexanaWatRenderer(parsed),
        snapshot = snapshot,
    )
}

@Composable
private fun VirtualizedWatView(provider: WasmBinaryProvider) {
    var panelState by remember { mutableStateOf<WatPanelState?>(null) }

    LaunchedEffect(provider) {
        panelState = buildWatPanelState(provider)
    }

    val ready = panelState
    if (ready == null) {
        Div({
            style {
                property("padding", "8px 0")
                property("opacity", "0.7")
            }
        }) {
            Text("Building WAT view…")
        }
        return
    }

    VirtualizedWatContent(ready)
}

/**
 * Estimated height in pixels per line of WAT output. Used to size
 * placeholder divs for off-screen chunks so the scrollbar reflects
 * the true document height. Refined when a chunk is actually rendered.
 */
private const val LINE_HEIGHT_PX = 17 // 12px font × 1.4 line-height ≈ 16.8

/** Section header rows are slightly taller due to padding. */
private const val HEADER_HEIGHT_PX = 28

/** How many pixels above/below the viewport to pre-render. */
private const val OVERSCAN_PX = 600

/**
 * One entry in the flat visible-item list. Items hidden by folded
 * sections or collapsed rec groups are excluded so the virtualizer
 * only deals with what should actually appear.
 */
private class VisibleItem(
    val index: Int,
    val chunk: BinaryChunk,
    val estimatedHeight: Int,
)

@Composable
private fun VirtualizedWatContent(state: WatPanelState) {
    val chunkTree = state.chunkTree
    val renderer = state.renderer
    val snapshot = state.snapshot

    var foldedSections by remember { mutableStateOf(emptySet<ChunkId>()) }
    var expandedRecGroups by remember { mutableStateOf(emptySet<ChunkId>()) }
    var expandedDataChunks by remember { mutableStateOf(emptySet<ChunkId>()) }
    var expandedImportChunks by remember { mutableStateOf(emptySet<ChunkId>()) }
    var expandedExportChunks by remember { mutableStateOf(emptySet<ChunkId>()) }

    if (chunkTree.totalChunks == 0) {
        Div({
            style {
                property("padding", "8px 0")
                property("opacity", "0.7")
            }
        }) {
            Text("No sections found.")
        }
        return
    }

    // Section shortcut bar + "Open in Editor" button
    val openInEditorScope = rememberCoroutineScope()
    SectionShortcutBar(chunkTree) {
        openInEditorScope.launch {
            try {
                WasmPrinterJs.ensureInitialized()
                val bytes = state.provider.bytes.duplicate()
                bytes.position(0)
                val result = WasmPrinterJs.printWat(bytes.toByteArray(), foldInstructions = false)
                postShowWatToHost(result.wat, result.warnings)
            } catch (e: Throwable) {
                console.error("Failed to convert WAT for editor: ${e.message}")
            }
        }
    }

    val visibleItems: List<VisibleItem> = remember(
        chunkTree, foldedSections, expandedRecGroups,
    ) {
        buildList {
            var hidden = false
            for (i in 0 until chunkTree.totalChunks) {
                val chunk = chunkTree.chunkAt(i)
                when (chunk) {
                    is BinaryChunk.SectionHeader -> {
                        hidden = chunk.id in foldedSections
                        add(VisibleItem(i, chunk, HEADER_HEIGHT_PX))
                    }
                    is BinaryChunk.Element -> {
                        if (hidden) continue
                        if (chunk.kind == WasmSectionKinds.TYPE_REC) {
                            add(VisibleItem(i, chunk, LINE_HEIGHT_PX))
                        } else if (chunk.groupId == null || chunk.groupId in expandedRecGroups) {
                            val h = chunk.estimatedLines.coerceAtLeast(1) * LINE_HEIGHT_PX
                            add(VisibleItem(i, chunk, h))
                        }
                    }
                }
            }
        }
    }

    // Cache rendered rows per chunk id. Rows are deterministic for a given
    // (chunk, renderer, snapshot) triple, so we cache to avoid calling
    // renderer.renderRows() on every scroll/toggle.
    val rowsCache: HashMap<ChunkId, List<org.jetbrains.hexana.providers.format.RenderedRow>> = remember { HashMap() }

    // Per-chunk HTML cache keyed on (chunkId, expand-state-relevant-to-chunk).
    // Section headers key on folded state; element chunks key on their expand flags.
    // This avoids re-building HTML strings for chunks whose state hasn't changed.
    val chunkHtmlCache: HashMap<Long, String> = remember { HashMap() }

    // Reverse lookup: ChunkId.toString() → ChunkId, built from visibleItems
    // so the click handler can reconstruct ChunkId from data-id attributes.
    val chunkIdLookup: Map<String, ChunkId> = remember(visibleItems) {
        val map = HashMap<String, ChunkId>()
        for (item in visibleItems) {
            map[item.chunk.id.toString()] = item.chunk.id
            if (item.chunk is BinaryChunk.Element) {
                val groupId = (item.chunk as BinaryChunk.Element).groupId
                if (groupId != null) map[groupId.toString()] = groupId
            }
        }
        map
    }

    // Refs to hold the latest state values so the click handler (attached once
    // in the ref callback) always sees current state without re-attaching.
    val chunkIdLookupRef = remember { mutableStateOf(chunkIdLookup) }
    chunkIdLookupRef.value = chunkIdLookup
    val foldedSectionsRef = remember { mutableStateOf(foldedSections) }
    foldedSectionsRef.value = foldedSections
    val expandedRecGroupsRef = remember { mutableStateOf(expandedRecGroups) }
    expandedRecGroupsRef.value = expandedRecGroups
    val expandedDataChunksRef = remember { mutableStateOf(expandedDataChunks) }
    expandedDataChunksRef.value = expandedDataChunks
    val expandedImportChunksRef = remember { mutableStateOf(expandedImportChunks) }
    expandedImportChunksRef.value = expandedImportChunks
    val expandedExportChunksRef = remember { mutableStateOf(expandedExportChunks) }
    expandedExportChunksRef.value = expandedExportChunks

    // Prefix-sum array of item heights for O(1) offset lookups.
    // Uses only estimated heights to avoid recomposition loops that occur
    // when feeding measured DOM heights back into Compose state.
    val prefixHeights: IntArray = remember(visibleItems) {
        val arr = IntArray(visibleItems.size + 1)
        for (i in visibleItems.indices) {
            arr[i + 1] = arr[i] + visibleItems[i].estimatedHeight
        }
        arr
    }
    val totalHeight = prefixHeights.lastOrNull() ?: 0

    // Current scroll position and viewport height, updated on scroll.
    var scrollTop by remember { mutableStateOf(0) }
    var viewportHeight by remember { mutableStateOf(800) }

    // Binary-search for the first item whose bottom edge is past
    // (scrollTop - OVERSCAN_PX) and the last item whose top edge is
    // before (scrollTop + viewportHeight + OVERSCAN_PX).
    val renderStart: Int
    val renderEnd: Int // exclusive
    run {
        val lo = (scrollTop - OVERSCAN_PX).coerceAtLeast(0)
        val hi = scrollTop + viewportHeight + OVERSCAN_PX
        var a = 0; var b = visibleItems.size
        while (a < b) {
            val m = (a + b) / 2
            if (prefixHeights[m + 1] <= lo) a = m + 1 else b = m
        }
        renderStart = a
        a = renderStart; b = visibleItems.size
        while (a < b) {
            val m = (a + b) / 2
            if (prefixHeights[m] < hi) a = m + 1 else b = m
        }
        renderEnd = a.coerceAtMost(visibleItems.size)
    }

    // Compute per-chunk cache keys and HTML for the visible slice.
    // Each chunk gets a wrapper div with a stable data-ci attribute so we can
    // do surgical DOM updates instead of replacing the entire innerHTML.

    /** Compute the cache key for a chunk given current expand state. */
    fun cacheKeyFor(item: VisibleItem): Long {
        val chunk = item.chunk
        return when (chunk) {
            is BinaryChunk.SectionHeader -> {
                val folded = chunk.id in foldedSections
                (item.index.toLong() shl 4) or (if (folded) 1L else 0L)
            }
            is BinaryChunk.Element -> {
                val bits = (if (chunk.id in expandedRecGroups) 1L else 0L) or
                    (if (chunk.id in expandedDataChunks) 2L else 0L) or
                    (if (chunk.id in expandedImportChunks) 4L else 0L) or
                    (if (chunk.id in expandedExportChunks) 8L else 0L)
                (item.index.toLong() shl 4) or bits
            }
        }
    }

    /** Build HTML for a single chunk (without wrapper div). */
    fun buildChunkHtml(item: VisibleItem): String {
        val chunk = item.chunk
        val cacheKey = cacheKeyFor(item)
        chunkHtmlCache[cacheKey]?.let { return it }
        val html = buildString(2048) {
            when (chunk) {
                is BinaryChunk.SectionHeader -> {
                    appendSectionHeaderHtml(
                        this, chunk, snapshot,
                        folded = chunk.id in foldedSections,
                    )
                }
                is BinaryChunk.Element -> {
                    if (chunk.kind == WasmSectionKinds.TYPE_REC) {
                        appendRecGroupHeaderHtml(
                            this, chunk,
                            expanded = chunk.id in expandedRecGroups,
                        )
                    } else {
                        appendChunkRowsHtml(
                            this, chunk, renderer, snapshot, rowsCache,
                            isDataExpanded = chunk.id in expandedDataChunks,
                            isImportExpanded = chunk.id in expandedImportChunks,
                            isExportExpanded = chunk.id in expandedExportChunks,
                        )
                    }
                }
            }
        }
        chunkHtmlCache[cacheKey] = html
        return html
    }

    // Track what's currently rendered so SideEffect can do surgical updates.
    // Maps item.index → cacheKey for each currently-rendered chunk wrapper.
    val prevRenderedRef = remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    // Ordered list of item indices currently in the DOM (between top-pad and bottom-pad).
    val prevIndicesRef = remember { mutableStateOf<List<Int>>(emptyList()) }
    // Direct references to wrapper DOM elements by item index — avoids querySelector loops.
    val wrapperElements = remember { HashMap<Int, HTMLElement>() }
    // Direct references to the padding divs
    val topPadElRef = remember { mutableStateOf<HTMLElement?>(null) }
    val bottomPadElRef = remember { mutableStateOf<HTMLElement?>(null) }

    // Current desired state for the visible slice
    val topPad = if (renderStart > 0) prefixHeights[renderStart] else 0
    val bottomPad = totalHeight - (if (renderEnd <= visibleItems.size) prefixHeights[renderEnd] else totalHeight)

    // Ordered list of item indices and their cache keys for the current visible range
    val currentIndices: List<Int> = remember(renderStart, renderEnd, visibleItems) {
        ArrayList<Int>(renderEnd - renderStart).also { list ->
            for (listIdx in renderStart until renderEnd) list.add(visibleItems[listIdx].index)
        }
    }
    val currentRendered: Map<Int, Long> = remember(
        renderStart, renderEnd, visibleItems,
        foldedSections, expandedDataChunks, expandedImportChunks, expandedExportChunks, expandedRecGroups,
    ) {
        val map = HashMap<Int, Long>(renderEnd - renderStart)
        for (listIdx in renderStart until renderEnd) {
            val item = visibleItems[listIdx]
            map[item.index] = cacheKeyFor(item)
        }
        map
    }
    // Lookup from item.index → VisibleItem for the current range
    val currentItemsByIndex: Map<Int, VisibleItem> = remember(renderStart, renderEnd, visibleItems) {
        HashMap<Int, VisibleItem>(renderEnd - renderStart).also { map ->
            for (listIdx in renderStart until renderEnd) {
                val item = visibleItems[listIdx]
                map[item.index] = item
            }
        }
    }

    // Single scrollable container — Compose manages only this one Div.
    // The visible content is set via innerHTML; clicks are handled via
    // event delegation using data-action attributes.
    Div({
        style {
            property("overflow-y", "auto")
            property("flex", "1")
            property("min-height", "0")
            property("font-family", "var(--vscode-editor-font-family, 'Menlo', 'Monaco', 'Courier New', monospace)")
            property("font-size", "12px")
            property("line-height", "1.4")
        }
        ref { el ->
            val htmlEl = el as HTMLElement
            var rafPending = false
            val scrollHandler: (Event) -> Unit = {
                if (!rafPending) {
                    rafPending = true
                    kotlinx.browser.window.requestAnimationFrame {
                        rafPending = false
                        scrollTop = htmlEl.scrollTop.toInt()
                        viewportHeight = htmlEl.clientHeight
                    }
                }
            }
            el.addEventListener("scroll", scrollHandler)

            // Event delegation for clickable elements — reads from *Ref
            // so the handler always sees the latest Compose state.
            val clickHandler: (Event) -> Unit = handler@{ evt ->
                var target = evt.target as? HTMLElement ?: return@handler
                // Walk up to find the element with data-action
                while (target !== htmlEl) {
                    val action = target.getAttribute("data-action")
                    if (action != null) {
                        val id = target.getAttribute("data-id") ?: return@handler
                        val chunkId = chunkIdLookupRef.value[id] ?: return@handler
                        when (action) {
                            "toggle-section" -> {
                                foldedSections = if (chunkId in foldedSectionsRef.value)
                                    foldedSectionsRef.value - chunkId else foldedSectionsRef.value + chunkId
                            }
                            "toggle-rec" -> {
                                expandedRecGroups = if (chunkId in expandedRecGroupsRef.value)
                                    expandedRecGroupsRef.value - chunkId else expandedRecGroupsRef.value + chunkId
                            }
                            "toggle-data" -> {
                                expandedDataChunks = if (chunkId in expandedDataChunksRef.value)
                                    expandedDataChunksRef.value - chunkId else expandedDataChunksRef.value + chunkId
                            }
                            "toggle-import" -> {
                                expandedImportChunks = if (chunkId in expandedImportChunksRef.value)
                                    expandedImportChunksRef.value - chunkId else expandedImportChunksRef.value + chunkId
                            }
                            "toggle-export" -> {
                                expandedExportChunks = if (chunkId in expandedExportChunksRef.value)
                                    expandedExportChunksRef.value - chunkId else expandedExportChunksRef.value + chunkId
                            }
                        }
                        return@handler
                    }
                    target = target.parentElement as? HTMLElement ?: return@handler
                }
            }
            el.addEventListener("click", clickHandler)

            // Defer initial measurement to avoid recomposition during ref
            kotlinx.browser.window.requestAnimationFrame {
                scrollTop = htmlEl.scrollTop.toInt()
                viewportHeight = htmlEl.clientHeight
            }
            onDispose {
                el.removeEventListener("scroll", scrollHandler)
                el.removeEventListener("click", clickHandler)
            }
        }
    }) {
        // Single content div — innerHTML is updated surgically.
        // On first render or when the visible range changes drastically, we do a full
        // innerHTML. On expand/collapse toggles (same range, few chunks changed), we
        // only update the wrapper divs whose cache keys changed.
        val contentElRef = remember { mutableStateOf<HTMLElement?>(null) }
        Div({
            ref { el ->
                contentElRef.value = el as HTMLElement
                onDispose { contentElRef.value = null }
            }
        })
        SideEffect {
            val el = contentElRef.value ?: return@SideEffect
            val prev = prevRenderedRef.value
            val prevIndices = prevIndicesRef.value

            if (prev.isEmpty()) {
                // First render — build DOM elements directly instead of innerHTML
                wrapperElements.clear()
                el.innerHTML = ""
                val topPadDiv = kotlinx.browser.document.createElement("div") as HTMLElement
                topPadDiv.style.setProperty("height", "${topPad}px")
                el.appendChild(topPadDiv)
                topPadElRef.value = topPadDiv

                for (idx in currentIndices) {
                    val item = currentItemsByIndex[idx]!!
                    val wrapper = kotlinx.browser.document.createElement("div") as HTMLElement
                    wrapper.innerHTML = buildChunkHtml(item)
                    el.appendChild(wrapper)
                    wrapperElements[idx] = wrapper
                }

                val bottomPadDiv = kotlinx.browser.document.createElement("div") as HTMLElement
                bottomPadDiv.style.setProperty("height", "${bottomPad}px")
                el.appendChild(bottomPadDiv)
                bottomPadElRef.value = bottomPadDiv
            } else {
                // --- Update padding heights in-place ---
                topPadElRef.value?.style?.setProperty("height", "${topPad}px")
                bottomPadElRef.value?.style?.setProperty("height", "${bottomPad}px")

                // --- Diff previous vs current item indices ---
                val curSet = currentIndices.toHashSet()

                // Remove chunks no longer visible
                for (idx in prevIndices) {
                    if (idx !in curSet) {
                        wrapperElements.remove(idx)?.remove()
                    }
                }

                // Insert new chunks and update changed ones.
                val bottomPadEl = bottomPadElRef.value
                for (i in currentIndices.indices) {
                    val idx = currentIndices[i]
                    val existing = wrapperElements[idx]
                    if (existing != null) {
                        // Update content if cache key changed
                        val oldKey = prev[idx]
                        val newKey = currentRendered[idx]
                        if (oldKey != newKey) {
                            existing.innerHTML = buildChunkHtml(currentItemsByIndex[idx]!!)
                        }
                    } else {
                        // Create new wrapper and insert at correct position
                        val wrapper = kotlinx.browser.document.createElement("div") as HTMLElement
                        wrapper.innerHTML = buildChunkHtml(currentItemsByIndex[idx]!!)
                        // Find the correct insertion point: the next existing chunk wrapper or bottom-pad
                        var insertBefore: HTMLElement? = null
                        for (j in (i + 1) until currentIndices.size) {
                            insertBefore = wrapperElements[currentIndices[j]]
                            if (insertBefore != null) break
                        }
                        el.insertBefore(wrapper, insertBefore ?: bottomPadEl)
                        wrapperElements[idx] = wrapper
                    }
                }
            }

            // Update prev state
            prevRenderedRef.value = currentRendered
            prevIndicesRef.value = currentIndices
        }
    }
}

// ---------------------------------------------------------------------------
// Section shortcut bar — quick-jump links for each section
// ---------------------------------------------------------------------------

@Composable
private fun SectionShortcutBar(
    chunkTree: org.jetbrains.hexana.providers.format.WasmChunkTree,
    onOpenInEditor: () -> Unit,
) {
    val sections = remember(chunkTree) {
        buildList {
            for (i in 0 until chunkTree.totalChunks) {
                val c = chunkTree.chunkAt(i)
                if (c is BinaryChunk.SectionHeader) {
                    add(WasmSectionKinds.displayName(c.kind))
                }
            }
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-wrap", "wrap")
            property("gap", "4px")
            property("padding", "4px 0")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("margin-bottom", "4px")
            property("flex-shrink", "0")
            property("align-items", "center")
        }
    }) {
        for (name in sections) {
            Div({
                style {
                    property("padding", "2px 8px")
                    property("font-size", "11px")
                    property("color", "var(--vscode-textLink-foreground, #3794ff)")
                    property("cursor", "default")
                    property("user-select", "none")
                    property("opacity", "0.8")
                }
            }) {
                Text(name)
            }
        }
        // Spacer to push the button to the right
        Div({ style { property("flex", "1") } })
        Div({
            style {
                property("padding", "2px 8px")
                property("font-size", "11px")
                property("color", "var(--vscode-textLink-foreground, #3794ff)")
                property("cursor", "pointer")
                property("user-select", "none")
                property("white-space", "nowrap")
            }
            onClick { onOpenInEditor() }
        }) {
            Text("Open in Editor ↗")
        }
    }
}

// ---------------------------------------------------------------------------
// HTML builders — pure string functions, no Compose overhead
// ---------------------------------------------------------------------------

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun buildHexPreview(
    snapshot: org.jetbrains.hexana.document.BinaryDocumentSnapshot,
    chunk: BinaryChunk.SectionHeader,
): String {
    val source = snapshot.byteSource()
    val rangeStart = chunk.byteRange.first
    val rangeLen = (chunk.byteRange.last - rangeStart + 1).coerceAtLeast(0L).toInt()
    val available = (source.length - rangeStart).coerceAtLeast(0L).toInt()
    val length = if (rangeStart < 0L) 0 else minOf(rangeLen, available)
    val capped = length.coerceAtMost(WasmHexanaWatRenderer.MAX_BYTES_DISPLAYED)
    val sb = StringBuilder()
    for (j in 0 until capped) {
        val b = source.get(rangeStart + j)
        if (j > 0) sb.append(' ')
        sb.append(((b.toInt() and 0xFF).toString(16).uppercase()).padStart(2, '0'))
    }
    if (length > WasmHexanaWatRenderer.MAX_BYTES_DISPLAYED) {
        sb.append(" …")
    }
    return sb.toString()
}

private fun appendSectionHeaderHtml(
    sb: StringBuilder,
    chunk: BinaryChunk.SectionHeader,
    snapshot: org.jetbrains.hexana.document.BinaryDocumentSnapshot,
    folded: Boolean,
) {
    val offsetColor = cssColorFor(RenderTokenKind.OFFSET_ANNOTATION)
    val commentColor = cssColorFor(RenderTokenKind.COMMENT)
    val offset = "0x${chunk.byteRange.first.toString(16).uppercase().padStart(8, '0')}"
    val hexPreview = escapeHtml(buildHexPreview(snapshot, chunk))
    val arrow = if (folded) "▶" else "▼"
    val label = escapeHtml(
        "(${WasmSectionKinds.displayName(chunk.kind)})  " +
            "${chunk.elementCount} entries · ${formatBytes(chunk.totalSize)}"
    )
    sb.append("<div data-action=\"toggle-section\" data-id=\"").append(escapeHtml(chunk.id.toString()))
    sb.append("\" style=\"display:flex;align-items:center;gap:8px;padding:3px 12px;")
    sb.append("background:var(--vscode-editor-selectionBackground, rgba(255,255,255,0.06));")
    sb.append("cursor:pointer;user-select:none;position:sticky;top:0;z-index:1;")
    sb.append("border-bottom:1px solid var(--hexana-border, #333)\">")
    sb.append("<span style=\"color:").append(offsetColor).append("\">").append(offset).append("</span>")
    sb.append("<span style=\"color:").append(offsetColor)
    sb.append(";min-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap\">")
    sb.append(hexPreview).append("</span>")
    sb.append("<span style=\"color:").append(commentColor).append("\">").append(arrow).append("</span>")
    sb.append("<span style=\"color:var(--hexana-fg, #ccc)\">").append(label).append("</span>")
    sb.append("</div>")
}

private fun appendRecGroupHeaderHtml(
    sb: StringBuilder,
    chunk: BinaryChunk.Element,
    expanded: Boolean,
) {
    val arrow = if (expanded) "▼" else "▶"
    val count = escapeHtml(chunk.displayName ?: "?")
    val kwColor = cssColorFor(RenderTokenKind.KEYWORD)
    sb.append("<div data-action=\"toggle-rec\" data-id=\"").append(escapeHtml(chunk.id.toString()))
    sb.append("\" style=\"padding:1px 16px;cursor:pointer;user-select:none;color:").append(kwColor).append("\">")
    sb.append(arrow).append(" (rec  (; ").append(count).append(" types ;)")
    sb.append("</div>")
}

/**
 * Appends all rows for a single Element chunk as HTML. Uses [rowsCache]
 * to avoid calling [WasmHexanaWatRenderer.renderRows] on every scroll/toggle —
 * the parsed row list is cached per chunk id.
 */
private fun appendChunkRowsHtml(
    sb: StringBuilder,
    chunk: BinaryChunk.Element,
    renderer: WasmHexanaWatRenderer,
    snapshot: org.jetbrains.hexana.document.BinaryDocumentSnapshot,
    rowsCache: HashMap<ChunkId, List<org.jetbrains.hexana.providers.format.RenderedRow>>,
    isDataExpanded: Boolean,
    isImportExpanded: Boolean,
    isExportExpanded: Boolean,
) {
    val rows = rowsCache.getOrPut(chunk.id) { renderer.renderRows(snapshot, chunk) }
    val chunkIdStr = escapeHtml(chunk.id.toString())
    val kwColor = cssColorFor(RenderTokenKind.KEYWORD)

    sb.append("<div style=\"padding:0 16px\">")
    for (row in rows) {
        // Skip hidden rows based on expand state
        if ((row.kind is RowKind.DataBody || row.kind is RowKind.DataDescriptor) && !isDataExpanded) continue
        if (row.kind is RowKind.ImportDescriptor && !isImportExpanded) continue
        if (row.kind is RowKind.ExportDescriptor && !isExportExpanded) continue

        // Expander rows
        if (row.kind is RowKind.DataExpander) {
            val byteCount = (row.kind as RowKind.DataExpander).bodyByteCount
            val label = if (isDataExpanded) "▼ hide $byteCount bytes" else "▶ show $byteCount bytes"
            sb.append("<div data-action=\"toggle-data\" data-id=\"").append(chunkIdStr)
            sb.append("\" style=\"padding-left:90px;cursor:pointer;user-select:none;color:").append(kwColor).append("\">")
            sb.append(escapeHtml(label)).append("</div>")
            continue
        }
        if (row.kind is RowKind.ImportExpander) {
            val label = if (isImportExpanded) "▼ hide details" else "▶ show details"
            sb.append("<div data-action=\"toggle-import\" data-id=\"").append(chunkIdStr)
            sb.append("\" style=\"padding-left:90px;cursor:pointer;user-select:none;color:").append(kwColor).append("\">")
            sb.append(escapeHtml(label)).append("</div>")
            continue
        }
        if (row.kind is RowKind.ExportExpander) {
            val label = if (isExportExpanded) "▼ hide details" else "▶ show details"
            sb.append("<div data-action=\"toggle-export\" data-id=\"").append(chunkIdStr)
            sb.append("\" style=\"padding-left:90px;cursor:pointer;user-select:none;color:").append(kwColor).append("\">")
            sb.append(escapeHtml(label)).append("</div>")
            continue
        }

        // Regular row — tokens with syntax highlighting
        sb.append("<div style=\"white-space:pre;min-height:1.4em\">")
        for (token in row.tokens) {
            if (token.kind == RenderTokenKind.LINE_BREAK) continue
            val color = cssColorFor(token.kind)
            val escaped = escapeHtml(token.text)
            if (color.isNotEmpty()) {
                sb.append("<span style=\"color:").append(color).append("\">")
                sb.append(escaped).append("</span>")
            } else {
                sb.append(escaped)
            }
        }
        sb.append("</div>")
    }
    sb.append("</div>")
}

// ---------------------------------------------------------------------------
// Component-model fallback — wasmprinter-based conversion
// ---------------------------------------------------------------------------

@Composable
private fun WasmPrinterWat(bytes: ByteArray) {
    var status by remember { mutableStateOf<WatConversionStatus>(WatConversionStatus.Converting) }

    LaunchedEffect(bytes) {
        status = WatConversionStatus.Converting
        try {
            WasmPrinterJs.ensureInitialized()
            val result = WasmPrinterJs.printWat(bytes, foldInstructions = false)
            postShowWatToHost(result.wat, result.warnings)
            status = WatConversionStatus.Opened(result.warnings)
        } catch (e: Throwable) {
            status = WatConversionStatus.Error(e.message ?: "Failed to convert WASM to WAT")
        }
    }

    when (val s = status) {
        is WatConversionStatus.Converting -> Div({
            style {
                property("padding", "8px 0")
                property("opacity", "0.7")
            }
        }) {
            Text("Converting to WAT…")
        }
        is WatConversionStatus.Opened -> {
            if (s.warning != null) {
                Div({
                    style {
                        property("color", "var(--vscode-editorWarning-foreground, #cca700)")
                        property("margin-bottom", "8px")
                        property("font-size", "12px")
                    }
                }) {
                    Text("Warning: ${s.warning}")
                }
            }
            Div({
                style {
                    property("padding", "8px 0")
                    property("opacity", "0.7")
                }
            }) {
                Text("WAT output opened in editor tab.")
            }
        }
        is WatConversionStatus.Error -> Div { Text("Error: ${s.message}") }
    }
}

private sealed class WatConversionStatus {
    data object Converting : WatConversionStatus()
    data class Opened(val warning: String?) : WatConversionStatus()
    data class Error(val message: String) : WatConversionStatus()
}

// ---------------------------------------------------------------------------
// Token color mapping — mirrors IntelliJ's colorFor() using CSS variables
// that adapt to the VS Code theme.
// ---------------------------------------------------------------------------

private fun cssColorFor(kind: RenderTokenKind): String = when (kind) {
    RenderTokenKind.KEYWORD -> "var(--vscode-symbolIcon-keywordForeground, #569CD6)"
    RenderTokenKind.IDENTIFIER -> "var(--vscode-symbolIcon-variableForeground, #9CDCFE)"
    RenderTokenKind.NUMBER -> "var(--vscode-debugTokenExpression-number, #B5CEA8)"
    RenderTokenKind.STRING -> "var(--vscode-debugTokenExpression-string, #CE9178)"
    RenderTokenKind.COMMENT -> "var(--vscode-editorLineNumber-foreground, #7F848E)"
    RenderTokenKind.OFFSET_ANNOTATION -> "#D7BA7D"
    RenderTokenKind.TYPE_REF -> "#4EC9B0"
    RenderTokenKind.FUNC_REF -> "#DCDCAA"
    RenderTokenKind.LABEL_REF -> "#9CDCFE"
    RenderTokenKind.LOCAL_REF -> "#9CDCFE"
    RenderTokenKind.GLOBAL_REF -> "#9CDCFE"
    RenderTokenKind.ERROR -> "var(--vscode-errorForeground, #F44747)"
    RenderTokenKind.RAW_BYTES -> "var(--vscode-editorLineNumber-foreground, #7F848E)"
    RenderTokenKind.PUNCTUATION -> ""
    RenderTokenKind.OPERATOR -> ""
    RenderTokenKind.WHITESPACE -> ""
    RenderTokenKind.LINE_BREAK -> ""
}

private fun formatBytes(size: Long): String = when {
    size < 1024 -> "${size}B"
    size < 1024 * 1024 -> "${size / 1024}KB"
    else -> "${size / (1024 * 1024)}MB"
}
