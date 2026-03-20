package org.jetbrains.hexana.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.startOffset
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewMode
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.HexanaSettingsListener
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.document.EditableBinaryDocument
import org.jetbrains.hexana.feedback.HexanaFeedbackOpener
import org.jetbrains.hexana.feedback.HexanaIssueLinks
import org.jetbrains.hexana.feedback.isInIdeFeedbackEnabled
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toByteBuffer
import org.jetbrains.hexana.providers.*
import org.jetbrains.hexana.ui.components.ShareFeedbackButton
import org.jetbrains.hexana.ui.components.ShareFeedbackButtonLabel
import org.jetbrains.hexana.ui.components.ShareFeedbackPopup
import org.jetbrains.hexana.wat.psi.getDataByIndex
import org.jetbrains.hexana.wat.psi.getDefinedFunctionByIndex
import org.jetbrains.jewel.foundation.modifier.onHover
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.datatransfer.StringSelection
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

private const val HexanaMarketplaceUrl =
    "https://plugins.jetbrains.com/plugin/29090-hexana--webassembly--binary-inspector"

@Composable
fun SyncLazyListStates(vararg states: LazyListState) {
    states.forEach { source ->
        val currentInfo by remember { derivedStateOf { source.firstVisibleItemIndex to source.firstVisibleItemScrollOffset } }

        LaunchedEffect(source) {
            snapshotFlow { currentInfo }
                .collect { (index, offset) ->
                    states.forEach { target ->
                        if (target !== source) {
                            // this is very important for animated scenarios - otherwise "Scroll Data Section into view" stops working
                            if (!target.isScrollInProgress) {
                                if (target.firstVisibleItemIndex != index || target.firstVisibleItemScrollOffset != offset) {
                                    // launch is important so that the vertical scroll bar works properly :)
                                    launch {
                                        target.scrollToItem(index, offset)
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HexViewer(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    showOffsets: Boolean = true,
    focusRequester: FocusRequester,
    editableDoc: EditableBinaryDocument? = null,
    /**
     * Invoked after a hex-cell edit successfully commits. The binary
     * editor uses this to demote the provider to [GenericBinaryProvider]
     * for the rest of the session — raw byte mutations bypass the WAT
     * encoder, so structural panels (size analysis, dominators, etc.)
     * can no longer trust the file's shape.
     */
    onHexCommit: (() -> Unit)? = null,
    suppressEditHighlight: Boolean = false,
) {
    val buf = binaryProvider.bytes

    // Snapshot of modified byte ranges from the editable document, used to
    // highlight edited cells. Recomputed whenever the live provider changes
    // (which happens on every commit via the DocumentListener in
    // BinaryEditorView). The list is small — only contiguous edited spans.
    val modifiedRanges = remember(binaryProvider, suppressEditHighlight) {
        if (suppressEditHighlight) emptyList()
        else editableDoc?.modifiedRanges() ?: emptyList()
    }

    val coroutineScope = rememberCoroutineScope()

    val arrowKeyHandler = remember { mutableStateOf<((Key, Boolean) -> Unit)?>(null) }
    var showShareFeedbackPopup by remember { mutableStateOf(false) }
    var feedbackPromptDisabled by remember {
        mutableStateOf(HexanaSettings.getInstance().feedbackPromptDisabled)
    }
    DisposableEffect(Unit) {
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(HexanaSettings.TOPIC, HexanaSettingsListener { enabled ->
            feedbackPromptDisabled = !enabled
        })
        onDispose {
            connection.disconnect()
        }
    }

    // Pending hex digit for in-cell editing: when non-null, the cursor cell
    // shows `<digit>_` and the next hex digit typed combines into a byte and
    // commits via `editableDoc.edit().replace(...).commit()`. Cleared on
    // Escape, on cursor move, or after a successful commit. Null when no
    // editable document is wired or the hex viewer is not in edit mode.
    var pendingDigit by remember(editableDoc) { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    if (showShareFeedbackPopup && event.key == Key.Escape) {
                        showShareFeedbackPopup = false
                        return@onPreviewKeyEvent true
                    }
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                            pendingDigit = null
                            arrowKeyHandler.value?.invoke(event.key, event.isShiftPressed)
                            return@onPreviewKeyEvent true
                        }
                        Key.Escape -> {
                            if (pendingDigit != null) {
                                pendingDigit = null
                                return@onPreviewKeyEvent true
                            }
                        }
                        else -> {
                            val digit = hexDigitValue(event.key)
                            if (digit != null && editableDoc != null) {
                                pendingDigit = handleHexDigit(
                                    digit = digit,
                                    pending = pendingDigit,
                                    bytesPerRow = state.bytesPerRow.value,
                                    state = state,
                                    doc = editableDoc,
                                    onCommit = onHexCommit,
                                )
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                }
                false
            }
            .focusable(true)
            .fillMaxSize()
            .onClick {
                state.setViewMode(HexViewMode.Hex)
                focusRequester.requestFocus()
            }
            .background(HexanaTheme.backgroundColor)) {
        val offsetListState = rememberLazyListState()
        val hexDataListState = rememberLazyListState()
        val textDataListState = rememberLazyListState()

        SyncLazyListStates(offsetListState, hexDataListState, textDataListState)

        // Request focus when the hex viewer first composes so that key
        // events (hex-digit typing, arrow navigation) work immediately —
        // including after an IDE restart that auto-reopens the editor.
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        val bytesPerRow by remember { state.bytesPerRow }
        val spacing = 8.dp

        val rowCount = (buf.limit() + bytesPerRow - 1) / bytesPerRow
        val offsetWidth = 96.dp

        val showFloatingShareFeedbackButton = !feedbackPromptDisabled && shouldShowFloatingShareFeedbackButtonInHexViewer(
            availableWidth = maxWidth,
            bytesPerRow = bytesPerRow,
            showOffsets = showOffsets,
            offsetWidth = offsetWidth,
            spacing = spacing,
        )
        val isDarkTheme = JewelTheme.isDark

        LaunchedEffect(showFloatingShareFeedbackButton) {
            if (!showFloatingShareFeedbackButton) {
                showShareFeedbackPopup = false
            }
        }

        var selectionAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var currentPointerPosition by remember { mutableStateOf(Offset.Unspecified) }
        var isPointerPressed by remember { mutableStateOf(false) }
        var selectionAreaHeight by remember { mutableFloatStateOf(0f) }
        var selectionAreaWidth by remember { mutableFloatStateOf(0f) }

        val totalBytes = buf.limit()

        val textMeasurer = rememberTextMeasurer()
        val monoCharWidthPx = remember(textMeasurer) {
            textMeasurer.measure(
                "W",
                style = TextStyle(fontFamily = FontFamily.Monospace)
            ).size.width.toFloat()
        }

        arrowKeyHandler.value = handleArrowKey@{ key: Key, isShiftPressed: Boolean ->
            val curLine = state.cursorPosition.line
            val curCol = state.cursorPosition.lineOffset

            // If cursor was never placed, start at 0,0
            val startLine = if (curLine < 0) 0 else curLine
            val startCol = if (curCol < 0) 0 else curCol

            val newLine: Int
            val newCol: Int
            when (key) {
                Key.DirectionRight -> {
                    val nextAbsolute = startLine * bytesPerRow + startCol + 1
                    if (nextAbsolute >= totalBytes) {
                        newLine = startLine
                        newCol = startCol
                    } else {
                        newLine = nextAbsolute / bytesPerRow
                        newCol = nextAbsolute % bytesPerRow
                    }
                }
                Key.DirectionLeft -> {
                    val prevAbsolute = startLine * bytesPerRow + startCol - 1
                    if (prevAbsolute < 0) {
                        newLine = 0
                        newCol = 0
                    } else {
                        newLine = prevAbsolute / bytesPerRow
                        newCol = prevAbsolute % bytesPerRow
                    }
                }
                Key.DirectionDown -> {
                    val nextAbsolute = (startLine + 1) * bytesPerRow + startCol
                    if (nextAbsolute >= totalBytes) {
                        newLine = startLine
                        newCol = startCol
                    } else {
                        newLine = startLine + 1
                        newCol = startCol
                    }
                }
                Key.DirectionUp -> {
                    if (startLine <= 0) {
                        newLine = 0
                        newCol = startCol
                    } else {
                        newLine = startLine - 1
                        newCol = startCol
                    }
                }
                else -> return@handleArrowKey
            }

            state.setCursorPosition(newLine, newCol)
            state.setHoverPosition(newLine, newCol)

            if (isShiftPressed) {
                if (selectionAnchor == null) {
                    selectionAnchor = startLine to startCol
                }
                val anchor = selectionAnchor!!
                state.setSelection(anchor.first, anchor.second, newLine, newCol)
            } else {
                selectionAnchor = null
                state.resetSelection()
            }

            // Only scroll if the cursor line is outside the visible area
            val visibleItems = hexDataListState.layoutInfo.visibleItemsInfo
            if (visibleItems.isNotEmpty()) {
                val firstFullyVisible = if (visibleItems.first().offset < 0) {
                    visibleItems.getOrNull(1)?.index ?: visibleItems.first().index
                } else {
                    visibleItems.first().index
                }
                val viewportEnd = hexDataListState.layoutInfo.viewportEndOffset
                val lastFullyVisible = visibleItems.lastOrNull { it.offset + it.size <= viewportEnd }?.index
                    ?: visibleItems.last().index

                if (newLine < firstFullyVisible) {
                    coroutineScope.launch {
                        hexDataListState.animateScrollToItem(newLine)
                    }
                } else if (newLine > lastFullyVisible) {
                    // Scroll so newLine appears at the bottom of the viewport
                    // We need to find how many items fit and scroll so newLine is the last visible
                    val itemsVisible = lastFullyVisible - firstFullyVisible
                    val scrollTarget = (newLine - itemsVisible).coerceAtLeast(0)
                    coroutineScope.launch {
                        hexDataListState.animateScrollToItem(scrollTarget)
                    }
                }
            }
        }

        LaunchedEffect(state) {
            state.hexScrollFlow.collect { offset ->
                val lineNo = (offset / bytesPerRow).toInt()
                hexDataListState.animateScrollToItem(lineNo)
            }
        }

        fun resolveSelectionPosition(pointer: Offset, areaWidth: Float, listState: LazyListState = hexDataListState): Pair<Int, Int>? {
            val visibleItemsInfo = listState.layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty() || areaWidth <= 0f || rowCount == 0) {
                return null
            }

            val firstVisible = visibleItemsInfo.first()
            val lastVisible = visibleItemsInfo.last()

            val resolvedRow = when {
                pointer.y < firstVisible.offset -> firstVisible.index - 1
                pointer.y > lastVisible.offset + lastVisible.size -> lastVisible.index + 1
                else -> visibleItemsInfo.indexOfLast { it.offset <= pointer.y } + listState.firstVisibleItemIndex
            }.coerceIn(0, rowCount - 1)

            val cellWidth = areaWidth / bytesPerRow
            val resolvedColumn = (pointer.x / cellWidth).toInt().coerceIn(0, bytesPerRow - 1)

            return resolvedRow to resolvedColumn
        }

        fun resolveTextSelectionPosition(pointer: Offset, paddingPx: Float, charWidthPx: Float, listState: LazyListState): Pair<Int, Int>? {
            val visibleItemsInfo = listState.layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty() || charWidthPx <= 0f || rowCount == 0) {
                return null
            }

            val firstVisible = visibleItemsInfo.first()
            val lastVisible = visibleItemsInfo.last()

            val resolvedRow = when {
                pointer.y < firstVisible.offset -> firstVisible.index - 1
                pointer.y > lastVisible.offset + lastVisible.size -> lastVisible.index + 1
                else -> visibleItemsInfo.indexOfLast { it.offset <= pointer.y } + listState.firstVisibleItemIndex
            }.coerceIn(0, rowCount - 1)

            val adjustedX = pointer.x - paddingPx
            val resolvedColumn = (adjustedX / charWidthPx).toInt().coerceIn(0, bytesPerRow - 1)

            return resolvedRow to resolvedColumn
        }

        LaunchedEffect(isPointerPressed, currentPointerPosition, selectionAreaHeight, selectionAnchor, bytesPerRow) {
            if (!isPointerPressed || currentPointerPosition == Offset.Unspecified || selectionAreaHeight <= 0f) {
                return@LaunchedEffect
            }
            while (isPointerPressed && currentPointerPosition != Offset.Unspecified) {
                val threshold = 40.dp.value
                val scrollSpeed = 50f
                val scrollDelta = when {
                    currentPointerPosition.y < threshold -> {
                        val ratio = (threshold - maxOf(0f, currentPointerPosition.y)) / threshold
                        -scrollSpeed * ratio
                    }

                    currentPointerPosition.y > selectionAreaHeight - threshold -> {
                        val ratio = maxOf(0f, currentPointerPosition.y - (selectionAreaHeight - threshold)) / threshold
                        scrollSpeed * ratio
                    }

                    else -> 0f
                }

                if (scrollDelta == 0f) {
                    break
                }

                val consumed = hexDataListState.scrollBy(scrollDelta)
                val anchor = selectionAnchor
                val current = resolveSelectionPosition(currentPointerPosition, selectionAreaWidth)
                if (anchor != null && current != null) {
                    state.setSelection(anchor.first, anchor.second, current.first, current.second)
                }
                if (consumed == 0f) {
                    break
                }
                delay(16)
            }
        }


        Column(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth()) {
                    if (showOffsets) {
                        Box(
                            modifier = Modifier
                                .width(offsetWidth)
                                .background(HexanaTheme.backgroundColor)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(" ", style = TextStyle(fontFamily = FontFamily.Monospace))
                        }
                    }
                    Box(
                        modifier = Modifier.background(HexanaTheme.backgroundColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            (0 until bytesPerRow).forEach { byte ->
                                Box(
                                    modifier = Modifier.padding(end = 2.dp)
                                ) {
                                    Text(
                                        byte.asHexString(),
                                        softWrap = false,
                                        maxLines = 1,
                                        style = TextStyle(
                                            color = HexanaTheme.textColor,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(HexanaTheme.backgroundColor)
                            .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                    ) {
                        val showAscii = state.showAscii
                        Text(
                            if (showAscii) {
                                "ASCII"
                            } else {
                                "UTF-8"
                            },
                            softWrap = false,
                            maxLines = 1,
                            style = TextStyle(
                                color = HexanaTheme.textColor,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.onClick {
                                state.setShowAscii(!showAscii)
                            }
                        )
                    }
                }

                Box(modifier = Modifier.matchParentSize()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showFloatingShareFeedbackButton,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp),
                        enter = fadeIn(animationSpec = tween(durationMillis = 280)) +
                                slideInHorizontally(
                                    animationSpec = tween(durationMillis = 140),
                                    initialOffsetX = { width -> width / 3 }
                                ),
                        exit = fadeOut(animationSpec = tween(durationMillis = 250)) +
                                slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 90),
                                    targetOffsetX = { width -> width / 4 }
                                ),
                    ) {
                        ShareFeedbackButton(
                            modifier = Modifier,
                            onClick = {
                                showShareFeedbackPopup = !showShareFeedbackPopup
                            }
                        )
                    }
                }
            }
            ContextMenuArea(items = {
                getContextActions(buf, state, binaryProvider)
            }) {
                Row {
                    Box {
                        LazyColumn(state = offsetListState) {
                            items(count = rowCount) { rowIndex ->
                                Box(
                                    modifier = Modifier
                                        .width(offsetWidth)
                                        .background(HexanaTheme.backgroundColor)
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Text(
                                        (state.displayBaseOffset + rowIndex * bytesPerRow).asHexString(padStart = 8),
                                        softWrap = false,
                                        style = TextStyle(fontFamily = FontFamily.Monospace)
                                    )
                                }
                            }
                        }

                    }

                    Box(
                        modifier = Modifier
                            .onGloballyPositioned {
                                selectionAreaHeight = it.size.height.toFloat()
                                selectionAreaWidth = it.size.width.toFloat()
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    focusRequester.requestFocus()
                                    state.resetSelection()
                                    isPointerPressed = true
                                    currentPointerPosition = down.position

                                    val start = resolveSelectionPosition(down.position, size.width.toFloat())
                                    if (start == null) {
                                        isPointerPressed = false
                                        currentPointerPosition = Offset.Unspecified
                                        selectionAnchor = null
                                        return@awaitEachGesture
                                    }
                                    val startIndex = start.first
                                    val startOffset = start.second
                                    selectionAnchor = start
                                    state.setCursorPosition(startIndex, startOffset)
                                    state.setHoverPosition(startIndex, startOffset)

                                    drag(down.id) { change ->
                                        currentPointerPosition = change.position
                                        val end = resolveSelectionPosition(change.position, size.width.toFloat()) ?: return@drag
                                        val endIndex = end.first
                                        val endOffset = end.second
                                        state.setSelection(startIndex, startOffset, endIndex, endOffset)
                                        state.setCursorPosition(endIndex, endOffset)
                                        state.setHoverPosition(endIndex, endOffset)
                                    }

                                    isPointerPressed = false
                                    currentPointerPosition = Offset.Unspecified
                                }
                            }
                    ) {
                        LazyColumn(state = hexDataListState) {
                            items(count = rowCount) { rowIndex ->
                                Box(
                                    modifier = Modifier
                                        .background(HexanaTheme.backgroundColor)
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(spacing),
                                    ) {
                                        val start = rowIndex * bytesPerRow
                                        val end = min(start + bytesPerRow, buf.limit())

                                        (start until end).forEachIndexed { byteIndex, absoluteIndex ->
                                            buf.position(absoluteIndex)
                                            val byte = buf.get()
                                            val originalText = (byte.toInt() and 0xFF).asHexString()
                                            val isUnderCursor = byteIndex == state.cursorPosition.lineOffset &&
                                                    rowIndex == state.cursorPosition.line
                                            // Show pending edit digit on the cursor cell when one is queued.
                                            val byteText = if (isUnderCursor && pendingDigit != null) {
                                                pendingDigit!!.toString(16).uppercase() + "_"
                                            } else {
                                                originalText
                                            }

                                            val isHover =
                                                byteIndex == state.hoverPosition.lineOffset && rowIndex == state.hoverPosition.line

                                            val isSearchResult = state.searchResults.value.any { range ->
                                                absoluteIndex.toLong() in range
                                            }

                                            val highlightRange = state.highlightRanges.firstOrNull { range ->
                                                absoluteIndex.toLong() in range.offset until (range.offset + range.size)
                                            }

                                            val isEdited = modifiedRanges.any { range ->
                                                absoluteIndex.toLong() in range.offset until (range.offset + range.length)
                                            }

                                            val backgroundColor = if (isUnderCursor) {
                                                HexanaTheme.hexCellActiveColor
                                            } else if (isHover) {
                                                HexanaTheme.hexCellHoverColor
                                            } else if (highlightRange != null) {
                                                highlightRange.color
                                            } else {
                                                Color.Unspecified
                                            }

                                            val selectionBackgroundColor =
                                                if (state.selection.contains(rowIndex, byteIndex)) {
                                                    Color(156, 183, 255, 255)
                                                } else if (isSearchResult) {
                                                    HexanaTheme.searchResultColor
                                                } else {
                                                    backgroundColor
                                                }


                                            Box(
                                                modifier = Modifier
                                                    .padding(end = 2.dp)
                                                    .background(selectionBackgroundColor)
                                                    .onHover { hovered ->
                                                        if (hovered) state.setHoverPosition(rowIndex, byteIndex)
                                                    }
                                            ) {
                                                Text(
                                                    byteText,
                                                    softWrap = false,
                                                    maxLines = 1,
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        color = if (isEdited) HexanaTheme.editedByteForegroundColor else HexanaTheme.hexTextColor,
                                                        fontWeight = if (isEdited) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    focusRequester.requestFocus()
                                    state.resetSelection()
                                    isPointerPressed = true
                                    currentPointerPosition = down.position

                                    val textPaddingPx = 8.dp.toPx()
                                    val start = resolveTextSelectionPosition(down.position, textPaddingPx, monoCharWidthPx, textDataListState)
                                    if (start == null) {
                                        isPointerPressed = false
                                        currentPointerPosition = Offset.Unspecified
                                        selectionAnchor = null
                                        return@awaitEachGesture
                                    }
                                    val startIndex = start.first
                                    val startOffset = start.second
                                    selectionAnchor = start
                                    state.setCursorPosition(startIndex, startOffset)
                                    state.setHoverPosition(startIndex, startOffset)

                                    drag(down.id) { change ->
                                        currentPointerPosition = change.position
                                        val end = resolveTextSelectionPosition(change.position, textPaddingPx, monoCharWidthPx, textDataListState) ?: return@drag
                                        val endIndex = end.first
                                        val endOffset = end.second
                                        state.setSelection(startIndex, startOffset, endIndex, endOffset)
                                        state.setCursorPosition(endIndex, endOffset)
                                        state.setHoverPosition(endIndex, endOffset)
                                    }

                                    isPointerPressed = false
                                    currentPointerPosition = Offset.Unspecified
                                }
                            }
                    ) {
                        LazyColumn(state = textDataListState) {
                            items(count = rowCount) { rowIndex ->
                                Row(
                                    modifier = Modifier
                                        .background(HexanaTheme.backgroundColor).fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    val start = rowIndex * bytesPerRow
                                    val end = min(start + bytesPerRow, buf.limit())
                                    (start until end).forEachIndexed { byteIndex, _ ->
                                        buf.position(start + byteIndex)
                                        val isHover =
                                            byteIndex == state.hoverPosition.lineOffset && rowIndex == state.hoverPosition.line
                                        val isUnderCursor = byteIndex == state.cursorPosition.lineOffset &&
                                                rowIndex == state.cursorPosition.line

                                        val isSearchResult = state.searchResults.value.any { range ->
                                            (rowIndex * bytesPerRow + byteIndex).toLong() in range
                                        }

                                        val highlightRange = state.highlightRanges.firstOrNull { range ->
                                            (rowIndex * bytesPerRow + byteIndex).toLong() in range.offset until (range.offset + range.size)
                                        }

                                        val absoluteIndex = rowIndex * bytesPerRow + byteIndex
                                        val isEdited = modifiedRanges.any { range ->
                                            absoluteIndex.toLong() in range.offset until (range.offset + range.length)
                                        }

                                        val backgroundColor = if (isUnderCursor) {
                                            HexanaTheme.hexCellActiveColor
                                        } else if (isHover) {
                                            HexanaTheme.hexCellHoverColor
                                        } else if (highlightRange != null) {
                                            highlightRange.color
                                        } else {
                                            Color.Unspecified
                                        }

                                        val selectionBackgroundColor =
                                            if (state.selection.contains(rowIndex, byteIndex)) {
                                                Color(156, 183, 255, 255)
                                            } else if (isSearchResult) {
                                                HexanaTheme.searchResultColor
                                            } else {
                                                backgroundColor
                                            }

                                        val text = if (state.showAscii) {
                                            buf.get().asASCII()
                                        } else {
                                            getUtf8CharacterAtIndex(buf, start + byteIndex)
                                        }

                                        val textForeground = if (isEdited) {
                                            HexanaTheme.editedByteForegroundColor
                                        } else if (text != null) {
                                            HexanaTheme.textColor
                                        } else {
                                            HexanaTheme.hexTextColor
                                        }

                                        Text(
                                            text ?: ".",
                                            modifier = Modifier.background(selectionBackgroundColor),
                                            softWrap = false,
                                            maxLines = 1,
                                            style = TextStyle(
                                                color = textForeground,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isEdited) FontWeight.Bold else FontWeight.Normal
                                            )
                                        )
                                    }
                                }
                            }
                        }


                        // Show scrollbar only when content overflows the viewport
                        val showScrollbar by remember(hexDataListState) {
                            derivedStateOf {
                                val layout = hexDataListState.layoutInfo
                                val total = layout.totalItemsCount
                                if (total == 0) return@derivedStateOf false
                                val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
                                val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                                // If not all items are visible, show scrollbar
                                !(first == 0 && last >= total - 1)
                            }
                        }

                        if (showScrollbar) {
                            val scrollbarColor = HexanaTheme.hexTextColor
                            VerticalScrollbar(
                                modifier = Modifier
                                    .align(alignment = Alignment.CenterEnd)
                                    .fillMaxHeight().width(18.dp).padding(end = 8.dp),
                                adapter = rememberScrollbarAdapter(scrollState = hexDataListState),
                                style = ScrollbarStyle(
                                    minimalHeight = 16.dp,
                                    thickness = 8.dp,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                    hoverDurationMillis = 300,
                                    unhoverColor = scrollbarColor.copy(alpha = 0.50f),
                                    hoverColor = scrollbarColor.copy(alpha = 0.80f),
                                )
                            )
                        }
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showShareFeedbackPopup,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = tween(durationMillis = 140)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isDarkTheme) {
                            Color.Black.copy(alpha = 0.18f)
                        } else {
                            Color(0xFFDCE9FF).copy(alpha = 0.22f)
                        }
                    )
                    .onClick {
                        showShareFeedbackPopup = false
                    }
            ) {
                ShareFeedbackPopup(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 44.dp, end = 16.dp),
                    showInIdeFeedbackAction = isInIdeFeedbackEnabled(),
                    onReportBug = {
                        BrowserUtil.browse(HexanaIssueLinks.bugReportUrl(binaryProvider, isDarkTheme))
                        showShareFeedbackPopup = false
                    },
                    onRateOnMarketplace = {
                        BrowserUtil.browse(HexanaMarketplaceUrl)
                        showShareFeedbackPopup = false
                    },
                    onShareFeedback = {
                        HexanaFeedbackOpener.open(state.project)
                        showShareFeedbackPopup = false
                    },
                    onNeverShowAgain = {
                        HexanaSettings.getInstance().feedbackPromptDisabled = true
                        feedbackPromptDisabled = true
                        showShareFeedbackPopup = false
                    },
                    onDismiss = {
                        showShareFeedbackPopup = false
                    },
                )
            }
        }


    }
}

@Composable
private fun BoxWithConstraintsScope.shouldShowFloatingShareFeedbackButtonInHexViewer(
    availableWidth: Dp,
    bytesPerRow: Int,
    showOffsets: Boolean,
    offsetWidth: Dp,
    spacing: Dp,
): Boolean {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val monospaceTextStyle = remember {
        TextStyle(fontFamily = FontFamily.Monospace)
    }

    return remember(availableWidth, bytesPerRow, showOffsets, offsetWidth, spacing, density) {
        val totalWidthPx = with(density) { availableWidth.roundToPx() }
        val offsetColumnWidthPx = if (showOffsets) {
            with(density) { offsetWidth.roundToPx() }
        } else {
            0
        }
        val hexCellTextWidthPx = textMeasurer.measure(
            text = "00",
            style = monospaceTextStyle,
        ).size.width
        val textColumnCharacterWidthPx = textMeasurer.measure(
            text = "W",
            style = monospaceTextStyle,
        ).size.width
        val hexCellPaddingPx = with(density) { 2.dp.roundToPx() }
        val hexColumnHorizontalPaddingPx = with(density) { 16.dp.roundToPx() }
        val textColumnHorizontalPaddingPx = with(density) { 16.dp.roundToPx() }
        val interByteSpacingPx = with(density) { spacing.roundToPx() }
        val minimumFreeGapPx = with(density) { 24.dp.roundToPx() }
        val floatingButtonHorizontalPaddingPx = with(density) { 32.dp.roundToPx() }
        val floatingButtonOuterPaddingPx = with(density) { 20.dp.roundToPx() }

        val hexColumnWidthPx = hexColumnHorizontalPaddingPx +
                bytesPerRow * (hexCellTextWidthPx + hexCellPaddingPx) +
                (bytesPerRow - 1).coerceAtLeast(0) * interByteSpacingPx

        val textColumnWidthPx = textColumnHorizontalPaddingPx + max(
            textMeasurer.measure(
                text = "ASCII",
                style = monospaceTextStyle,
            ).size.width,
            bytesPerRow * textColumnCharacterWidthPx,
        )

        val floatingButtonWidthPx = textMeasurer.measure(
            text = ShareFeedbackButtonLabel,
            style = TextStyle(fontSize = 13.sp),
        ).size.width + floatingButtonHorizontalPaddingPx + floatingButtonOuterPaddingPx

        totalWidthPx - offsetColumnWidthPx - hexColumnWidthPx - textColumnWidthPx - minimumFreeGapPx >= floatingButtonWidthPx
    }
}

private fun getContextActions(
    buf: CommonByteBuffer,
    state: HexViewStateHolder,
    binaryProvider: BinaryProvider,
): List<ContextMenuItem> {
    val wasmModule = (binaryProvider as? WasmBinaryProvider)?.wasmModule
    val codeSection = wasmModule?.codeSection
    val dataSection = wasmModule?.dataSection
    val codeSectionRange = codeSection?.contentRange ?: IntRange.EMPTY
    val dataSectionRange = dataSection?.contentRange ?: IntRange.EMPTY
    val bytesPerRow = state.bytesPerRow.value
    val position = state.hoverPosition
    val byteOffset = position.line * bytesPerRow + position.lineOffset

    val result = mutableListOf<ContextMenuItem>()
    result.add(ContextMenuItem("Copy as ASCII") { copySelection(buf, state, ::selectedAsASCII) })
    result.add(ContextMenuItem("Copy as Kotlin::listOf") { copySelection(buf, state, ::selectAsKotlinList) })

    if (binaryProvider is WasmBinaryProvider && state.editor != null) {
        when (byteOffset) {
            in codeSectionRange -> {
                result.add(ContextMenuItem("Open in function in WAT") {
                    openInWat(state) { file ->
                        val functionIndex =
                            binaryProvider.getCodeRecordIndexAtByteOffset(byteOffset.toLong())
                        getDefinedFunctionByIndex(file, functionIndex)
                    }
                })
            }

            in dataSectionRange -> {
                result.add(ContextMenuItem("Open in data in WAT") {
                    openInWat(state) { file ->
                        val index = binaryProvider.getDataRecordIndexAtByteOffset(byteOffset.toLong())
                        getDataByIndex(file, index)
                    }
                })
            }
        }
    }

    val locationProvider =
        (binaryProvider as? WasmBinaryProvider)?.sourceLocationProvider ?: HexanaSourceLocationProvider.EMPTY
    if (byteOffset in codeSectionRange) {
        val sourceLocation = locationProvider.getSourceLocationForOffset(byteOffset)
        if (sourceLocation != null) {
            result.add(ContextMenuItem("Open in sources") {
                HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
                    val virtualFile = readAction {
                        try {
                            VfsUtil.findFile(Path.of(sourceLocation.filePath), true)
                        } catch (_: Exception) {
                            null
                        }
                    }

                    if (virtualFile != null) {
                        withContext(Dispatchers.EDT) {
                            val descriptor = OpenFileDescriptor(
                                state.project,
                                virtualFile,
                                sourceLocation.line,
                                sourceLocation.column
                            )
                            descriptor.navigate(true)
                        }
                    } else {
                        val message = HexanaBundle.message("notifications.fileNotFound", sourceLocation.filePath)
                        Notification(
                            HexanaService.NOTIFICATION_GROUP,
                            message,
                            NotificationType.WARNING
                        ).notify(state.project)
                    }
                }
            })
        }
    }
    return result
}


/**
 * Maps a Compose [Key] to its hex-digit value `[0..15]` if it represents one,
 * or null otherwise. Accepts `0-9` (number row OR numpad) and `a-f` /`A-F`
 * (letter shift state doesn't matter — `Key.A` represents the physical key).
 */
private fun hexDigitValue(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    Key.A -> 0xA
    Key.B -> 0xB
    Key.C -> 0xC
    Key.D -> 0xD
    Key.E -> 0xE
    Key.F -> 0xF
    else -> null
}

/**
 * Applies a single hex digit to the current cursor cell.
 *
 * - When [pending] is `null`, this is the high nibble — store and wait for
 *   the second digit; returns the new pending value.
 * - When [pending] is non-null, this is the low nibble — combine into a byte,
 *   commit `replace(off, off+1, byte)` on [doc], advance the cursor to the
 *   next cell, and return `null` to clear the pending state.
 *
 * If the cursor sits past the end of the document we drop the digit silently
 * (return whatever [pending] was).
 */
private fun handleHexDigit(
    digit: Int,
    pending: Int?,
    bytesPerRow: Int,
    state: HexViewStateHolder,
    doc: EditableBinaryDocument,
    onCommit: (() -> Unit)? = null,
): Int? {
    val absoluteOffset = (state.cursorPosition.line.toLong() * bytesPerRow.toLong()) +
        state.cursorPosition.lineOffset.toLong()
    if (absoluteOffset < 0L || absoluteOffset >= doc.length) return pending

    if (pending == null) {
        return digit
    }

    val byte = ((pending and 0xF) shl 4) or (digit and 0xF)
    doc.edit().apply {
        replace(absoluteOffset, absoluteOffset + 1L, byteArrayOf(byte.toByte()))
        commit()
    }
    onCommit?.invoke()
    val nextOffset = absoluteOffset + 1L
    if (nextOffset < doc.length) {
        val nextLine = (nextOffset / bytesPerRow.toLong()).toInt()
        val nextLineOffset = (nextOffset % bytesPerRow.toLong()).toInt()
        state.setCursorPosition(nextLine, nextLineOffset)
    }
    return null
}

private fun Int.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()
private fun Byte.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()
private fun UByte.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()

fun Byte.asASCII(): String? {
    val b = toInt() and 0xFF
    return if (b in 32..126) b.toChar().toString() else null
}

fun Modifier.borderBottom(color: Color, width: Dp, verticalPadding: Dp): Modifier = this.drawBehind {
    val strokeWidthPx = width.toPx()
    val y = size.height + verticalPadding.toPx() - strokeWidthPx / 2

    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = strokeWidthPx
    )
}

private fun selectedAsASCII(buf: CommonByteBuffer, state: HexViewStateHolder): String? {
    val sel = state.selection
    if (sel.start.line < 0 || sel.end.line < 0) return null

    val bytesPerRow = state.bytesPerRow.value

    val startOffset = sel.start.line * bytesPerRow + sel.start.lineOffset
    val endOffset = sel.end.line * bytesPerRow + sel.end.lineOffset

    if (startOffset < 0 || endOffset < 0 || startOffset >= buf.limit()) return null
    val last = min(endOffset, buf.lastIndex)
    if (last < startOffset) return null

    buf.position(startOffset)
    return (startOffset..last).joinToString("") { buf.get().asASCII() ?: "." }
}

private fun selectAsKotlinList(buf: CommonByteBuffer, state: HexViewStateHolder): String? {
    val sel = state.selection
    if (sel.start.line < 0 || sel.end.line < 0) return null

    val bytesPerRow = state.bytesPerRow.value

    val startOffset = sel.start.line * bytesPerRow + sel.start.lineOffset
    val endOffset = sel.end.line * bytesPerRow + sel.end.lineOffset

    if (startOffset < 0 || endOffset < 0 || startOffset >= buf.limit()) return null
    val last = min(endOffset, buf.lastIndex)
    if (last < startOffset) return null

    buf.position(startOffset)
    val contents = (startOffset..last).joinToString { "0x" + buf.get().toUByte().asHexString() + "u" }
    return "listOf(${contents})"
}

fun copySelection(
    buf: CommonByteBuffer,
    state: HexViewStateHolder,
    selectionBuilder: (CommonByteBuffer, HexViewStateHolder) -> String?
) {
    val position = buf.position()
    val text = try {
        selectionBuilder(buf, state)
    } finally {
        buf.position(position)
    }
    if (!text.isNullOrEmpty()) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}


fun openInWat(state: HexViewStateHolder, findElement: (file: PsiFile) -> PsiElement?) {
    val watEditor = state.editor ?: return
    val project = watEditor.project ?: return
    val virtualFile = watEditor.virtualFile ?: return
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return

    val element = findElement(psiFile) ?: return

    watEditor.caretModel.moveToOffset(element.startOffset)
    watEditor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    state.setSelectedTab(3)
    ApplicationManager.getApplication().invokeLater {
        UIUtil.findComponentOfType(watEditor.component, EditorComponentImpl::class.java)?.requestFocusInWindow()
    }
}

fun getUtf8CharacterAtIndex(buf: CommonByteBuffer, index: Int): String? {
    val bufferPosition = buf.position()
    buf.position(index)
    try {
        val firstByte = buf.get().toInt()
        val length: Int = when {
            (firstByte and 0b10000000) == 0b00000000 -> 1
            (firstByte and 0b11100000) == 0b11000000 -> 2
            (firstByte and 0b11110000) == 0b11100000 -> 3
            (firstByte and 0b11111000) == 0b11110000 -> 4
            else -> return null
        }

        if (index + length > buf.limit()) {
            return null // Incomplete sequence
        }

        try {
            val out = CharBuffer.allocate(2)
            // Max size is 1 char for 1-3 byte sequences, and 2 chars (surrogate pair)
            // for 4-byte sequences (Code Points U+10000 to U+10FFFF).
            val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
            buf.position(index)
            decoder.decode(buf.toByteBuffer().slice(index, length), out, true)
            decoder.flush(out)

            if (out.position() == 1) { // don't show surrogate pairs
                out.flip()
                val char = out.get(0)
                return when {
                    Character.isISOControl(char) -> null
                    Character.isDefined(char) -> char.toString()
                    else -> null
                }
            }
        } catch (_: Exception) {
            // Decoding failed
            return null
        }

        return null

    } finally {
        buf.position(bufferPosition)
    }
}
