package org.jetbrains.hexana.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
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
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.wasm.sections.*
import org.jetbrains.hexana.wat.LightWatFile
import org.jetbrains.hexana.wat.WatFileset
import org.jetbrains.hexana.wat.WatSyntaxHighlighter
import org.jetbrains.hexana.wat.createWatFileset
import org.jetbrains.hexana.wat.psi.WatLexer
import org.jetbrains.hexana.wat.psi.WatToken
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel


internal sealed interface WasmPanel : Panel {
    val wasmBinaryProvider: WasmBinaryProvider

    class Imports(override val wasmBinaryProvider: WasmBinaryProvider) : WasmPanel {
        override val description = "Imports"

        @Composable
        override fun onRender() {
            val imports = remember(wasmBinaryProvider) { wasmBinaryProvider.imports }

            LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
                items(imports.size) { idx ->
                    val importRecord = imports[idx]
                    Text(
                        importRecord.name,
                        style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }


    class Exports(override val wasmBinaryProvider: WasmBinaryProvider) : WasmPanel {
        override val description = "Exports"

        @Composable
        override fun onRender() {
            val exports = remember(wasmBinaryProvider) {
                wasmBinaryProvider.exports
            }

            LazyColumn {
                items(exports.size) { exportIndex ->
                    val export = exports[exportIndex]
                    val exportKind = when (export) {
                        is WasmFunctionExportRecordEntry -> "function"
                        is WasmGlobalExportRecordEntry -> "global"
                        is WasmMemoryExportRecordEntry -> "memory"
                        is WasmTableExportRecordEntry -> "table"
                        is WasmTagExportRecordEntry -> "tag"
                        is WasmUnsupportedExportRecordEntry -> "unknown"
                    }

                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace)) {
                                append(export.name)
                            }

                            append(" ")

                            withStyle(style = SpanStyle(color = Color.LightGray, fontFamily = FontFamily.Monospace)) {
                                append("(; ${exportKind} ;)")
                            }
                        }
                    )
                }
            }
        }
    }

    class Data(override val wasmBinaryProvider: WasmBinaryProvider, val state: HexViewStateHolder) : WasmPanel {
        override val description = "Data"

        @OptIn(ExperimentalFoundationApi::class)
        @Composable
        override fun onRender() {
            val dataRecords = remember(wasmBinaryProvider) { wasmBinaryProvider.data }

            if (dataRecords.isEmpty()) return

            var sortBySizeAsc: Boolean? by remember { mutableStateOf(null) }
            var selectedOriginalIndex: Int? by remember { mutableStateOf(null) }

            val viewRecords = remember(dataRecords, sortBySizeAsc) {
                val base = dataRecords.withIndex().map { it.index to it.value }
                when (sortBySizeAsc) {
                    true -> base.sortedBy { (_, rec) -> rec.recordLength }
                    false -> base.sortedByDescending { (_, rec) -> rec.recordLength }
                    null -> base
                }
            }

//            LaunchedEffect(viewRecords) {
//                if (selectedOriginalIndex == null && viewRecords.isNotEmpty()) {
//                    val (originalIndex, dataRecord) = viewRecords.first()
//                    selectedOriginalIndex = originalIndex
//                    state.scrollHexViewTo(dataRecord.offset)
//
//                    val startLine = (dataRecord.offset / state.bytesPerRow.value).toInt()
//                    val startLineOffset = (dataRecord.offset % state.bytesPerRow.value).toInt()
//
//                    val endLine = ((dataRecord.offset + dataRecord.recordLength) / state.bytesPerRow.value).toInt()
//                    val endLineOffset = ((dataRecord.offset + dataRecord.recordLength) % state.bytesPerRow.value).toInt() - 1
//
//                    state.setSelection(
//                        startLine, startLineOffset,
//                        endLine, endLineOffset
//                    )
//                }
//            }

            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Box(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Data",
                                style = TextStyle(
                                    color = HexanaTheme.textColor,
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.onClick {
                                    sortBySizeAsc = when (sortBySizeAsc) {
                                        null -> true
                                        true -> false
                                        false -> null
                                    }
                                }
                            )


                            val sortTitle = when (sortBySizeAsc) {
                                true -> "smallest first"
                                false -> "biggest first"
                                null -> "by order"
                            }

                            Link(
                                sortTitle,
                                onClick = {
                                    sortBySizeAsc = when (sortBySizeAsc) {
                                        null -> true
                                        true -> false
                                        false -> null
                                    }
                                }
                            )
                        }
                    }

                    Box(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                        Text(
                            "Size",
                            style = TextStyle(
                                color = HexanaTheme.textColor,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                        )
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(viewRecords.size) { rowIndex ->
                        val (originalIndex, dataRecord) = viewRecords[rowIndex]
                        val name = "(data $#data${originalIndex})"
                        val sizeLabel = "${dataRecord.recordLength}"

                        val selectedColor = if (rowIndex % 2 == 0) HexanaTheme.backgroundColor else lerp(
                            HexanaTheme.backgroundColor,
                            Color.White,
                            0.05f
                        )
                        val isSelected = selectedOriginalIndex == originalIndex
                        val rowBg = if (isSelected) lerp(
                            selectedColor,
                            HexanaTheme.hexCellHoverActiveColor,
                            0.6f
                        ) else selectedColor

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .onClick {
                                    selectedOriginalIndex = originalIndex
                                    state.scrollHexViewTo(dataRecord.offset)

                                    val startLine = (dataRecord.offset / state.bytesPerRow.value).toInt()
                                    val startLineOffset = (dataRecord.offset % state.bytesPerRow.value).toInt()

                                    val endLine =
                                        ((dataRecord.offset + dataRecord.recordLength) / state.bytesPerRow.value).toInt()
                                    val endLineOffset =
                                        ((dataRecord.offset + dataRecord.recordLength) % state.bytesPerRow.value).toInt() - 1

                                    state.setSelection(
                                        startLine, startLineOffset,
                                        endLine, endLineOffset
                                    )
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                name,
                                style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                                lineHeight = TextUnit(1f, TextUnitType.Em),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                sizeLabel,
                                style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                                lineHeight = TextUnit(1f, TextUnitType.Em),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    class Wat(
        override val wasmBinaryProvider: WasmBinaryVirtualFileProvider,
        val state: HexViewStateHolder,
        val wasmViewModel: WasmViewModel = WasmViewModel(state, wasmBinaryProvider)
    ) : WasmPanel {
        override val description = "WAT"

        @Composable
        override fun onRender() {
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
                    WatLiteEditor((wasmViewState as WatConversionState.ShowLiteEditor).wat)
                }
                is WatConversionState.ShowEditor -> {
                    val editor = wasmViewState as WatConversionState.ShowEditor
                    WatEditorContainer(editor.editorComponent, editor.warningComponent)
                }

                is WatConversionState.ShowMultifileEditor -> {
                    WatMultifileEditorContainer(state, wasmViewState as WatConversionState.ShowMultifileEditor, wasmBinaryProvider)
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
internal fun WatLiteEditor(watContent: String) {
    val tokens by remember { derivedStateOf {
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
    }}

    val highlighter = WatSyntaxHighlighter()

    LazyColumn {
        items(tokens.size) { lineIndex ->
            val tokensInLine = tokens[lineIndex]
            Text(
                text = buildAnnotatedString {
                    tokensInLine.forEach { tokenData ->
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

                    append(" ")
                }
            )
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
    wasmBinaryProvider: WasmBinaryVirtualFileProvider,
) {
    val panelState by produceState<JComponent?>(initialValue = null, key1 = state, key2 = hexViewState.currentState.value.fileIndex) {
        withContext(Dispatchers.EDT) {
            val fileset = state.fileset
            val currentFile = fileset.currentFile
            val watTextEditor = wasmBinaryProvider.createTextEditor(currentFile)
            fileset.editor = watTextEditor.editor
            watTextEditor.editor.settings.isUseSoftWraps = false
            watTextEditor.editor.asSafely<EditorEx>()?.isViewer = true
            watTextEditor.editor.caretModel.moveToOffset(fileset.fileOffset)
            watTextEditor.editor.scrollingModel.scrollTo(watTextEditor.editor.offsetToLogicalPosition(fileset.fileOffset), ScrollType.RELATIVE)
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
    class ShowLiteEditor(val wat: String) : WatConversionState()
    class ShowMultifileEditor(val fileset: WatFileset) : WatConversionState()
    class Error(val error: String) : WatConversionState()
}

const val useMultifileEditor = false

internal class WasmViewModel(
    private val state: HexViewStateHolder,
    private val wasmBinaryProvider: WasmBinaryVirtualFileProvider,
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
                    val conversionState = if (useMultifileEditor) {
                        val fileset = createWatFileset(wasmFile, success.wat) { fileset ->
                            state.setConversionState(WatConversionState.ShowMultifileEditor(fileset))
                        }
                        WatConversionState.ShowMultifileEditor(fileset)
                    } else if (virtualFile.length() > HexanaService.WAT_EDITOR_MAX_FILE_SIZE) {
                        WatConversionState.ShowLiteEditor(success.wat)
                    } else {
                        val name = wasmFile.name.removeSuffix(".wasm") + ".wat"
                        val file = LightWatFile(wasmFile, name, success.wat)

                        var notification: JComponent? = null

                        val editorComponent = withContext(Dispatchers.EDT) {
                            val watTextEditor = wasmBinaryProvider.createTextEditor(file)
                            watTextEditor.editor.settings.isUseSoftWraps = false
                            watTextEditor.editor.asSafely<EditorEx>()?.isViewer = true

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


                        WatConversionState.ShowEditor(editorComponent, warningComponent = if (success.warning != null) notification else null)
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
}

private class ConversionException(
    val conversionError : HexanaService.WatConversionResult.Error
) : Exception()
