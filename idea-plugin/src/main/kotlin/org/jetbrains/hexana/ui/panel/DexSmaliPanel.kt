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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.renderDexClassAccessFlags
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.DexBinaryProvider
import org.jetbrains.hexana.shared.DEX_SMALI_TAB_NAME
import org.jetbrains.hexana.shared.DexSmaliClass
import org.jetbrains.hexana.shared.DexSmaliRow
import org.jetbrains.hexana.shared.DexSmaliSearchEntry
import org.jetbrains.hexana.shared.DexSmaliSectionKinds
import org.jetbrains.hexana.shared.dexSmaliClassBodyId
import org.jetbrains.hexana.shared.dexSmaliClassHeaderId
import org.jetbrains.hexana.shared.dexSmaliClassRows
import org.jetbrains.hexana.shared.dexSmaliChunkTree
import org.jetbrains.hexana.shared.dexSmaliClasses
import org.jetbrains.hexana.shared.dexSmaliSearchIndex
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text

/**
 * The Dalvik disassembly tab for `.dex` binaries: one foldable section per
 * class, whose body is the class rendered as smali.
 *
 * Everything expensive happens inside the panel's composable — tabs render only
 * when selected — and then only per expanded class, so opening an APK-sized DEX
 * costs a single pass over `class_def_item`s.
 */
internal fun dexSmaliPanels(provider: DexBinaryProvider, state: HexViewStateHolder): List<Panel> =
    listOf(Panel(DEX_SMALI_TAB_NAME) { DexSmaliTab(provider, state) })

/** Everything the view needs, built once off the EDT. */
private class DexSmaliModel(
    val dex: DexFile,
    val bytes: CommonByteBuffer,
    val classes: List<DexSmaliClass>,
    val tree: ChunkTree,
    val searchIndex: List<DexSmaliSearchEntry>,
)

@Composable
private fun DexSmaliTab(provider: DexBinaryProvider, state: HexViewStateHolder) {
    var model by remember(provider) { mutableStateOf<Result<DexSmaliModel>?>(null) }
    LaunchedEffect(provider) {
        model = withContext(Dispatchers.IO) {
            try {
                Result.success(buildDexSmaliModel(provider))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                HexanaLog.LOG.warn("Failed to build the DEX smali view", e)
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
                    text = "Failed to read this DEX file: ${failure.message ?: failure::class.simpleName}",
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
        return
    }

    if (ready.classes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
            Text("(no classes)", modifier = Modifier.align(Alignment.Center))
        }
        return
    }

    DexSmaliContent(ready, state)
}

private fun buildDexSmaliModel(provider: DexBinaryProvider): DexSmaliModel {
    val dex = provider.parsedDexFile ?: error("not a readable DEX file")
    val bytes = provider.bytes
    val classes = dexSmaliClasses(dex)
    return DexSmaliModel(
        dex = dex,
        bytes = bytes,
        classes = classes,
        tree = dexSmaliChunkTree(classes),
        searchIndex = dexSmaliSearchIndex(dex, bytes, classes),
    )
}

@Composable
private fun DexSmaliContent(model: DexSmaliModel, state: HexViewStateHolder) {
    // Rendered rows, filled in per class as classes are expanded. Also the
    // source of truth for itemCountForChunk, which has to agree with what
    // elementContent emits or the navigator lands off-target.
    val rowCache = remember(model) { mutableStateMapOf<ChunkId, List<DexSmaliRow>>() }

    // Collapsed by default: a folded class emits no element, so no
    // class_data_item or code_item is read until the reader asks for one.
    val foldedSections = remember(model) {
        mutableStateOf<Set<ChunkId>>(model.classes.mapTo(HashSet()) { dexSmaliClassHeaderId(it) })
    }

    // Header rows resolve their class by descriptor, which is what the header
    // chunk id carries; a linear scan per rendered row would not do here.
    val classesByDescriptor = remember(model) { model.classes.associateBy { it.descriptor } }

    val config = remember(model) {
        VirtualizedChunkPanelConfig(
            sectionDisplayName = { kind ->
                when (kind) {
                    DexSmaliSectionKinds.CLASS -> "class"
                    DexSmaliSectionKinds.CLASS_BODY -> "body"
                    else -> kind.id
                }
            },
            searchPlaceholder = "Search classes and methods",
            computeSearchResults = { query -> searchResults(model, query) },
            itemCountForChunk = { chunkId ->
                if ((chunkId as? ChunkId.Indexed)?.sectionKind == DexSmaliSectionKinds.CLASS_BODY) {
                    // One placeholder item until the class has been rendered.
                    rowCache[chunkId]?.size ?: 1
                } else {
                    1
                }
            },
            sectionParent = mapOf(DexSmaliSectionKinds.CLASS_BODY to DexSmaliSectionKinds.CLASS),
            onOffsetClicked = { offset -> state.scrollHexViewTo(offset, setCursor = true) },
        )
    }

    VirtualizedChunkPanel(
        chunkTree = model.tree,
        config = config,
        foldedSectionsExternal = foldedSections,
        // The shortcut bar renders one un-virtualized label per top-level
        // section; with a class per section that is thousands of composables.
        // The collapsed class list is the index here anyway.
        showSectionShortcutBar = false,
        sectionHeaderContent = { chunk, folded, onToggle ->
            val cls = classesByDescriptor[(chunk.id as? ChunkId.Symbolic)?.name]
            if (cls != null) {
                DexClassHeaderRow(cls, folded, onToggle)
            } else {
                DexFallbackHeaderRow(chunk, folded, onToggle)
            }
        },
        elementContent = { chunk, _ ->
            val cached = rowCache[chunk.id]
            if (cached == null) {
                item(key = "dex-smali-loading:${chunk.id}") {
                    DexClassPlaceholderRow(model, chunk, rowCache)
                }
            } else {
                items(count = cached.size, key = { rowIndex -> "dex-smali:${chunk.id}:$rowIndex" }) { rowIndex ->
                    DexSmaliRowView(cached[rowIndex])
                }
            }
        },
        onSearchResultPicked = { result, navigator ->
            // Navigating to the body unfolds its class header on the way.
            navigator.gotoChunk((result as? DexChunkSearchResult)?.bodyId ?: result.chunkId)
        },
    )
}

private class DexChunkSearchResult(
    override val label: String,
    override val detail: String?,
    val bodyId: ChunkId,
) : ChunkSearchResult {
    override val chunkId: ChunkId get() = bodyId
}

private fun searchResults(model: DexSmaliModel, query: String): List<ChunkSearchResult> {
    val needle = query.trim()
    // Offset queries are already served by the panel's own offset search.
    if (needle.isEmpty() || needle.startsWith("0x")) return emptyList()
    val lowered = needle.lowercase()
    return model.searchIndex.asSequence()
        .filter { lowered in it.label.lowercase() }
        .take(50)
        .map { entry ->
            DexChunkSearchResult(
                label = entry.label,
                detail = entry.detail,
                bodyId = dexSmaliClassBodyId(entry.classIndex),
            )
        }
        .toList()
}

/**
 * Placeholder shown while a newly expanded class is rendered. The rows land in
 * [rowCache], after which the LazyColumn flips from this one item to one item
 * per row.
 */
@Composable
private fun DexClassPlaceholderRow(
    model: DexSmaliModel,
    chunk: BinaryChunk.Element,
    rowCache: SnapshotStateMap<ChunkId, List<DexSmaliRow>>,
) {
    val cls = model.classes.getOrNull(chunk.indexInSection)
    LaunchedEffect(chunk.id, model) {
        if (cls == null) {
            rowCache[chunk.id] = emptyList()
            return@LaunchedEffect
        }
        rowCache[chunk.id] = withContext(Dispatchers.IO) {
            try {
                dexSmaliClassRows(model.dex, model.bytes, cls)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                HexanaLog.LOG.warn("Failed to render ${cls.descriptor} as smali", e)
                emptyList()
            }
        }
    }
    Text(
        text = "# rendering ${chunk.displayName ?: "class"}…",
        style = CHUNK_PANEL_ROW_TEXT_STYLE.copy(color = defaultColorFor(RenderTokenKind.COMMENT)),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
    )
}

@Composable
private fun DexSmaliRowView(row: DexSmaliRow) {
    SelectableTokenRow(
        tokens = row.tokens,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun DexClassHeaderRow(cls: DexSmaliClass, folded: Boolean, onToggle: () -> Unit) {
    HeaderRow(folded = folded, onToggle = onToggle, offset = cls.byteRange.first) {
        val flags = renderDexClassAccessFlags(cls.accessFlags)
        Text(
            text = if (flags.isEmpty()) cls.descriptor else "$flags ${cls.descriptor}",
            style = dexHeaderStyle,
        )
        cls.superDescriptor?.let { superDescriptor ->
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "extends $superDescriptor",
                style = dexHeaderStyle.copy(
                    fontWeight = FontWeight.Normal,
                    color = defaultColorFor(RenderTokenKind.COMMENT),
                ),
            )
        }
    }
}

@Composable
private fun DexFallbackHeaderRow(chunk: BinaryChunk.SectionHeader, folded: Boolean, onToggle: () -> Unit) {
    HeaderRow(folded = folded, onToggle = onToggle, offset = chunk.byteRange.first) {
        Text(text = chunk.kind.id, style = dexHeaderStyle)
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
    val gutterStyle = dexHeaderStyle.copy(color = HexanaTheme.hexTextColor)
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

private val dexHeaderStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )
