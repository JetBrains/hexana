package org.jetbrains.hexana.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.notebook.editor.BackedVirtualFile
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.asSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.wat.WatFileType
import org.jetbrains.hexana.wat.WatNavigation
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import java.awt.BorderLayout
import java.awt.Cursor
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.min

@Composable
internal fun createHexEditView(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val stateHolder = remember { state.currentState }

    val buf = binaryProvider.bytes

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        val totalHeightPx = with(density) { maxHeight.toPx() }
        val maxW = maxWidth


        Column(modifier = Modifier.fillMaxSize()) {
            if (stateHolder.value.topFraction > 0) {
                Box(modifier = Modifier.fillMaxWidth().weight(stateHolder.value.topFraction)) {
                    val separator = 1.dp
                    val half = (maxW - separator) / 2
                    val bytesPerRow = 16
                    val spacing = 8.dp
                    val rowCount = (buf.size + bytesPerRow - 1) / bytesPerRow
                    val offsetWidth = 96.dp

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .width(offsetWidth)
                                    .background(HexanaTheme.backgroundColor)
                                    .padding(horizontal = 8.dp)
                            ) {}
                            Box(
                                modifier = Modifier.width(half).background(HexanaTheme.backgroundColor)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(spacing)
                                ) {
                                    for (i in 0 until bytesPerRow) {
                                        Text(
                                            i.asHexString(),
                                            style = TextStyle(
                                                color = HexanaTheme.textColor,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                }
                            }
                            Box(modifier = Modifier.width(separator).background(Color.Gray))
                            Box(
                                modifier = Modifier.width(half).background(HexanaTheme.backgroundColor)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    "ASCII",
                                    style = TextStyle(
                                        color = HexanaTheme.textColor,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                        Box {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(count = rowCount) { rowIndex ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Box(
                                            modifier = Modifier
                                                .width(offsetWidth)
                                                .background(HexanaTheme.backgroundColor)
                                                .padding(horizontal = 8.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Row {
                                                Text(
                                                    (rowIndex * bytesPerRow).asHexString(padStart = 8),
                                                    style = TextStyle(fontFamily = FontFamily.Monospace)
                                                )
                                            }
                                        }

                                        Box(
                                            modifier = Modifier.width(half).background(HexanaTheme.backgroundColor)
                                                .padding(horizontal = 8.dp)
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(spacing),
                                            ) {
                                                val start = rowIndex * bytesPerRow
                                                val end = min(start + bytesPerRow, buf.size)

                                                val rowText = buf.slice(start until end)
                                                    .joinToString(" ") { (it.toInt() and 0xFF).asHexString() }
                                                Text(
                                                    rowText,
                                                    style = TextStyle(
                                                        color = HexanaTheme.hexTextColor,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                )
                                            }
                                        }
                                        Box(modifier = Modifier.width(separator).background(Color.Gray))
                                        Box(
                                            modifier = Modifier.width(half).background(HexanaTheme.backgroundColor)
                                                .padding(horizontal = 8.dp)
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.Start
                                            ) {
                                                val start = rowIndex * bytesPerRow
                                                val end = min(start + bytesPerRow, buf.size)
                                                val str = buf.slice(start until end).joinToString("") {
                                                    val b = it.toInt() and 0xFF
                                                    if (b in 32..126) b.toChar().toString() else "."
                                                }
                                                Text(
                                                    str,
                                                    style = TextStyle(
                                                        color = HexanaTheme.textColor,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Show scrollbar only when content overflows the viewport
                            val showScrollbar by remember(listState) {
                                derivedStateOf {
                                    val layout = listState.layoutInfo
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
                                    adapter = rememberScrollbarAdapter(scrollState = listState)
                                )
                            }
                        }
                    }
                }
            }

            val panels = remember { mutableStateListOf(*binaryProvider.resolvePanels()) }

            if (panels.isNotEmpty()) {
                HorizontalDraggableDivider(
                    onDrag = { deltaY ->
                        if (totalHeightPx > 0) {
                            val deltaRatio = deltaY / totalHeightPx
                            val topFraction = (stateHolder.value.topFraction + deltaRatio).coerceIn(0.1f, 0.9f)
                            state.setTopFraction(topFraction)
                        }
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f - stateHolder.value.topFraction)
                        .background(HexanaTheme.backgroundColor),
                    contentAlignment = Alignment.TopStart
                ) {
                    TabsPanel(panels) { expanded ->
                        if (expanded) {
                            state.setTopFraction(0f)
                        } else {
                            state.setTopFraction(0.5f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabsPanel(panels: List<Panel>, onExpand: (Boolean) -> Unit) {
    if (panels.isEmpty()) return

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    val tabs by remember {
        derivedStateOf {
            panels.mapIndexed { index, panel ->
                TabData.Default(
                    selected = index == selectedTabIndex,
                    content = { Text(text = panel.description) },
                    closable = false,
                    onClick = { selectedTabIndex = index }
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HexanaTheme.backgroundColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Center the IconButton both vertically and horizontally within its own parent container
            Box(
                modifier = Modifier
                    .width(40.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    expanded = !expanded
                    onExpand(expanded)
                }) {
                    Icon(
                        if (expanded) AllIconsKeys.General.CollapseComponent else AllIconsKeys.General.ExpandComponent,
                        contentDescription = null,
                        iconClass = AllIcons::class.java
                    )
                }
            }
            TabStrip(
                tabs = tabs,
                style = JewelTheme.defaultTabStyle,
            )
        }

        Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal)

        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            panels.get(selectedTabIndex).onRender()
        }
    }
}

@Composable
private fun HorizontalDraggableDivider(onDrag: (deltaY: Float) -> Unit) {
    val dragHitAreaThickness = 8.dp

    Divider(
        orientation = org.jetbrains.jewel.ui.Orientation.Horizontal,
        modifier = Modifier
            .fillMaxWidth().height(dragHitAreaThickness / 2).background(Color.Gray)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dragHitAreaThickness)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .background(Color.Transparent)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    onDrag(delta)
                },
                startDragImmediately = true
            )

    )
}

private fun Int.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()


private interface Panel {
    val description: String

    @Composable
    fun onRender()
}

private sealed interface WasmPanel : Panel {
    val wasmBinaryProvider: WasmBinaryProvider

    class Imports(override val wasmBinaryProvider: WasmBinaryProvider) : WasmPanel {
        override val description = "Imports"

        @Composable
        override fun onRender() {
            val imports = remember { wasmBinaryProvider.getImports() }

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
            Text("[EXPORTS]", style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace))
        }
    }

    class Data(override val wasmBinaryProvider: WasmBinaryProvider) : WasmPanel {
        override val description = "Data"

        @Composable
        override fun onRender() {
            val dataRecords = remember { wasmBinaryProvider.getData() }

            LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
                items(dataRecords.size) { dataRecordIndex ->
                    val dataRecord = dataRecords[dataRecordIndex]
                    Text(
                        "Data record $dataRecordIndex ${dataRecord::class.simpleName}",
                        style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace)
                    )
                }
            }

        }
    }

    class LightWatFile(
        val originWasmFile: VirtualFile,
        val watFileName: String,
        val text: String,
    ): LightVirtualFile(watFileName, WatFileType.INSTANCE, text), BackedVirtualFile {
        override fun getOriginFile(): VirtualFile {
            return originWasmFile
        }
    }

    class Wat(override val wasmBinaryProvider: WasmBinaryVirtualFileProvider) : WasmPanel {
        override val description = "WAT"

        @Composable
        override fun onRender() {
            var loading by remember { mutableStateOf(true) }
            var editorPanel by remember { mutableStateOf<JComponent?>(null) }

            // Perform conversion once per file; create Swing components on EDT
            androidx.compose.runtime.LaunchedEffect(wasmBinaryProvider.file) {
                loading = true
                runCatching {
                    val conversionResult = withContext(Dispatchers.IO) {
                        HexanaService.getInstance().convertWasmToWat(
                            VfsUtil.virtualToIoFile(wasmBinaryProvider.file)
                        )
                    }
                    conversionResult as? HexanaService.WatConversionResult.Success
                        ?: error("WAT conversion failed")
                }.onSuccess { success ->
                    withContext(Dispatchers.EDT) {
                        val watFileName = wasmBinaryProvider.file.name.removeSuffix(".wasm") + ".wat"
                        val watPreviewFile = LightWatFile(wasmBinaryProvider.file, watFileName, success.wat)
                        val watTextEditor = wasmBinaryProvider.createTextEditor(watPreviewFile)
                        watTextEditor.editor.asSafely<EditorEx>()?.isViewer = true
                        watPreviewFile.putUserData(WatNavigation.WAT_EDITOR, watTextEditor.editor)
                        editorPanel = watTextEditor.component
                    }
                    loading = false
                }.onFailure {
                    println("Failed to convert WASM to WAT: ${it.message}")
                    loading = false
                    editorPanel = null
                }
            }

            if (loading) {
                ConvertingIndicator()
            } else {
                Box(Modifier.fillMaxSize()) {
                    SwingPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        factory = {
                            JPanel(BorderLayout())
                        },
                        update = { panel ->
                            if (loading) return@SwingPanel
                            panel.removeAll()
                            val child = editorPanel
                            if (child != null) {
                                panel.add(child, BorderLayout.CENTER)
                            }
                            panel.revalidate()
                            panel.repaint()
                        }
                    )
                }
            }

        }
    }
}

@Composable
private fun ConvertingIndicator() {
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
            "0000 0 1001 01 10 01 converting",
            style = TextStyle(brush = brush, fontFamily = FontFamily.Monospace)
        )
    }

}

private fun BinaryProvider.resolvePanels(): Array<Panel> {
    return when (this) {
        is GenericBinaryProvider -> emptyArray()
        is WasmBinaryVirtualFileProvider -> {
            if (wasmModule == null) return emptyArray() else arrayOf(
                WasmPanel.Imports(this),
                WasmPanel.Exports(this),
                WasmPanel.Data(this),
                WasmPanel.Wat(this)
            )
        }

        else -> emptyArray()
    }
}