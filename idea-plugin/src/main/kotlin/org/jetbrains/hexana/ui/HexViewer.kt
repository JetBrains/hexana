package org.jetbrains.hexana.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.startOffset
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewMode
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.HexanaSourceLocationProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wat.psi.getDataByIndex
import org.jetbrains.hexana.wat.psi.getDefinedFunctionByIndex
import org.jetbrains.jewel.foundation.modifier.onHover
import org.jetbrains.jewel.ui.component.Text
import java.awt.datatransfer.StringSelection
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.math.min


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
    showOffsets: Boolean = true
) {
    val buf = binaryProvider.bytes
    val focusRequester = remember { FocusRequester() }


    BoxWithConstraints(
        modifier = Modifier
            .focusRequester(focusRequester)
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

        val bytesPerRow by remember { state.bytesPerRow }

        val separator = 4.dp
        val spacing = 8.dp

        val rowCount = (buf.size + bytesPerRow - 1) / bytesPerRow
        val offsetWidth = 96.dp

        LaunchedEffect(state) {
            state.hexScrollFlow.collect { offset ->
                val lineNo = (offset / bytesPerRow).toInt()
                hexDataListState.animateScrollToItem(lineNo)
            }
        }


        Column(modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (showOffsets) {
                    Box(
                        modifier = Modifier
                            .width(offsetWidth)
                            .background(HexanaTheme.backgroundColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
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
                        .padding(horizontal = 8.dp, vertical = 4.dp).background(HexanaTheme.backgroundColor)
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
                                        (rowIndex * bytesPerRow).asHexString(padStart = 8),
                                        softWrap = false,
                                        style = TextStyle(fontFamily = FontFamily.Monospace)
                                    )
                                }
                            }
                        }

                    }

                    Box(
                        modifier = Modifier
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    state.resetSelection()

                                    val visibleItemsInfo = hexDataListState.layoutInfo.visibleItemsInfo
                                    val indexResolvedRelative = visibleItemsInfo.indexOfLast {
                                        it.offset <= down.position.y
                                    }

                                    val startIndex = indexResolvedRelative + hexDataListState.firstVisibleItemIndex
                                    val cellWidth = (size.width / bytesPerRow)
                                    val startOffset = (down.position.x / cellWidth).toInt()

                                    drag(down.id) { change ->
                                        val visibleItemsInfo = hexDataListState.layoutInfo.visibleItemsInfo
                                        val indexResolvedRelative = visibleItemsInfo.indexOfLast {
                                            it.offset <= change.position.y
                                        }

                                        val endIndex = indexResolvedRelative + hexDataListState.firstVisibleItemIndex
                                        val endOffset = (change.position.x / cellWidth).toInt()

                                        state.setSelection(startIndex, startOffset, endIndex, endOffset)
                                    }
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
                                        val end = min(start + bytesPerRow, buf.size)

                                        buf.slice(start until end).forEachIndexed { byteIndex, byte ->
                                            val byteText = (byte.toInt() and 0xFF).asHexString()
                                            val isUnderCursor = byteIndex == state.cursorPosition.lineOffset &&
                                                    rowIndex == state.cursorPosition.line

                                            val isHover =
                                                byteIndex == state.hoverPosition.lineOffset && rowIndex == state.hoverPosition.line

                                            val backgroundColor = if (isHover) {
                                                if (isUnderCursor) HexanaTheme.hexCellHoverActiveColor else HexanaTheme.hexCellHoverColor
                                            } else {
                                                if (isUnderCursor) HexanaTheme.hexCellActiveColor else Color.Unspecified
                                            }

                                            val selectionBackgroundColor =
                                                if (state.selection.contains(rowIndex, byteIndex)) {
                                                    Color(156, 183, 255, 255)
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
                                                        color = HexanaTheme.hexTextColor
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box {
                        LazyColumn(state = textDataListState) {
                            items(count = rowCount) { rowIndex ->
                                Row(
                                    modifier = Modifier
                                        .background(HexanaTheme.backgroundColor).fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    val start = rowIndex * bytesPerRow
                                    val end = min(start + bytesPerRow, buf.size)
                                    buf.slice(start until end).forEachIndexed { byteIndex, byte ->
                                        val isHover =
                                            byteIndex == state.hoverPosition.lineOffset && rowIndex == state.hoverPosition.line
                                        val isUnderCursor = byteIndex == state.cursorPosition.lineOffset &&
                                                rowIndex == state.cursorPosition.line


                                        val backgroundColor = if (isHover) {
                                            if (isUnderCursor) HexanaTheme.hexCellHoverActiveColor else HexanaTheme.hexCellHoverColor
                                        } else {
                                            if (isUnderCursor) HexanaTheme.hexCellActiveColor else Color.Unspecified
                                        }

                                        val selectionBackgroundColor =
                                            if (state.selection.contains(rowIndex, byteIndex)) {
                                                Color(156, 183, 255, 255)
                                            } else {
                                                backgroundColor
                                            }

                                        val text = if (state.showAscii) {
                                            byte.asASCII()
                                        } else {
                                            getUtf8CharacterAtIndex(buf, start + byteIndex)
                                        }
                                        Text(
                                            text ?: ".",
                                            modifier = Modifier.background(selectionBackgroundColor),
                                            softWrap = false,
                                            maxLines = 1,
                                            style = TextStyle(
                                                color = if (text != null) HexanaTheme.textColor else HexanaTheme.hexTextColor,
                                                fontFamily = FontFamily.Monospace
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
                            VerticalScrollbar(
                                modifier = Modifier
                                    .align(alignment = Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .background(Color.Gray),
                                adapter = rememberScrollbarAdapter(scrollState = hexDataListState)
                            )
                        }
                    }
                }
            }
        }


    }
}

private fun getContextActions(
    buf: ByteArray,
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

    val locationProvider = (binaryProvider as? WasmBinaryProvider)?.sourceLocationProvider ?: HexanaSourceLocationProvider.EMPTY
    if (byteOffset in codeSectionRange) {
        val sourceLocation = locationProvider.getSourceLocationForOffset(byteOffset)
        if (sourceLocation != null) {
            result.add(ContextMenuItem("Open in sources") {
                HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
                    val virtualFile = readAction {
                        try {
                            VfsUtil.findFile(Path.of(sourceLocation.filePath), true)
                        }
                        catch (_: Exception) {
                            null
                        }
                    }

                    if (virtualFile != null) {
                        withContext(Dispatchers.EDT) {
                            val descriptor = OpenFileDescriptor(state.project, virtualFile, sourceLocation.line, sourceLocation.column)
                            descriptor.navigate(true)
                        }
                    }
                    else {
                        val message = HexanaBundle.message("notifications.fileNotFound", sourceLocation.filePath)
                        Notification(HexanaService.NOTIFICATION_GROUP, message, NotificationType.WARNING).notify(state.project)
                    }
                }
            })
        }
    }
    return result
}


private fun Int.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()
private fun Byte.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()
private fun UByte.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()

private fun Byte.asASCII(): String? {
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

private fun selectedAsASCII(buf: ByteArray, state: HexViewStateHolder): String? {
    val sel = state.selection
    if (sel.start.line < 0 || sel.end.line < 0) return null

    val bytesPerRow = state.bytesPerRow.value

    val startOffset = sel.start.line * bytesPerRow + sel.start.lineOffset
    val endOffset = sel.end.line * bytesPerRow + sel.end.lineOffset

    if (startOffset < 0 || endOffset < 0 || startOffset >= buf.size) return null
    val last = min(endOffset, buf.lastIndex)
    if (last < startOffset) return null

    return (startOffset..last).joinToString("") { buf[it].asASCII() ?: "." }
}

private fun selectAsKotlinList(buf: ByteArray, state: HexViewStateHolder): String? {
    val sel = state.selection
    if (sel.start.line < 0 || sel.end.line < 0) return null

    val bytesPerRow = state.bytesPerRow.value

    val startOffset = sel.start.line * bytesPerRow + sel.start.lineOffset
    val endOffset = sel.end.line * bytesPerRow + sel.end.lineOffset

    if (startOffset < 0 || endOffset < 0 || startOffset >= buf.size) return null
    val last = min(endOffset, buf.lastIndex)
    if (last < startOffset) return null

    val contents = (startOffset..last).joinToString { "0x" + buf[it].toUByte().asHexString() + "u" }
    return "listOf(${contents})"
}

fun copySelection(
    buf: ByteArray,
    state: HexViewStateHolder,
    selectionBuilder: (ByteArray, HexViewStateHolder) -> String?
) {
    val text = selectionBuilder(buf, state)
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


fun getUtf8CharacterAtIndex(byteArray: ByteArray, index: Int): String? {
    val firstByte = byteArray[index].toInt()
    val length: Int = when {
        (firstByte and 0b10000000) == 0b00000000 -> 1
        (firstByte and 0b11100000) == 0b11000000 -> 2
        (firstByte and 0b11110000) == 0b11100000 -> 3
        (firstByte and 0b11111000) == 0b11110000 -> 4
        else -> return null
    }

    if (index + length > byteArray.size) {
        return null // Incomplete sequence
    }

    try {
        val out = CharBuffer.allocate(2)
        // Max size is 1 char for 1-3 byte sequences, and 2 chars (surrogate pair)
        // for 4-byte sequences (Code Points U+10000 to U+10FFFF).
        val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        decoder.decode(ByteBuffer.wrap(byteArray, index, length), out, true)
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
}