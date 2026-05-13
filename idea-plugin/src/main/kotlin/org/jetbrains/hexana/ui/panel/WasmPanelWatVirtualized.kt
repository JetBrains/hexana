package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.TokenStream
import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.WasmChunkTree
import org.jetbrains.hexana.providers.format.WasmHexanaWatRenderer
import org.jetbrains.hexana.providers.format.WasmParsedBinary
import org.jetbrains.hexana.providers.format.WasmSectionKinds
import org.jetbrains.hexana.providers.format.buildWasmChunkTree
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * PR 1 of the new virtualized WAT viewer. Compose `LazyColumn` over the
 * format-agnostic `BinaryChunk` tree built from `WasmBinaryProvider`. One
 * chunk per renderable unit (section header, element). Code-section chunks
 * are per-function; the Function section is a single chunk per design
 * decision 5.
 *
 * Renders a placeholder body (`(; $name @ 0xXXXX ;)`) per chunk — the real
 * Hexana-flavored WAT printer lands in PR 2. PR 3 adds the toolbar (search,
 * jump-to-function). PR 4 adds the hex ↔ WAT cross-link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WasmPanelWatVirtualized(provider: WasmBinaryProvider, state: HexViewStateHolder) {
    val panelState = remember(provider) { buildPanelState(provider) }
    val chunkTree = panelState.chunkTree
    val renderer = panelState.renderer
    val snapshot = panelState.snapshot

    val listState = rememberLazyListState()
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    var foldedSections by remember(provider) { mutableStateOf(emptySet<ChunkId>()) }

    Column(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                var hidden = false
                for (i in 0 until chunkTree.totalChunks) {
                    val chunk = chunkTree.chunkAt(i)
                    when (chunk) {
                        is BinaryChunk.SectionHeader -> {
                            hidden = chunk.id in foldedSections
                            val isFolded = hidden
                            stickyHeader(key = chunk.id) {
                                SectionHeaderRow(
                                    chunk = chunk,
                                    snapshot = snapshot,
                                    folded = isFolded,
                                    onToggle = {
                                        foldedSections = if (chunk.id in foldedSections) {
                                            foldedSections - chunk.id
                                        } else {
                                            foldedSections + chunk.id
                                        }
                                    },
                                )
                            }
                        }
                        is BinaryChunk.Element -> {
                            if (!hidden) {
                                item(key = chunk.id) {
                                    ChunkRow(chunk = chunk, renderer = renderer, snapshot = snapshot)
                                }
                            }
                        }
                    }
                }
            }
        }
        StatusBar(chunkTree = chunkTree, currentIndex = firstVisibleIndex)
    }
}

private data class PanelState(
    val chunkTree: WasmChunkTree,
    val renderer: WasmHexanaWatRenderer,
    val snapshot: BinaryDocumentSnapshot,
)

private fun buildPanelState(provider: WasmBinaryProvider): PanelState {
    // Phase 1 stub: copy provider bytes into a BinaryDocument so the chunk tree
    // builder has a valid BinaryDocumentSnapshot to attach. Phase 2 plumbs the
    // document directly so this copy goes away.
    val bytes = provider.bytes.toByteArray()
    val snapshot = binaryDocumentOf(bytes).snapshot()
    val parsed = WasmParsedBinary.fromProvider(provider, snapshot)
    return PanelState(
        chunkTree = buildWasmChunkTree(parsed),
        renderer = WasmHexanaWatRenderer(parsed),
        snapshot = snapshot,
    )
}

@Composable
private fun SectionHeaderRow(
    chunk: BinaryChunk.SectionHeader,
    snapshot: BinaryDocumentSnapshot,
    folded: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )

    // Build the hex preview once per chunk — same format as emitHexBytes in the
    // renderer. Column widths come from WasmHexanaWatRenderer's companion so the
    // sticky header lines up with body rows even if the renderer changes them.
    val hexPreview = remember(chunk.id) {
        val source = snapshot.byteSource()
        val rangeStart = chunk.byteRange.first
        val rangeLen = (chunk.byteRange.last - rangeStart + 1).coerceAtLeast(0L).toInt()
        val available = (source.length - rangeStart).coerceAtLeast(0L).toInt()
        val length = if (rangeStart < 0L) 0 else minOf(rangeLen, available)
        val capped = length.coerceAtMost(WasmHexanaWatRenderer.MAX_BYTES_DISPLAYED)
        val sb = StringBuilder(WasmHexanaWatRenderer.BYTES_WIDTH)
        for (i in 0 until capped) {
            val b = source.get(rangeStart + i)
            if (i > 0) sb.append(' ')
            sb.append(((b.toInt() and 0xFF).toString(16).uppercase()).padStart(2, '0'))
        }
        if (length > WasmHexanaWatRenderer.MAX_BYTES_DISPLAYED) {
            sb.append(" …")
        }
        sb.toString().padEnd(WasmHexanaWatRenderer.BYTES_WIDTH)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Offset gutter — matches GUTTER_WIDTH layout of body rows.
        Text(
            text = "0x${chunk.byteRange.first.toString(16).uppercase().padStart(8, '0')}",
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(WasmHexanaWatRenderer.GAP.dp))
        // Hex preview column — mirrors the RAW_BYTES column in body rows.
        Text(
            text = hexPreview,
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(WasmHexanaWatRenderer.GAP.dp))
        Text(
            text = if (folded) "▶" else "▼",
            style = textStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "(${WasmSectionKinds.displayName(chunk.kind)})  " +
                "${chunk.elementCount} entries · ${formatBytes(chunk.totalSize)}",
            style = textStyle,
        )
    }
}

@Composable
private fun ChunkRow(
    chunk: BinaryChunk.Element,
    renderer: WasmHexanaWatRenderer,
    snapshot: BinaryDocumentSnapshot,
) {
    val annotated = remember(chunk.id) {
        renderer.render(snapshot, chunk).toAnnotatedString()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        Text(
            text = annotated,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = HexanaTheme.textColor,
            ),
        )
    }
}

@Composable
private fun StatusBar(chunkTree: WasmChunkTree, currentIndex: Int) {
    Divider(orientation = Orientation.Horizontal, thickness = 1.dp, modifier = Modifier.fillMaxWidth())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HexanaTheme.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )
        if (chunkTree.totalChunks == 0) {
            Text("No chunks", style = style)
            return@Row
        }
        val chunk = chunkTree.chunkAt(currentIndex.coerceAtMost(chunkTree.totalChunks - 1))
        val sectionLabel = WasmSectionKinds.displayName(chunk.kind)
        val offsetLabel = "0x${chunk.byteRange.first.toString(16).uppercase().padStart(8, '0')}"
        val nameLabel = when (chunk) {
            is BinaryChunk.Element -> chunk.displayName ?: "${sectionLabel}#${chunk.indexInSection}"
            is BinaryChunk.SectionHeader -> "(section header)"
        }
        Text("Chunk ${currentIndex + 1} / ${chunkTree.totalChunks}", style = style)
        Text("Section: $sectionLabel", style = style)
        Text("Offset: $offsetLabel", style = style)
        Text(nameLabel, style = style)
    }
}

private fun TokenStream.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (t in tokens) {
        if (t.kind == RenderTokenKind.LINE_BREAK) {
            append('\n')
            continue
        }
        withStyle(SpanStyle(color = colorFor(t.kind))) {
            append(t.text)
        }
    }
}

private fun colorFor(kind: RenderTokenKind): Color = when (kind) {
    RenderTokenKind.KEYWORD -> Color(0xFF569CD6)
    RenderTokenKind.IDENTIFIER -> Color(0xFF9CDCFE)
    RenderTokenKind.NUMBER -> Color(0xFFB5CEA8)
    RenderTokenKind.STRING -> Color(0xFFCE9178)
    RenderTokenKind.COMMENT -> Color(0xFF7F848E)
    RenderTokenKind.OFFSET_ANNOTATION -> Color(0xFFD7BA7D)
    RenderTokenKind.TYPE_REF -> Color(0xFF4EC9B0)
    RenderTokenKind.FUNC_REF -> Color(0xFFDCDCAA)
    RenderTokenKind.ERROR -> Color(0xFFF44747)
    else -> Color.Unspecified
}

private fun formatBytes(size: Long): String = when {
    size < 1024 -> "${size}B"
    size < 1024 * 1024 -> "${size / 1024}KB"
    else -> "${size / (1024 * 1024)}MB"
}

