package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.ui.DialogWrapper
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.renderDexClassAccessFlags
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

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
        Box(modifier = Modifier.fillMaxSize().weight(1f).padding(8.dp)) {
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
    var searchQuery by remember(dex) { mutableStateOf(TextFieldValue("")) }
    val filteredRows = remember(dex, searchQuery.text) {
        if (searchQuery.text.isBlank()) rows
        else {
            val q = searchQuery.text.trim().lowercase()
            rows.filter { it.text.lowercase().contains(q) }
        }
    }
    val listState = rememberLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search classes",
        )
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (filteredRows.isEmpty()) {
                    item { Text(if (searchQuery.text.isNotEmpty()) "No classes match the search." else "(no classes)", style = DexRowTextStyle) }
                    return@LazyColumn
                }
                items(filteredRows.size, key = { filteredRows[it].index }) { idx ->
                    DexClassRow(filteredRows[idx])
                }
            }
            VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
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
    data class IndexedString(val index: Int, val rawValue: String, val displayValue: String)

    val allStrings = remember(dex) {
        dex.stringIds.mapIndexed { idx, entry ->
            IndexedString(idx, entry.value, escapeControlChars(entry.value))
        }
    }
    var searchQuery by remember(dex) { mutableStateOf(TextFieldValue("")) }
    val filteredStrings = remember(dex, searchQuery.text) {
        if (searchQuery.text.isBlank()) allStrings
        else {
            val q = searchQuery.text.trim().lowercase()
            allStrings.filter { it.displayValue.lowercase().contains(q) }
        }
    }
    val listState = rememberLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search strings",
        )
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (filteredStrings.isEmpty()) {
                    item { Text(if (searchQuery.text.isNotEmpty()) "No strings match the search." else "(no strings)", style = DexRowTextStyle) }
                    return@LazyColumn
                }
                items(filteredStrings.size, key = { filteredStrings[it].index }) { idx ->
                    val s = filteredStrings[idx]
                    val prefix = "#${s.index.toString().padStart(5, ' ')}  "
                    Text(
                        prefix + s.displayValue,
                        style = DexRowTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .pointerInput(s.rawValue) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        StringValueDialog("String #${s.index}", s.rawValue).show()
                                    },
                                )
                            },
                    )
                }
            }
            VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

private class StringValueDialog(title: String, private val value: String) : DialogWrapper(true) {
    init {
        this.title = title
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val textArea = JTextArea(value).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 12
            columns = 60
        }
        return JPanel(BorderLayout()).apply {
            add(JScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> {
        val copyAction = object : DialogWrapperAction("Copy to Clipboard") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                val selection = StringSelection(value)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            }
        }
        return arrayOf(copyAction, okAction)
    }
}

private fun escapeControlChars(s: String): String = buildString(s.length) {
    for (c in s) {
        when (c) {
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u0000' -> append("\\0")
            else -> if (c.code < 0x20) append("\\x${c.code.toString(16).padStart(2, '0')}") else append(c)
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
