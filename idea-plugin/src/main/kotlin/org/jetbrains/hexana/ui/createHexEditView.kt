package org.jetbrains.hexana.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.launch
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryVirtualFileProvider
import org.jetbrains.hexana.wat.WatFileType
import org.jetbrains.hexana.wat.WatPreviewProvider
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import java.awt.BorderLayout
import java.awt.Cursor
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
                        Box(modifier = Modifier.width(half).background(HexanaTheme.backgroundColor).padding(horizontal = 8.dp)) {
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
                        Box(modifier = Modifier.width(half).background(HexanaTheme.backgroundColor).padding(horizontal = 8.dp)) {
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
                                            for (idx in start until end) {
                                                Text(
                                                    (buf[idx].toInt() and 0xFF).asHexString(),
                                                    style = TextStyle(
                                                        color = HexanaTheme.hexTextColor,
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
                                        Row(
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            val start = rowIndex * bytesPerRow
                                            val end = min(start + bytesPerRow, buf.size)
                                            for (idx in start until end) {
                                                val b = buf[idx].toInt() and 0xFF
                                                val ch = if (b in 32..126) b.toChar().toString() else "."
                                                Text(
                                                    ch,
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
                    TabsPanel(panels)
                }
            }
        }
    }
}

@Composable
private fun TabsPanel(tabs: List<Panel>) {
    if (tabs.isEmpty()) return

    var selectedPanel by remember { mutableStateOf<Panel>(tabs.first()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().background(HexanaTheme.backgroundColor)) {
            tabs.forEach { tab ->
                val selected = tab == selectedPanel
                val fg = if (selected) HexanaTheme.textColor else HexanaTheme.textColor.copy(alpha = 0.8f)
                val underline = if (selected) HexanaTheme.textColor else Color.Transparent

                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .padding(horizontal = 8.dp)
                        .clickable { selectedPanel = tab }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(tab.description, style = TextStyle(color = fg))
                    Spacer(Modifier.height(4.dp))
                    Divider(
                        orientation = org.jetbrains.jewel.ui.Orientation.Horizontal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(underline)
                    )
                }
            }
        }

        Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal)

        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            selectedPanel.onRender()
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

    class Wat(override val wasmBinaryProvider: WasmBinaryVirtualFileProvider) : WasmPanel {
        override val description = "WAT"

        @Composable
        override fun onRender() {
            val coroutineScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default) }

            SwingPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                factory = {
                    val container = JPanel(BorderLayout())

                    coroutineScope.launch {
                        runCatching {
                            val conversionResult = HexanaService.getInstance().convertWasmToWat(VfsUtil.virtualToIoFile(wasmBinaryProvider.file))
                            conversionResult as? HexanaService.WatConversionResult.Success ?: return@launch
                            val watPreviewFile = LightVirtualFile(wasmBinaryProvider.file.name, WatFileType.INSTANCE, conversionResult.wat)
                            val watTextEditor = wasmBinaryProvider.createTextEditor(watPreviewFile)
                            watPreviewFile.putUserData(WatPreviewProvider.NAVIGATE_IN_EDITOR, watTextEditor.editor)
                            watTextEditor.component
                        }.onSuccess { editorPanel ->
                            // Ensure Swing updates happen on EDT
                            javax.swing.SwingUtilities.invokeLater {
                                container.removeAll()
                                container.add(editorPanel, BorderLayout.CENTER)
                                container.revalidate()
                                container.repaint()
                            }
                        }.onFailure {
                            println("Failed to convert WASM to WAT: ${it.message}")
                        }
                    }

                    container
                },
                update = { panel ->
                    // Optional: Update the Swing component when Compose state changes
                }
            )
        }
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