package org.jetbrains.hexana.ui.panel

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettingsUtils
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorGutterAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.TextAnnotationGutterProvider
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.tree.IElementType
import com.intellij.ui.EditorNotificationPanel
import com.intellij.util.asSafely
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.hexana.HexViewMode
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.wat.LightWatFile
import org.jetbrains.hexana.wat.WatFileset
import org.jetbrains.hexana.wat.WatSyntaxHighlighter
import org.jetbrains.hexana.wat.createWatFileset
import org.jetbrains.hexana.wat.psi.WatLexer
import org.jetbrains.hexana.wat.psi.WatToken
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.BorderLayout
import java.awt.Cursor
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.Color as AwtColor


@Composable
internal fun WasmPanelWat(
    wasmBinaryProvider: BinaryVirtualFileProvider,
    state: HexViewStateHolder,
    wasmViewModel: WasmViewModel = WasmViewModel(state, wasmBinaryProvider)
) {
    val wasmViewState by wasmViewModel.conversionState.collectAsState()
    state.setViewMode(HexViewMode.Wat)

    DisposableEffect(Unit) {
        onDispose {
            state.setViewMode(HexViewMode.Hex)
        }
    }

    when (wasmViewState) {
        null, is WatConversionState.Loading -> ConvertingIndicator(message = "0000 0 1001 01 10 01 converting ${wasmBinaryProvider.file.name}")

        is WatConversionState.ShowLiteEditor -> {
            val editor = wasmViewState as WatConversionState.ShowLiteEditor
            WatLiteEditor(editor.wat, editor.lineOffsets, state)
        }

        is WatConversionState.ShowEditor -> {
            val editor = wasmViewState as WatConversionState.ShowEditor
            WatEditorContainer(editor.editorComponent, editor.warningComponent)
        }

        is WatConversionState.ShowMultifileEditor -> {
            WatMultifileEditorContainer(
                state,
                wasmViewState as WatConversionState.ShowMultifileEditor,
                wasmBinaryProvider
            )
        }

        is WatConversionState.Error -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    AllIconsKeys.General.Error,
                    contentDescription = null,
                    iconClass = AllIcons::class.java
                )
                Text((wasmViewState as WatConversionState.Error).error)
            }
        }
    }
}

private data class TokenData(val tokenType: IElementType, val text: String)


@Composable
@ReadOnlyComposable
fun TextAttributesKey.toComposeColor(): Color {
    val globalScheme = EditorColorsManager.getInstance().globalScheme
    val attributes = globalScheme.getAttributes(this)
    val awtColor = attributes?.foregroundColor ?: globalScheme.defaultForeground
    return Color(awtColor.rgb)
}

@Composable
internal fun WatLiteEditor(watContent: String, lineOffsets: Map<Int, String>, state: HexViewStateHolder) {
    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    val searchFocusRequester = remember { FocusRequester() }
    val editorFocusRequester = remember { FocusRequester() }

    val matches by remember(watContent, searchQuery.text) {
        derivedStateOf {
            if (searchQuery.text.isEmpty()) emptyList<IntRange>()
            else {
                val result = mutableListOf<IntRange>()
                var index = watContent.indexOf(searchQuery.text, ignoreCase = true)
                while (index != -1) {
                    result.add(index until index + searchQuery.text.length)
                    index = watContent.indexOf(searchQuery.text, index + searchQuery.text.length, ignoreCase = true)
                }
                result
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val tokens by remember {
        derivedStateOf {
            val result = mutableListOf<List<TokenData>>()
            val lexer = WatLexer()
            lexer.start(watContent, 0, watContent.length)
            var tokenType: IElementType?
            var currentTokens = mutableListOf<TokenData>()
            while ((lexer.tokenType.also { tokenType = it }) != null) {
                if (tokenType == WatToken.NEWLINE) {
                    result.add(currentTokens)
                    currentTokens = mutableListOf()
                } else {
                    currentTokens.add(TokenData(tokenType!!, lexer.tokenText!!))
                }
                lexer.advance()
            }

            result
        }
    }

    val lineStartOffsets by remember(tokens) {
        derivedStateOf {
            val offsets = mutableListOf<Int>()
            var currentOffset = 0
            tokens.forEach { line ->
                offsets.add(currentOffset)
                line.forEach { currentOffset += it.text.length }
                currentOffset += 1 // for newline
            }
            offsets
        }
    }

    var cursorLine by remember { mutableIntStateOf(0) }
    var cursorColumn by remember { mutableIntStateOf(0) }
    var selectionStartLine by remember { mutableIntStateOf(0) }
    var selectionStartColumn by remember { mutableIntStateOf(0) }

    val lineTexts by remember(tokens) {
        derivedStateOf {
            tokens.map { line -> line.joinToString("") { it.text } }
        }
    }

    fun updateSelection(isShiftPressed: Boolean) {
        if (!isShiftPressed) {
            selectionStartLine = cursorLine
            selectionStartColumn = cursorColumn
        }
    }

    LaunchedEffect(cursorLine, cursorColumn) {
        val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
        val isVisible = visibleItems.any { it.index == cursorLine }
        if (!isVisible) {
            lazyListState.animateScrollToItem(cursorLine)
        } else {
            val item = visibleItems.find { it.index == cursorLine }
            if (item != null) {
                val viewportHeight = lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
                if (item.offset < 0 || item.offset + item.size > viewportHeight) {
                    lazyListState.animateScrollToItem(cursorLine)
                }
            }
        }
    }

    fun scrollToMatch(index: Int) {
        if (matches.isEmpty()) return
        val match = matches[index % matches.size]
        val lineIndex = lineStartOffsets.binarySearch(match.first).let { if (it < 0) -it - 2 else it }
        if (lineIndex >= 0) {
            coroutineScope.launch {
                lazyListState.animateScrollToItem(lineIndex)
            }
        }
    }

    val highlighter = WatSyntaxHighlighter()

    val offsetPattern = remember { Regex("""\(;@([0-9a-fA-F]+)\s*;\)""") }
    val showOffsets = remember { HexanaSettings.getInstance().showOffsets == true }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        editorFocusRequester.requestFocus()
    }

    val clipboardManager = LocalClipboardManager.current

    fun copySelectedText() {
        val selStart = if (selectionStartLine < cursorLine || (selectionStartLine == cursorLine && selectionStartColumn <= cursorColumn)) {
            selectionStartLine to selectionStartColumn
        } else {
            cursorLine to cursorColumn
        }
        val selEnd = if (selStart.first == selectionStartLine && selStart.second == selectionStartColumn) {
            cursorLine to cursorColumn
        } else {
            selectionStartLine to selectionStartColumn
        }

        if (selStart == selEnd) return

        val selectedText = buildString {
            for (i in selStart.first..selEnd.first) {
                val line = lineTexts[i]
                val start = if (i == selStart.first) selStart.second else 0
                val end = if (i == selEnd.first) selEnd.second else line.length
                append(line.substring(start, end))
                if (i < selEnd.first) append("\n")
            }
        }
        clipboardManager.setText(AnnotatedString(selectedText))
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .focusRequester(editorFocusRequester)
            .focusable()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.F && (it.isMetaPressed || it.isCtrlPressed)) {
                    isSearchVisible = true
                    true
                } else if (it.type == KeyEventType.KeyDown && it.key == Key.C && (it.isMetaPressed || it.isCtrlPressed)) {
                    copySelectedText()
                    true
                } else if (it.type == KeyEventType.KeyDown && it.key == Key.A && (it.isMetaPressed || it.isCtrlPressed)) {
                    selectionStartLine = 0
                    selectionStartColumn = 0
                    cursorLine = lineTexts.size - 1
                    cursorColumn = lineTexts.last().length
                    true
                } else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && isSearchVisible) {
                    isSearchVisible = false
                    searchQuery = TextFieldValue("")
                    editorFocusRequester.requestFocus()
                    true
                } else if (it.type == KeyEventType.KeyDown) {
                    val isShiftPressed = it.isShiftPressed
                    when (it.key) {
                        Key.DirectionUp -> {
                            if (cursorLine > 0) {
                                cursorLine--
                                cursorColumn = minOf(cursorColumn, lineTexts[cursorLine].length)
                            }
                            updateSelection(isShiftPressed)
                            true
                        }
                        Key.DirectionDown -> {
                            if (cursorLine < lineTexts.size - 1) {
                                cursorLine++
                                cursorColumn = minOf(cursorColumn, lineTexts[cursorLine].length)
                            }
                            updateSelection(isShiftPressed)
                            true
                        }
                        Key.DirectionLeft -> {
                            if (cursorColumn > 0) {
                                cursorColumn--
                            } else if (cursorLine > 0) {
                                cursorLine--
                                cursorColumn = lineTexts[cursorLine].length
                            }
                            updateSelection(isShiftPressed)
                            true
                        }
                        Key.DirectionRight -> {
                            if (cursorColumn < lineTexts[cursorLine].length) {
                                cursorColumn++
                            } else if (cursorLine < lineTexts.size - 1) {
                                cursorLine++
                                cursorColumn = 0
                            }
                            updateSelection(isShiftPressed)
                            true
                        }
                        Key.MoveHome -> {
                            cursorColumn = 0
                            updateSelection(isShiftPressed)
                            true
                        }
                        Key.MoveEnd -> {
                            cursorColumn = lineTexts[cursorLine].length
                            updateSelection(isShiftPressed)
                            true
                        }
                        Key.PageUp -> {
                            cursorLine = maxOf(0, cursorLine - 10)
                            cursorColumn = minOf(cursorColumn, lineTexts[cursorLine].length)
                            updateSelection(isShiftPressed)
                            true
                        }
                        Key.PageDown -> {
                            cursorLine = minOf(lineTexts.size - 1, cursorLine + 10)
                            cursorColumn = minOf(cursorColumn, lineTexts[cursorLine].length)
                            updateSelection(isShiftPressed)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        if (isSearchVisible) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        currentMatchIndex = 0
                        scrollToMatch(0)
                    },
                    modifier = Modifier.weight(1f).focusRequester(searchFocusRequester)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                if (it.isShiftPressed) {
                                    currentMatchIndex = (currentMatchIndex - 1 + matches.size) % matches.size
                                } else {
                                    currentMatchIndex = (currentMatchIndex + 1) % matches.size
                                }
                                scrollToMatch(currentMatchIndex)
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("Search...") },
                    leadingIcon = {
                        Icon(
                            AllIconsKeys.Actions.Search,
                            contentDescription = null,
                            iconClass = AllIcons::class.java,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (matches.isNotEmpty()) {
                                Text(
                                    "${currentMatchIndex + 1} of ${matches.size}",
                                    style = TextStyle(color = Color.Gray)
                                )
                            }
                            IconButton(onClick = {
                                searchQuery = TextFieldValue("")
                                isSearchVisible = false
                                editorFocusRequester.requestFocus()
                            }) {
                                Icon(
                                    AllIconsKeys.Actions.Close,
                                    contentDescription = "Close search",
                                    iconClass = AllIcons::class.java,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                )
                IconButton(onClick = {
                    if (matches.isNotEmpty()) {
                        currentMatchIndex = (currentMatchIndex - 1 + matches.size) % matches.size
                        scrollToMatch(currentMatchIndex)
                    }
                }) {
                    Icon(AllIconsKeys.Actions.PreviousOccurence, "Previous", iconClass = AllIcons::class.java)
                }
                IconButton(onClick = {
                    if (matches.isNotEmpty()) {
                        currentMatchIndex = (currentMatchIndex + 1) % matches.size
                        scrollToMatch(currentMatchIndex)
                    }
                }) {
                    Icon(AllIconsKeys.Actions.NextOccurence, "Next", iconClass = AllIcons::class.java)
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            var boxHeight by remember { mutableFloatStateOf(0f) }
            var boxYOffset by remember { mutableFloatStateOf(0f) }
            var currentPointerY by remember { mutableFloatStateOf(-1f) }
            var isPointerPressed by remember { mutableStateOf(false) }

            val lineTextLayoutResults = remember { mutableStateMapOf<Int, TextLayoutResult>() }

            var currentPointerX by remember { mutableFloatStateOf(-1f) }

            val density = LocalDensity.current
            val offsetWidthPx = if (showOffsets) with(density) { computeWidth(lineOffsets).toPx() } else 0f

            LaunchedEffect(isPointerPressed, currentPointerY, currentPointerX, boxHeight) {
                if (isPointerPressed && currentPointerY != -1f) {
                    while (true) {
                        val threshold = 40.dp.value
                        val scrollSpeed = 50f
                        var scrolled = false
                        if (currentPointerY < threshold) {
                            val ratio = (threshold - maxOf(0f, currentPointerY)) / threshold
                            lazyListState.scrollBy(-scrollSpeed * ratio)
                            scrolled = true
                        } else if (currentPointerY > boxHeight - threshold) {
                            val ratio = (maxOf(0f, currentPointerY - (boxHeight - threshold))) / threshold
                            lazyListState.scrollBy(scrollSpeed * ratio)
                            scrolled = true
                        }

                        if (scrolled) {
                            val item = lazyListState.layoutInfo.visibleItemsInfo.find {
                                currentPointerY >= it.offset && currentPointerY <= it.offset + it.size
                            }
                            if (item != null) {
                                val lineIndex = item.index
                                val layout = lineTextLayoutResults[lineIndex]
                                if (layout != null) {
                                    cursorLine = lineIndex
                                    // How to get local X? Row contains offset + text.
                                    // We need to account for the offset width.
                                    val localX = currentPointerX - offsetWidthPx
                                    cursorColumn = layout.getOffsetForPosition(Offset(localX, 0f))
                                    updateSelection(true)
                                }
                            }
                        }

                        if (!scrolled) break
                        delay(16)
                    }
                }
            }

            SelectionContainer {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize()
                        .onGloballyPositioned {
                            boxHeight = it.size.height.toFloat()
                            boxYOffset = it.positionInWindow().y
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    isPointerPressed = event.buttons.isPrimaryPressed
                                    val change = event.changes.firstOrNull()
                                    if (isPointerPressed && change != null) {
                                        currentPointerY = change.position.y
                                        currentPointerX = change.position.x

                                        // Handle drag across lines
                                        val viewportY = currentPointerY
                                        val item = lazyListState.layoutInfo.visibleItemsInfo.find {
                                            viewportY >= it.offset && viewportY <= it.offset + it.size
                                        }
                                        if (item != null) {
                                            val lineIndex = item.index
                                            val layout = lineTextLayoutResults[lineIndex]
                                            if (layout != null) {
                                                cursorLine = lineIndex
                                                val localX = currentPointerX - offsetWidthPx
                                                cursorColumn = layout.getOffsetForPosition(Offset(localX, 0f))
                                                updateSelection(event.keyboardModifiers.isShiftPressed || event.type == PointerEventType.Move)
                                            }
                                        }
                                    } else {
                                        currentPointerY = -1f
                                        currentPointerX = -1f
                                    }
                                }
                            }
                        }
                ) {
                    items(tokens.size) { lineIndex ->
                        val tokensInLine = tokens[lineIndex]
                        val lineStartOffset = lineStartOffsets[lineIndex]
                        val lineText = tokensInLine.joinToString("") { it.text }

                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (showOffsets) {
                                DisableSelection {
                                    val offsetText = lineOffsets[lineIndex]?.let { "0x$it" } ?: ""
                                    Text(
                                        text = offsetText,
                                        modifier = Modifier.width(computeWidth(lineOffsets)).padding(end = 8.dp)
                                            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                                            .clickable {
                                                lineOffsets[lineIndex]?.let { offsetStr ->
                                                    val offset = offsetStr.toLongOrNull(16) ?: return@let
                                                    state.setViewMode(HexViewMode.Hex)
                                                    state.scrollHexViewTo(offset)
                                                    state.setCursorPosition(offset, state.bytesPerRow.value)
                                                }
                                            },
                                        style = TextStyle(color = Color.Gray, fontFamily = FontFamily.Monospace)
                                    )
                                }
                            }

                            val annotatedString = buildAnnotatedString {
                                var currentPosInLine = 0
                                tokensInLine.forEach { tokenData ->
                                    if (offsetPattern.matches(tokenData.text)) {
                                        currentPosInLine += tokenData.text.length
                                        return@forEach
                                    }
                                    val colors = highlighter.getTokenHighlights(tokenData.tokenType)
                                    val colorResolved = colors.getOrNull(0)
                                    val style = if (colorResolved != null) {
                                        SpanStyle(color = colorResolved.toComposeColor())
                                    } else {
                                        SpanStyle()
                                    }

                                    withStyle(style = style) {
                                        append(tokenData.text)
                                    }

                                    currentPosInLine += tokenData.text.length
                                }

                                // Apply selection highlights
                                val selStart = if (selectionStartLine < cursorLine || (selectionStartLine == cursorLine && selectionStartColumn <= cursorColumn)) {
                                    selectionStartLine to selectionStartColumn
                                } else {
                                    cursorLine to cursorColumn
                                }
                                val selEnd = if (selStart.first == selectionStartLine && selStart.second == selectionStartColumn) {
                                    cursorLine to cursorColumn
                                } else {
                                    selectionStartLine to selectionStartColumn
                                }

                                if (lineIndex in selStart.first..selEnd.first && (selStart != selEnd)) {
                                    val startInLine = if (lineIndex == selStart.first) selStart.second else 0
                                    val endInLine = if (lineIndex == selEnd.first) selEnd.second else lineText.length

                                    if (startInLine < endInLine) {
                                        addStyle(
                                            style = SpanStyle(background = HexanaTheme.searchResultColor.copy(alpha = 0.5f)),
                                            start = startInLine,
                                            end = endInLine
                                        )
                                    }
                                }

                                // Apply search highlights
                                if (searchQuery.text.isNotEmpty()) {
                                    matches.forEachIndexed { index, range ->
                                        val matchStartInLine = range.first - lineStartOffset
                                        val matchEndInLine = range.last - lineStartOffset

                                        val intersectStart = maxOf(0, matchStartInLine)
                                        val intersectEnd = minOf(lineText.length, matchEndInLine)

                                        if (intersectStart < intersectEnd) {
                                            addStyle(
                                                style = SpanStyle(
                                                    background = if (index == currentMatchIndex) {
                                                        Color.Yellow.copy(alpha = 0.5f)
                                                    } else {
                                                        HexanaTheme.searchResultColor.copy(alpha = 0.5f)
                                                    }
                                                ),
                                                start = intersectStart,
                                                end = intersectEnd
                                            )
                                        }
                                    }
                                }
                            }

                            val textMeasurer = rememberTextMeasurer()
                            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            val infiniteTransition = rememberInfiniteTransition()
                            val cursorAlpha by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = keyframes {
                                        durationMillis = 1000
                                        0.7f at 500
                                    },
                                    repeatMode = RepeatMode.Restart
                                )
                            )

                            val isCursorInLine = lineIndex == cursorLine
                            val foregroundColor = HexanaTheme.textColor

                            Text(
                                text = annotatedString,
                                style = style,
                                onTextLayout = { lineTextLayoutResults[lineIndex] = it },
                                modifier = Modifier
                                    .pointerInput(lineIndex) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val isSecondaryPressed = event.buttons.isSecondaryPressed

                                                if (event.type == PointerEventType.Press) {
                                                    if (isSecondaryPressed) {
                                                        editorFocusRequester.requestFocus()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .drawWithContent {
                                    drawContent()
                                    if (isCursorInLine) {
                                        val layoutResult = textMeasurer.measure(
                                            text = annotatedString.substring(0, minOf(cursorColumn, annotatedString.length)),
                                            style = style
                                        )
                                        val cursorX = layoutResult.size.width.toFloat()
                                        drawLine(
                                            color = foregroundColor.copy(alpha = cursorAlpha),
                                            start = Offset(cursorX, 0f),
                                            end = Offset(cursorX, size.height),
                                            strokeWidth = 2f
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            VerticalScrollbar(
                lazyListState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

private fun computeWidth(lineOffsets: Map<Int, String>): Dp {
    val maxLength = lineOffsets.values.maxOfOrNull { it.length } ?: 4
    val width = when {
        maxLength <= 4 -> 60.dp
        maxLength <= 8 -> 80.dp
        else -> 100.dp
    }
    return width
}

@Composable
internal fun WatEditorContainer(
    editorComponent: JComponent?,
    warningComponent: JComponent?,
) {
    if (editorComponent != null) {
        SwingPanelEditor(editorComponent, warningComponent)
    }
}


@Composable
internal fun WatMultifileEditorContainer(
    hexViewState: HexViewStateHolder,
    state: WatConversionState.ShowMultifileEditor,
    wasmBinaryProvider: BinaryVirtualFileProvider,
) {
    val panelState by produceState<JComponent?>(
        initialValue = null,
        key1 = state,
        key2 = hexViewState.currentState.value.fileIndex
    ) {
        withContext(Dispatchers.EDT) {
            val fileset = state.fileset
            val currentFile = fileset.currentFile
            val watTextEditor = wasmBinaryProvider.createTextEditor(currentFile)
            fileset.editor = watTextEditor.editor
            watTextEditor.editor.settings.isUseSoftWraps = false
            watTextEditor.editor.settings.isLineNumbersShown = false
            watTextEditor.editor.asSafely<EditorEx>()?.isViewer = true
            watTextEditor.editor.caretModel.moveToOffset(fileset.fileOffset)
            watTextEditor.editor.scrollingModel.scrollTo(
                watTextEditor.editor.offsetToLogicalPosition(fileset.fileOffset),
                ScrollType.RELATIVE
            )
            value = UiDataProvider.wrapComponent(watTextEditor.component) { sink ->
                sink[OpenFileDescriptor.NAVIGATE_IN_EDITOR] = watTextEditor.editor
            }
        }
    }

    if (panelState != null) {
        key(panelState) {
            SwingPanelEditor(panelState)
        }
    }
}


@Composable
private fun SwingPanelEditor(editorPanel: JComponent?, vararg additionalComponents: JComponent?) {
    if (editorPanel == null) return
    val currentDensity = LocalDensity.current
    val ideScale = UISettingsUtils.getInstance().currentIdeScale
    val unscaledDensity = if (ideScale != 0f) {
        Density(currentDensity.density / ideScale, currentDensity.fontScale)
    } else {
        currentDensity
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDensity provides unscaledDensity) {
            SwingPanel(
                modifier = Modifier.fillMaxSize(),
                update = {
                    ApplicationManager.getApplication().invokeLater {
                        UIUtil.findComponentOfType(it, EditorComponentImpl::class.java)?.requestFocusInWindow()
                    }
                },
                factory = {
                    val panel = JPanel(BorderLayout())
                    panel.add(editorPanel, BorderLayout.CENTER)
                    additionalComponents.filterNotNull().forEach { panel.add(it, BorderLayout.NORTH) }
                    panel
                }
            )
        }
    }
}

@Composable
private fun ConvertingIndicator(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val colorStart = Color(0xFF00C6FF)
        val colorEnd = Color(0xFF0072FF)

        val infinite = rememberInfiniteTransition(label = "convertingGradient")
        val t by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing)
            ),
            label = "pulse"
        )

        val brush = Brush.linearGradient(
            colors = listOf(
                lerp(colorStart, colorEnd, t),
                lerp(colorEnd, colorStart, t)
            )
        )

        Text(
            message,
            style = TextStyle(brush = brush, fontFamily = FontFamily.Monospace)
        )
    }
}

sealed class WatConversionState {
    object Loading : WatConversionState()
    class ShowEditor(val editorComponent: JComponent, val warningComponent: JComponent? = null) : WatConversionState()
    class ShowLiteEditor(val wat: String, val lineOffsets: Map<Int, String>) : WatConversionState()
    class ShowMultifileEditor(val fileset: WatFileset) : WatConversionState()
    class Error(val error: String) : WatConversionState()
}

const val useMultifileEditor = false

internal class WasmViewModel(
    private val state: HexViewStateHolder,
    private val wasmBinaryProvider: BinaryVirtualFileProvider,
) : ViewModel() {

    internal val conversionState: StateFlow<WatConversionState?> = state.conversionStateFlow

    init {
        convert()
    }

    fun convert() {
        val wasmFile = wasmBinaryProvider.file
        if (state.startConversion()) {
            viewModelScope.launch(Dispatchers.IO + CoroutineName("Wasm to Wat conversion")) {
                val virtualFile = VfsUtil.virtualToIoFile(wasmFile)
                runCatching {
                    val conversionResult =
                        HexanaService.getInstance().convertWasmToWat(
                            virtualFile
                        )
                    if (conversionResult is HexanaService.WatConversionResult.Error) {
                        throw ConversionException(conversionResult)
                    }
                    conversionResult as HexanaService.WatConversionResult.Success
                }.onSuccess { success ->
                    val (fileText, lineOffsets) = handleOffsets(success.wat)
                    val conversionState = if (useMultifileEditor) {
                        val fileset = createWatFileset(wasmFile, fileText) { fileset ->
                            state.setConversionState(WatConversionState.ShowMultifileEditor(fileset))
                        }
                        WatConversionState.ShowMultifileEditor(fileset)
                    } else if (virtualFile.length() > HexanaService.WAT_EDITOR_MAX_FILE_SIZE) {
                        WatConversionState.ShowLiteEditor(fileText, lineOffsets)
                    } else {
                        val name = wasmFile.name.removeSuffix(".wasm") + ".wat"
                        val file = LightWatFile(wasmFile, name, fileText)

                        var notification: JComponent? = null

                        val editorComponent = withContext(Dispatchers.EDT) {
                            val watTextEditor = wasmBinaryProvider.createTextEditor(file)
                            watTextEditor.editor.settings.isUseSoftWraps = false
                            watTextEditor.editor.settings.isLineNumbersShown = false
                            watTextEditor.editor.asSafely<EditorEx>()?.isViewer = true

                            if (HexanaSettings.getInstance().showOffsets == true) {
                                if (lineOffsets.isNotEmpty()) {
                                    val gutterProvider = object : TextAnnotationGutterProvider, EditorGutterAction {
                                        override fun getLineText(line: Int, editor: Editor?): String? =
                                            lineOffsets[line]?.let { "0x$it" }

                                        override fun getToolTip(line: Int, editor: Editor?): String? =
                                            lineOffsets[line]?.let { "Offset: 0x$it" }

                                        override fun getStyle(line: Int, editor: Editor?): EditorFontType =
                                            EditorFontType.PLAIN

                                        override fun getColor(line: Int, editor: Editor?): ColorKey? = null
                                        override fun getBgColor(line: Int, editor: Editor?): AwtColor? = null
                                        override fun gutterClosed() {}
                                        override fun getPopupActions(line: Int, editor: Editor?): List<AnAction>? = null

                                        override fun doAction(line: Int) {
                                            lineOffsets[line]?.let { offsetStr ->
                                                val offset = offsetStr.toLongOrNull(16) ?: return@let
                                                state.setViewMode(HexViewMode.Hex)
                                                state.scrollHexViewTo(offset)
                                                state.setCursorPosition(offset, state.bytesPerRow.value)
                                            }
                                        }

                                        override fun getCursor(line: Int): Cursor? =
                                            if (lineOffsets.containsKey(line)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
                                    }
                                    watTextEditor.editor.gutter.registerTextAnnotation(gutterProvider, gutterProvider)
                                }
                            }

                            if (success.warning != null) {
                                notification = EditorNotificationPanel(
                                    HintUtil.WARNING_COLOR_KEY,
                                    EditorNotificationPanel.Status.Warning
                                )

                                notification.text = success.warning
                            }

                            UiDataProvider.wrapComponent(watTextEditor.component) { sink ->
                                sink[OpenFileDescriptor.NAVIGATE_IN_EDITOR] = watTextEditor.editor
                            }
                        }


                        WatConversionState.ShowEditor(
                            editorComponent,
                            warningComponent = if (success.warning != null) notification else null
                        )
                    }
                    state.setConversionState(conversionState)

                }.onFailure { err ->
                    val errorMessage = when (err) {
                        is ConversionException -> err.conversionError.error
                        else -> err.message ?: "Unknown error"
                    }
                    state.setConversionState(WatConversionState.Error(errorMessage))
                }
            }
        }
    }

    private fun handleOffsets(wat: String): Pair<String, Map<Int, String>> {
        val offsetPattern = Regex("""\(;@([0-9a-fA-F]+)\s*;\)""")
        val lineOffsets = mutableMapOf<Int, String>()
        val document = DocumentImpl(wat)
        for (line in 0 until document.lineCount) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val lineText = document.charsSequence.subSequence(lineStart, lineEnd)
            val match = offsetPattern.find(lineText)
            if (match != null) {
                lineOffsets[line] = match.groupValues[1].uppercase()
            }
        }
        return Pair(wat.replace(offsetPattern, ""), lineOffsets)
    }
}

private class ConversionException(
    val conversionError: HexanaService.WatConversionResult.Error
) : Exception()
