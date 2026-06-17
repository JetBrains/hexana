package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.renderDexClassAccessFlags
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Editor view for a parsed Android `.dex` file. Vertically split: a [HexViewer]
 * over the raw DEX bytes on top, three structured tabs underneath:
 *  1. Header — DEX version, sizes, offsets.
 *  2. Classes — list of class definitions with access flags and superclass.
 *  3. Strings — all string entries from the string table.
 */
@Composable
internal fun DexFileEditorView(
    dexFile: DexFile,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
) {
    VerticalSplitPanel(
        state = state,
        northPanel = {
            Box(modifier = Modifier.fillMaxSize()) {
                HexViewer(
                    binaryProvider = binaryProvider,
                    state = state,
                    focusRequester = hexFocusRequester,
                )
            }
        },
        southPanel = {
            DexFileTabs(dexFile)
        },
    )
}

@Composable
private fun DexFileTabs(dexFile: DexFile) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    val labels = listOf("Header", "Classes", "Strings")
    val tabs by remember(selectedTab) {
        derivedStateOf {
            labels.mapIndexed { index, label ->
                TabData.Default(
                    selected = index == selectedTab,
                    content = { Text(label) },
                    closable = false,
                    onClick = { selectedTab = index },
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
            when (selectedTab) {
                0 -> DexHeaderTab(dexFile)
                1 -> DexClassesTab(dexFile)
                else -> DexStringsTab(dexFile)
            }
        }
    }
}

@Composable
private fun DexHeaderTab(dex: DexFile) {
    val h = dex.header
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Android DEX file",
            style = TextStyle(fontWeight = FontWeight.Bold),
        )
        Text("version: ${h.version}")
        Text("file size: ${h.fileSize} bytes")
        Text("header size: ${h.headerSize} bytes")
        Text(
            "checksum: 0x${h.checksum.toUInt().toString(16).padStart(8, '0')}",
            style = DexMonoStyle,
        )
        Text(
            "signature: ${h.signature.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}",
            style = DexMonoStyle,
        )
        Text("strings: ${h.stringIdsSize}")
        Text("types: ${h.typeIdsSize}")
        Text("prototypes: ${h.protoIdsSize}")
        Text("fields: ${h.fieldIdsSize}")
        Text("methods: ${h.methodIdsSize}")
        Text("classes: ${h.classDefsSize}")
    }
}

@Composable
private fun DexClassesTab(dex: DexFile) {
    val rows = remember(dex) { buildClassRows(dex) }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            item { Text("(no classes)", style = DexRowTextStyle) }
            return@LazyColumn
        }
        items(rows.size, key = { it }) { idx ->
            DexClassRow(rows[idx])
        }
    }
}

@Composable
private fun DexClassRow(row: DexClassRowData) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (row.index % 2 == 0) Color.Transparent else bg)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.index.toString().padStart(4, ' '),
            style = DexRowTextStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(row.text, style = DexRowTextStyle.copy(color = HexanaTheme.textColor))
    }
}

@Composable
private fun DexStringsTab(dex: DexFile) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (dex.stringIds.isEmpty()) {
            item { Text("(no strings)", style = DexRowTextStyle) }
            return@LazyColumn
        }
        items(dex.stringIds.size, key = { it }) { idx ->
            val value = dex.stringIds[idx].value
            Text(
                "#${idx.toString().padStart(5, ' ')}  $value",
                style = DexRowTextStyle,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

private data class DexClassRowData(
    val index: Int,
    val text: String,
)

private fun buildClassRows(dex: DexFile): List<DexClassRowData> =
    dex.classDefs.mapIndexed { idx, classDef ->
        val className = dex.typeName(classDef.classIdx) ?: "<#${classDef.classIdx}>"
        val flags = renderDexClassAccessFlags(classDef.accessFlags)
        val superName = if (classDef.superclassIdx == -1 || classDef.superclassIdx == 0xFFFFFFFF.toInt()) {
            null
        } else {
            dex.typeName(classDef.superclassIdx)
        }
        val text = buildString {
            if (flags.isNotEmpty()) { append(flags); append(' ') }
            append(className)
            if (superName != null) { append(" extends "); append(superName) }
        }
        DexClassRowData(index = idx, text = text)
    }

private val DexMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
)

private val DexRowTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
)
