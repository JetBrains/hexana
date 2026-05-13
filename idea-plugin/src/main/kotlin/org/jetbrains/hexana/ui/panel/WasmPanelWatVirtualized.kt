package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.io.encoding.Base64
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.document.EditableBinaryDocument
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.TokenStream
import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.journal.JournalOp
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.EditableRowEncoder
import org.jetbrains.hexana.providers.format.EditableRowInfo
import org.jetbrains.hexana.providers.format.RenderedRow
import org.jetbrains.hexana.providers.format.RowEncodeResult
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
internal fun WasmPanelWatVirtualized(
    provider: WasmBinaryProvider,
    state: HexViewStateHolder,
    editableDoc: EditableBinaryDocument? = null,
    /**
     * LazyColumn scroll state. When supplied externally (typical case from
     * BinaryEditorView), the state lives in a slot that doesn't reset when
     * `provider` rebuilds after an edit, so scroll position is preserved
     * across commits. Defaults to a fresh per-panel state for callers
     * outside the edit pipeline.
     */
    listState: LazyListState? = null,
    /**
     * Set of folded section IDs. Same lifecycle reasoning as [listState] —
     * lifting to a stable slot above the panel keeps the user's open/
     * collapsed pattern intact across edit commits.
     */
    foldedSectionsExternal: MutableState<Set<ChunkId>>? = null,
) {
    val panelState = remember(provider) { buildPanelState(provider) }
    val chunkTree = panelState.chunkTree
    val renderer = panelState.renderer
    val snapshot = panelState.snapshot

    val effectiveListState = listState ?: rememberLazyListState()
    val firstVisibleIndex by remember(effectiveListState) {
        derivedStateOf { effectiveListState.firstVisibleItemIndex }
    }
    val foldedSectionsState: MutableState<Set<ChunkId>> =
        foldedSectionsExternal ?: remember { mutableStateOf(emptySet<ChunkId>()) }
    var foldedSections by foldedSectionsState

    Column(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = effectiveListState, modifier = Modifier.fillMaxSize()) {
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
                                    ChunkRow(
                                        chunk = chunk,
                                        renderer = renderer,
                                        snapshot = snapshot,
                                        editableDoc = editableDoc,
                                    )
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

private val ROW_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    color = Color.Unspecified,
)

@Composable
private fun ChunkRow(
    chunk: BinaryChunk.Element,
    renderer: WasmHexanaWatRenderer,
    snapshot: BinaryDocumentSnapshot,
    editableDoc: EditableBinaryDocument?,
) {
    val rows = remember(chunk.id) { renderer.renderRows(snapshot, chunk) }
    // Per-row "open insert below" state — survives recompositions until
    // the chunk rebuilds (after a successful commit). Map<rowIndex, true>
    // means an empty insert TextField is showing below that row.
    val insertOpen = remember(chunk.id) { mutableStateMapOf<Int, Boolean>() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            RenderedRowView(
                row = row,
                chunkId = chunk.id,
                rowIndex = rowIndex,
                editableDoc = editableDoc,
                onEnterRequestInsert = if (editableDoc != null && row.editable?.kind == EditableRowInfo.Kind.OPCODE) {
                    { insertOpen[rowIndex] = true }
                } else {
                    null
                },
            )
            val editable = row.editable
            if (insertOpen[rowIndex] == true && editableDoc != null && editable != null) {
                InsertedInstructionRow(
                    insertOffset = editable.opStart + editable.opEncodedLength.toLong(),
                    cascade = editable.cascade,
                    localCount = editable.localCount,
                    editableDoc = editableDoc,
                    onClose = { insertOpen.remove(rowIndex) },
                )
            }
        }
    }
}

/**
 * Per-row composable. Non-editable rows render the whole row as a single
 * styled `Text`. Editable rows split the row into a structural prefix
 * (offset gutter + hex column + indent) rendered as a static `Text` plus a
 * `BasicTextField` whose initial content is [EditableRowInfo.watText]; on
 * Enter or focus-loss we run [EditableRowEncoder.encode] and commit a
 * transaction on success.
 *
 * Error UX (red border, tooltip, Esc-revert) is Phase C.4 — for now an
 * encoder failure just keeps the field's text live without committing.
 */
@Composable
private fun RenderedRowView(
    row: RenderedRow,
    chunkId: ChunkId,
    rowIndex: Int,
    editableDoc: EditableBinaryDocument?,
    /**
     * Called after a successful commit (or no-op if the field is unchanged)
     * so the panel can open an empty insert row below this one. Null when
     * the row isn't an opcode row (locals rows don't trigger insert-below
     * in this slice).
     */
    onEnterRequestInsert: (() -> Unit)? = null,
) {
    val info = row.editable
    val color = HexanaTheme.textColor
    if (info == null || editableDoc == null) {
        Text(
            text = row.tokens.toAnnotatedString(),
            style = ROW_TEXT_STYLE.copy(color = color),
        )
        return
    }

    // Split tokens into a structural prefix (offset gutter + hex column +
    // indent whitespace) and the editable suffix. The TextField only holds
    // the suffix's text, pre-filled with info.watText (which the renderer
    // already strips of structural decoration).
    val prefixEnd = row.tokens.indexOfFirst { it.kind !in PREFIX_KINDS }
    val prefixTokens = if (prefixEnd > 0) row.tokens.subList(0, prefixEnd) else row.tokens
    val prefix = prefixTokens.toAnnotatedString()

    var fieldText by remember(chunkId, rowIndex, info.watText) { mutableStateOf(info.watText) }
    var lastError by remember(chunkId, rowIndex) { mutableStateOf<String?>(null) }
    val errorColor = colorFor(RenderTokenKind.ERROR)
    val borderModifier = if (lastError != null) {
        Modifier.border(width = 1.dp, color = errorColor, shape = RoundedCornerShape(2.dp))
    } else {
        Modifier
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = prefix, style = ROW_TEXT_STYLE.copy(color = color))
        BasicTextField(
            value = fieldText,
            onValueChange = { fieldText = it; lastError = null },
            singleLine = true,
            cursorBrush = SolidColor(color),
            textStyle = ROW_TEXT_STYLE.copy(color = colorFor(RenderTokenKind.KEYWORD)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val err = commitRowEdit(editableDoc, info, fieldText)
                    lastError = err
                    if (err == null) {
                        // Either nothing changed or the commit succeeded —
                        // either way, open an empty insert row below.
                        onEnterRequestInsert?.invoke()
                    }
                },
            ),
            modifier = Modifier
                // Red border on encoder failure — clears as soon as the
                // user types again (onValueChange resets lastError).
                .then(borderModifier)
                // Esc reverts the field to the last committed text. If
                // there was an error, also clears it. Returns true so the
                // event doesn't propagate to other panel handlers.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        fieldText = info.watText
                        lastError = null
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && fieldText != info.watText) {
                        lastError = commitRowEdit(editableDoc, info, fieldText)
                    }
                },
        )
        if (lastError != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "(; ${lastError} ;)",
                style = ROW_TEXT_STYLE.copy(color = errorColor),
            )
        }
    }
}

private val PREFIX_KINDS = setOf(
    RenderTokenKind.OFFSET_ANNOTATION,
    RenderTokenKind.RAW_BYTES,
    RenderTokenKind.WHITESPACE,
)

private fun List<RenderToken>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    for (t in this@toAnnotatedString) {
        if (t.kind == RenderTokenKind.LINE_BREAK) continue
        withStyle(SpanStyle(color = colorFor(t.kind))) {
            append(t.text)
        }
    }
}

/**
 * Encodes the user's edited [text] for [info] and applies the resulting
 * `JournalOp`s to [doc]. Returns `null` on success, or a short error
 * message on failure.
 */
private fun commitRowEdit(
    doc: EditableBinaryDocument,
    info: EditableRowInfo,
    text: String,
): String? {
    if (text == info.watText) return null // nothing to do
    val result = when (info.kind) {
        EditableRowInfo.Kind.OPCODE -> EditableRowEncoder.encode(
            op = info.op ?: return "internal: opcode row missing op metadata",
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = text,
            localCount = info.localCount,
            cascade = info.cascade,
        )
        EditableRowInfo.Kind.LOCALS -> EditableRowEncoder.encodeLocals(
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = text,
            cascade = info.cascade,
        )
    }
    return when (result) {
        is RowEncodeResult.Success -> {
            doc.edit().apply {
                for (op in result.ops) {
                    when (op) {
                        is JournalOp.Replace -> replace(op.start, op.end, Base64.decode(op.data))
                        is JournalOp.Insert -> insert(op.offset, Base64.decode(op.data))
                        is JournalOp.Delete -> delete(op.start, op.end)
                    }
                }
                commit()
            }
            null
        }
        is RowEncodeResult.Error -> result.message
    }
}

/**
 * The empty TextField that appears below an opcode row when the user
 * pressed Enter (Phase D.3). The user types a new instruction; on Enter
 * (or focus-loss) we encode and commit a `JournalOp.Insert` plus cascade
 * ops. Esc closes the row without committing.
 *
 * Empty text + Enter / focus-loss closes silently.
 */
@Composable
private fun InsertedInstructionRow(
    insertOffset: Long,
    cascade: org.jetbrains.hexana.providers.format.CascadeContext?,
    localCount: Int?,
    editableDoc: EditableBinaryDocument,
    onClose: () -> Unit,
) {
    var fieldText by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }
    val color = HexanaTheme.textColor
    val errorColor = colorFor(RenderTokenKind.ERROR)
    val borderModifier = if (lastError != null) {
        Modifier.border(width = 1.dp, color = errorColor, shape = RoundedCornerShape(2.dp))
    } else {
        Modifier
    }

    fun tryCommit(): Boolean {
        if (fieldText.isBlank()) {
            onClose()
            return true
        }
        val err = commitInsertEdit(editableDoc, insertOffset, cascade, localCount, fieldText)
        lastError = err
        return err == null
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Indent placeholder so the empty field roughly aligns with the
        // mnemonic column on the row above. Two spaces matches the
        // structural-row prefix the renderer uses for code body lines.
        Text(text = "  ", style = ROW_TEXT_STYLE.copy(color = color))
        BasicTextField(
            value = fieldText,
            onValueChange = { fieldText = it; lastError = null },
            singleLine = true,
            cursorBrush = SolidColor(color),
            textStyle = ROW_TEXT_STYLE.copy(color = colorFor(RenderTokenKind.KEYWORD)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { tryCommit() }),
            modifier = Modifier
                .then(borderModifier)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && fieldText.isBlank()) {
                        onClose()
                    }
                },
        )
        if (lastError != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "(; ${lastError} ;)",
                style = ROW_TEXT_STYLE.copy(color = errorColor),
            )
        }
    }
}

/**
 * Encodes the user's typed instruction for an inserted row at
 * [insertOffset] and applies the JournalOps. Returns null on success or a
 * short error message on failure.
 */
private fun commitInsertEdit(
    doc: EditableBinaryDocument,
    insertOffset: Long,
    cascade: org.jetbrains.hexana.providers.format.CascadeContext?,
    localCount: Int?,
    text: String,
): String? {
    val result = EditableRowEncoder.encodeInsert(
        insertOffset = insertOffset,
        text = text,
        localCount = localCount,
        cascade = cascade,
    )
    return when (result) {
        is RowEncodeResult.Success -> {
            doc.edit().apply {
                for (op in result.ops) {
                    when (op) {
                        is JournalOp.Replace -> replace(op.start, op.end, Base64.decode(op.data))
                        is JournalOp.Insert -> insert(op.offset, Base64.decode(op.data))
                        is JournalOp.Delete -> delete(op.start, op.end)
                    }
                }
                commit()
            }
            null
        }
        is RowEncodeResult.Error -> result.message
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

