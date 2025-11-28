package org.jetbrains.hexana.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.intellij.icons.AllIcons
import com.intellij.notebook.editor.BackedVirtualFile
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.asSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmUnsupportedExportRecordEntry
import org.jetbrains.hexana.wat.WatFileType
import org.jetbrains.hexana.wat.WatNavigation
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.min


internal sealed interface WasmPanel : Panel {
    val wasmBinaryProvider: WasmBinaryProvider

    class Imports(override val wasmBinaryProvider: WasmBinaryProvider) : WasmPanel {
        override val description = "Imports"

        @Composable
        override fun onRender() {
            val imports = remember(wasmBinaryProvider) { wasmBinaryProvider.getImports() }

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
                wasmBinaryProvider.getExports()
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

    class Data(override val wasmBinaryProvider: WasmBinaryProvider) : WasmPanel {
        override val description = "Data"

        @OptIn(ExperimentalFoundationApi::class)
        @Composable
        override fun onRender() {
            val dataRecords = remember(wasmBinaryProvider) { wasmBinaryProvider.getData() }
            var selectedTabIndex by remember { mutableIntStateOf(0) }
            if (dataRecords.isEmpty()) return

            Row {
                Column(modifier = Modifier.weight(0.3f).padding(4.dp)) {
                    LazyColumn {
                        items(dataRecords.size) { dataRecordIndex ->
                            val dataRecord = dataRecords[dataRecordIndex]
                            val data = dataRecord.data

                            val str = data.asASCII().substring(0, min(data.size, 16))

                            Text(
                                str,
                                style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                                lineHeight = TextUnit(1f, TextUnitType.Em), modifier = Modifier.onClick {
                                    selectedTabIndex = dataRecordIndex
                                }.fillMaxWidth().padding(4.dp).background(
                                    if (dataRecordIndex % 2 == 0) Color(135, 135, 135, 255) else Color.Transparent                               )
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).padding(4.dp)) {
                    HexViewer(dataRecords[selectedTabIndex].data, showOffsets = false)
                }
            }
        }
    }

    class LightWatFile(
        val originWasmFile: VirtualFile,
        watFileName: String,
        text: String,
    ): LightVirtualFile(watFileName, WatFileType.INSTANCE, text), BackedVirtualFile {
        override fun getOriginFile(): VirtualFile {
            return originWasmFile
        }
    }

    class Wat(
        override val wasmBinaryProvider: WasmBinaryVirtualFileProvider,
        val state: HexViewStateHolder,
        val wasmViewModel: WasmViewModel = WasmViewModel(wasmBinaryProvider)
    ) : WasmPanel {
        override val description = "WAT"

        @Composable
        override fun onRender() {
            val wasmViewState by wasmViewModel.conversionState.collectAsState()

            when (wasmViewState) {
                is WatConversionState.Loading -> ConvertingIndicator(message = "0000 0 1001 01 10 01 converting ${wasmBinaryProvider.file.name}")

                is WatConversionState.ShowLiteEditor -> {
                    Text("HERE TO BE LITE EDITOR")
                }
                is WatConversionState.ShowEditor -> {
                    WatEditorContainer((wasmViewState as WatConversionState.ShowEditor).wat, wasmBinaryProvider)
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

@Composable
internal fun WatEditorContainer(
    watContent: String,
    wasmBinaryProvider: WasmBinaryVirtualFileProvider,
) {
    val panelState by produceState<JComponent?>(initialValue = null, key1 = watContent) {
        withContext(Dispatchers.EDT) {
            val watFileName = wasmBinaryProvider.file.name.removeSuffix(".wasm") + ".wat"

            val watPreviewFile = WasmPanel.LightWatFile(wasmBinaryProvider.file, watFileName, watContent)

            val watTextEditor = wasmBinaryProvider.createTextEditor(watPreviewFile)
            watTextEditor.editor.settings.isUseSoftWraps = false
            watTextEditor.editor.asSafely<EditorEx>()?.isViewer = true
            watPreviewFile.putUserData(WatNavigation.WAT_EDITOR, watTextEditor.editor)
            val panel = object: JPanel(BorderLayout()), UiDataProvider {
                override fun uiDataSnapshot(sink: DataSink) {
                    sink[OpenFileDescriptor.NAVIGATE_IN_EDITOR] = watTextEditor.editor
                }
            }

            panel.add(watTextEditor.component)
            value = panel
        }
    }

    if (panelState != null) {
        SwingPanelEditor( panelState)
    }
}


@Composable
private fun SwingPanelEditor(editorPanel: JComponent?) {
    if (editorPanel == null) return
    Box(Modifier.fillMaxSize()) {
        SwingPanel(
            modifier = Modifier.fillMaxSize(),
            factory = {
                editorPanel
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
    class ShowEditor(val wat: String) : WatConversionState()
    class ShowLiteEditor(val wat: String) : WatConversionState()
    class Error(val error: String) : WatConversionState()
}

internal class WasmViewModel(
    private val wasmBinaryProvider: WasmBinaryVirtualFileProvider,
) : ViewModel() {
    private val _conversionState = MutableStateFlow<WatConversionState>(WatConversionState.Loading)

    internal val conversionState = _conversionState.asStateFlow()

    init {
        convert()
    }

    fun convert() {
        viewModelScope.launch(Dispatchers.IO + CoroutineName("Wasm to Wat conversion")) {
            runCatching {
                val conversionResult =
                    HexanaService.getInstance().convertWasmToWat(
                        VfsUtil.virtualToIoFile(wasmBinaryProvider.file)
                    )
                if (conversionResult is HexanaService.WatConversionResult.Error) {
                    throw ConversionException(conversionResult)
                }
                conversionResult as HexanaService.WatConversionResult.Success
            }.onSuccess { success ->
                _conversionState.value = WatConversionState.ShowEditor(success.wat)
            }.onFailure { err ->
                val errorMessage = when (err) {
                    is ConversionException -> err.conversionError.error
                    else -> err.message ?: "Unknown error"
                }
                _conversionState.value = WatConversionState.Error(errorMessage)
            }
        }
    }
}

private class ConversionException(
    val conversionError : HexanaService.WatConversionResult.Error
) : Exception()
