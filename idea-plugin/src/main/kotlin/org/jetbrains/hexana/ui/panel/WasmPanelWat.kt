package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.EditorGutterAction
import com.intellij.openapi.editor.TextAnnotationGutterProvider
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.EditorColorsManager
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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewMode
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.HexanaSettings
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.wat.LightWatFile
import org.jetbrains.hexana.wat.WatFileset
import org.jetbrains.hexana.wat.WatSyntaxHighlighter
import org.jetbrains.hexana.wat.createWatFileset
import org.jetbrains.hexana.wat.psi.WatLexer
import org.jetbrains.hexana.wat.psi.WatToken
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.awt.Cursor
import javax.swing.JComponent
import javax.swing.JPanel


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

    val highlighter = WatSyntaxHighlighter()

    val offsetPattern = remember { Regex("""\(;@([0-9a-fA-F]+)\s*;\)""") }
    val showOffsets = remember { HexanaSettings.getInstance().showOffsets == true }

    SelectionContainer {
        LazyColumn {
            items(tokens.size) { lineIndex ->
                val tokensInLine = tokens[lineIndex]

                Row(verticalAlignment = Alignment.Top) {
                    if (showOffsets) {
                        DisableSelection {
                            val offsetText = lineOffsets[lineIndex]?.let { "0x$it" } ?: ""
                            Text(
                                text = offsetText,
                                modifier = Modifier.width(60.dp).padding(end = 8.dp)
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
                    Text(
                        text = buildAnnotatedString {
                            tokensInLine.forEach { tokenData ->
                                if (offsetPattern.matches(tokenData.text)) return@forEach
                                val colors = highlighter.getTokenHighlights(tokenData.tokenType)
                                val colorResolved = colors.getOrNull(0)
                                if (colorResolved != null) {
                                    withStyle(style = SpanStyle(color = colorResolved.toComposeColor())) {
                                        append(tokenData.text)
                                    }
                                } else {
                                    append(tokenData.text)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
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
    Box(Modifier.fillMaxSize()) {
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
