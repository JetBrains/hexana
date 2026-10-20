package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.shared.CLR_IL_TAB_NAME
import org.jetbrains.hexana.shared.ClrIlRow
import org.jetbrains.hexana.shared.ClrIlSearchEntry
import org.jetbrains.hexana.shared.ClrIlSectionKinds
import org.jetbrains.hexana.shared.ClrIlType
import org.jetbrains.hexana.shared.clrIlChunkTree
import org.jetbrains.hexana.shared.clrIlSearchIndex
import org.jetbrains.hexana.shared.clrIlTypeBodyId
import org.jetbrains.hexana.shared.clrIlTypeHeaderId
import org.jetbrains.hexana.shared.clrIlTypeRows
import org.jetbrains.hexana.shared.clrIlTypes
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text

/**
 * The IL disassembly tab for .NET assemblies: one foldable section per type, whose body is the
 * type rendered as IL. Mirrors [dexSmaliPanels]: nothing is decoded until the tab is opened, and
 * IL only for the types that are expanded.
 */
internal fun clrIlPanels(provider: PeBinaryProvider, state: HexViewStateHolder): List<Panel> =
    listOf(Panel(CLR_IL_TAB_NAME) { ClrIlTab(provider, state) })

/**
 * Token resolution shares the metadata parser's caches, which are not thread-safe, so all IL is
 * rendered on this one single-threaded view of the IO pool. It must outlive any one panel: a type
 * being rendered cannot be interrupted, so it keeps running after its tab is switched away, and a
 * panel created on return — or a second editor of the same file — would otherwise render beside it.
 */
private val clrIlRenderDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/** Everything the view needs, built once off the EDT. */
private class ClrIlModel(
    val metadata: ClrMetadata,
    val bytes: CommonByteBuffer,
    val types: List<ClrIlType>,
    val tree: ChunkTree,
    val searchIndex: List<ClrIlSearchEntry>,
) {
    /** Bytes covered by each rendered row with a file offset, so a click selects the whole instruction. */
    val rowSizes = HashMap<Long, Int>()
}

@Composable
private fun ClrIlTab(provider: PeBinaryProvider, state: HexViewStateHolder) {
    var model by remember(provider) { mutableStateOf<Result<ClrIlModel>?>(null) }
    LaunchedEffect(provider) {
        model = withContext(Dispatchers.IO) {
            try {
                Result.success(buildClrIlModel(provider))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                HexanaLog.LOG.warn("Failed to build the IL view", e)
                Result.failure(e)
            }
        }
    }

    val ready = model?.getOrNull()
    if (ready == null) {
        Box(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
            val failure = model?.exceptionOrNull()
            if (failure == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Text(
                    text = "Failed to read .NET metadata: ${failure.message ?: failure::class.simpleName}",
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
        return
    }

    if (ready.types.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
            Text("(no types)", modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    ClrIlContent(ready, state)
}

private fun buildClrIlModel(provider: PeBinaryProvider): ClrIlModel {
    val metadata = provider.clrMetadata ?: error("no readable CLR metadata")
    val types = clrIlTypes(metadata)
    return ClrIlModel(
        metadata = metadata,
        bytes = provider.bytes,
        types = types,
        tree = clrIlChunkTree(metadata, types),
        searchIndex = clrIlSearchIndex(metadata, types),
    )
}

@Composable
private fun ClrIlContent(model: ClrIlModel, state: HexViewStateHolder) {
    // Rendered rows, filled in per type as types are expanded. Also the source of truth for
    // itemCountForChunk, which has to agree with what elementContent emits.
    val rowCache = remember(model) { mutableStateMapOf<ChunkId, List<ClrIlRow>>() }

    // Collapsed by default: a folded type emits no element, so no IL is decoded until asked for.
    val foldedSections = remember(model) {
        mutableStateOf<Set<ChunkId>>(model.types.mapTo(HashSet()) { clrIlTypeHeaderId(it) })
    }

    val config = remember(model) {
        VirtualizedChunkPanelConfig(
            sectionDisplayName = { kind ->
                when (kind) {
                    ClrIlSectionKinds.TYPE -> "type"
                    ClrIlSectionKinds.TYPE_BODY -> "body"
                    else -> kind.id
                }
            },
            searchPlaceholder = "Search types and methods",
            computeSearchResults = { query -> searchResults(model, query) },
            itemCountForChunk = { chunkId ->
                if ((chunkId as? ChunkId.Indexed)?.sectionKind == ClrIlSectionKinds.TYPE_BODY) {
                    // One placeholder item until the type has been rendered.
                    rowCache[chunkId]?.size ?: 1
                } else {
                    1
                }
            },
            sectionParent = mapOf(ClrIlSectionKinds.TYPE_BODY to ClrIlSectionKinds.TYPE),
            onOffsetClicked = { offset ->
                state.scrollHexViewTo(offset, setCursor = true)
                val size = synchronized(model.rowSizes) { model.rowSizes[offset] } ?: 0
                if (size > 0) state.setSelection(offset until offset + size)
            },
        )
    }

    VirtualizedChunkPanel(
        chunkTree = model.tree,
        config = config,
        foldedSectionsExternal = foldedSections,
        // One label per top-level section would be one per type; the collapsed list is the index.
        showSectionShortcutBar = false,
        sectionHeaderContent = { chunk, folded, onToggle ->
            val type = (chunk.id as? ChunkId.Indexed)?.let { model.types.getOrNull(it.index) }
            if (type != null) {
                ClrTypeHeaderRow(type, folded, onToggle)
            } else {
                HeaderRow(folded = folded, onToggle = onToggle, offset = chunk.byteRange.first) {
                    Text(text = chunk.kind.id, style = ilHeaderStyle)
                }
            }
        },
        elementContent = { chunk, _ ->
            val cached = rowCache[chunk.id]
            if (cached == null) {
                item(key = "clr-il-loading:${chunk.id}") {
                    ClrTypePlaceholderRow(model, chunk, rowCache)
                }
            } else {
                items(count = cached.size, key = { rowIndex -> "clr-il:${chunk.id}:$rowIndex" }) { rowIndex ->
                    SelectableTokenRow(tokens = cached[rowIndex].tokens, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        },
        onSearchResultPicked = { result, navigator ->
            // Navigating to the body unfolds its type header on the way.
            navigator.gotoChunk(result.chunkId)
        },
    )
}

private class ClrIlSearchResult(
    override val label: String,
    override val detail: String?,
    override val chunkId: ChunkId,
) : ChunkSearchResult

private fun searchResults(model: ClrIlModel, query: String): List<ChunkSearchResult> {
    val needle = query.trim()
    // Offset queries are already served by the panel's own offset search.
    if (needle.isEmpty() || needle.startsWith("0x")) return emptyList()
    val lowered = needle.lowercase()
    return model.searchIndex.asSequence()
        .filter { lowered in it.label.lowercase() }
        .take(50)
        .map { ClrIlSearchResult(it.label, it.detail, clrIlTypeBodyId(it.typeIndex)) }
        .toList()
}

/** Shown while a newly expanded type is rendered; the rows then replace it in [rowCache]. */
@Composable
private fun ClrTypePlaceholderRow(
    model: ClrIlModel,
    chunk: BinaryChunk.Element,
    rowCache: SnapshotStateMap<ChunkId, List<ClrIlRow>>,
) {
    val type = model.types.getOrNull(chunk.indexInSection)
    LaunchedEffect(chunk.id, model) {
        if (type == null) {
            rowCache[chunk.id] = emptyList()
            return@LaunchedEffect
        }
        rowCache[chunk.id] = withContext(clrIlRenderDispatcher) {
            try {
                clrIlTypeRows(model.metadata, model.bytes, type).also { rows ->
                    synchronized(model.rowSizes) {
                        for (row in rows) {
                            val offset = row.fileOffset ?: continue
                            if (row.byteSize > 0) model.rowSizes[offset] = row.byteSize
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                HexanaLog.LOG.warn("Failed to render ${type.name} as IL", e)
                emptyList()
            }
        }
    }
    Text(
        text = "// rendering ${chunk.displayName ?: "type"}…",
        style = CHUNK_PANEL_ROW_TEXT_STYLE.copy(color = defaultColorFor(RenderTokenKind.COMMENT)),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
    )
}

@Composable
private fun ClrTypeHeaderRow(type: ClrIlType, folded: Boolean, onToggle: () -> Unit) {
    HeaderRow(folded = folded, onToggle = onToggle, offset = type.byteRange.first) {
        Text(text = "${type.declaration} ${type.name}", style = ilHeaderStyle)
        val details = listOfNotNull(
            type.extends?.let { "extends $it" },
            "${type.memberCount} member${if (type.memberCount == 1) "" else "s"}",
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = details.joinToString(", "),
            style = ilHeaderStyle.copy(fontWeight = FontWeight.Normal, color = defaultColorFor(RenderTokenKind.COMMENT)),
        )
    }
}

@Composable
private fun HeaderRow(
    folded: Boolean,
    onToggle: () -> Unit,
    offset: Long,
    content: @Composable () -> Unit,
) {
    val background = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val gutterStyle = ilHeaderStyle.copy(color = HexanaTheme.hexTextColor)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("0x" + offset.toString(16).padStart(6, '0'), style = gutterStyle)
        Spacer(modifier = Modifier.width(12.dp))
        Text(if (folded) "▶" else "▼", style = gutterStyle)
        Spacer(modifier = Modifier.width(8.dp))
        content()
    }
}

private val ilHeaderStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )
