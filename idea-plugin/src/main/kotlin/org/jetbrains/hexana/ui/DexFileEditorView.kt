package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.dex.DexClassData
import org.jetbrains.hexana.dex.DexClassDef
import org.jetbrains.hexana.dex.DexEncodedField
import org.jetbrains.hexana.dex.DexEncodedMethod
import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.dex.renderDexClassAccessFlags
import org.jetbrains.hexana.dex.renderDexFieldAccessFlags
import org.jetbrains.hexana.dex.renderDexMethodAccessFlags
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.theme.defaultTabStyle

internal fun dexFilePanels(
    dexFile: DexFile,
    binaryProvider: BinaryProvider,
): List<org.jetbrains.hexana.ui.panel.Panel> = listOf(
    org.jetbrains.hexana.ui.panel.Panel("Header") { DexHeaderTab(dexFile) },
    org.jetbrains.hexana.ui.panel.Panel("Classes") { DexClassesPanel(dexFile, binaryProvider) },
)

/**
 * Wrapper that manages the class-list → class-detail drill-down inside a
 * single "Classes" panel, preserving the navigation state that was previously
 * handled by [DexFileTabs].
 */
@Composable
private fun DexClassesPanel(dexFile: DexFile, binaryProvider: BinaryProvider) {
    var selectedClassDefIdx by rememberSaveable { mutableStateOf<Int?>(null) }
    val classIdx = selectedClassDefIdx
    if (classIdx != null) {
        DexClassDetailView(
            dex = dexFile,
            classDefIndex = classIdx,
            buffer = binaryProvider.bytes,
            onBack = { selectedClassDefIdx = null },
            onNavigateToClass = { idx -> selectedClassDefIdx = idx },
        )
    } else {
        DexClassesTab(dexFile) { idx ->
            selectedClassDefIdx = idx
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
private fun DexClassesTab(dex: DexFile, onClassClick: (Int) -> Unit) {
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
                    DexClassRow(filteredRows[idx], onClick = { onClassClick(filteredRows[idx].classDefIndex) })
                }
            }
            VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun DexClassRow(row: DexClassRowData, onClick: () -> Unit) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (row.index % 2 == 0) Color.Transparent else bg)
            .clickable(onClick = onClick)
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


// -----------------------------------------------------------------------------
// Class detail view — shown when a class name is clicked
// -----------------------------------------------------------------------------

@Composable
private fun DexClassDetailView(
    dex: DexFile,
    classDefIndex: Int,
    buffer: CommonByteBuffer,
    onBack: () -> Unit,
    onNavigateToClass: (Int) -> Unit,
) {
    val classDef = dex.classDefs[classDefIndex]
    val className = dex.typeName(classDef.classIdx) ?: "<#${classDef.classIdx}>"
    val classData = remember(dex, classDefIndex) {
        try {
            DexFileParser.parseClassData(buffer, classDef)
        } catch (_: Exception) {
            null
        }
    }
    val interfaces = remember(dex, classDefIndex) {
        try {
            DexFileParser.parseTypeList(buffer, classDef.interfacesOff)
        } catch (_: Exception) {
            emptyList()
        }
    }

    var detailTab by rememberSaveable(classDefIndex) { mutableStateOf(0) }
    val detailLabels = listOf("Info", "Fields", "Methods")
    val detailTabs by remember(detailTab, classDefIndex) {
        derivedStateOf {
            detailLabels.mapIndexed { index, label ->
                TabData.Default(
                    selected = index == detailTab,
                    content = { Text(label) },
                    closable = false,
                    onClick = { detailTab = index },
                )
            }
        }
    }

    val linkColor = if (JewelTheme.isDark) Color(0xFF589DF6) else Color(0xFF2470B3)

    Column(modifier = Modifier.fillMaxSize()) {
        // Back link
        Text(
            "\u2190 Back to classes",
            style = DexRowTextStyle.copy(color = linkColor),
            modifier = Modifier.clickable(onClick = onBack).padding(bottom = 4.dp),
        )
        TabStrip(tabs = detailTabs, style = JewelTheme.defaultTabStyle)
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxSize().weight(1f).padding(top = 4.dp)) {
            when (detailTab) {
                0 -> DexClassInfoTab(dex, classDef, className, interfaces, classData, onNavigateToClass)
                1 -> DexClassFieldsTab(dex, classData)
                else -> DexClassMethodsTab(dex, classData)
            }
        }
    }
}

@Composable
private fun DexClassInfoTab(
    dex: DexFile,
    classDef: DexClassDef,
    className: String,
    interfaces: List<Int>,
    classData: DexClassData?,
    onNavigateToClass: (Int) -> Unit,
) {
    val linkColor = if (JewelTheme.isDark) Color(0xFF589DF6) else Color(0xFF2470B3)
    val flags = renderDexClassAccessFlags(classDef.accessFlags)
    val superName = if (classDef.superclassIdx == -1 || classDef.superclassIdx == 0xFFFFFFFF.toInt()) {
        null
    } else {
        dex.typeName(classDef.superclassIdx)
    }
    val sourceFile = if (classDef.sourceFileIdx == -1 || classDef.sourceFileIdx == 0xFFFFFFFF.toInt()) {
        null
    } else {
        dex.string(classDef.sourceFileIdx)
    }

    // Build a map from type descriptor to classDef index for navigation.
    val typeToClassDefIdx = remember(dex) {
        dex.classDefs.withIndex().associate { (idx, cd) ->
            (dex.typeName(cd.classIdx) ?: "") to idx
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = className, style = TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp))
        if (flags.isNotEmpty()) Text("access: $flags", style = DexMonoStyle)
        if (superName != null) {
            val superIdx = typeToClassDefIdx[superName]
            if (superIdx != null) {
                Text(
                    "extends: $superName",
                    style = DexMonoStyle.copy(color = linkColor),
                    modifier = Modifier.clickable { onNavigateToClass(superIdx) },
                )
            } else {
                Text("extends: $superName", style = DexMonoStyle)
            }
        }
        if (interfaces.isNotEmpty()) {
            Text("implements:", style = DexMonoStyle)
            for (typeIdx in interfaces) {
                val ifaceName = dex.typeName(typeIdx) ?: "<#$typeIdx>"
                val ifaceClassIdx = typeToClassDefIdx[ifaceName]
                if (ifaceClassIdx != null) {
                    Text(
                        "  $ifaceName",
                        style = DexMonoStyle.copy(color = linkColor),
                        modifier = Modifier.clickable { onNavigateToClass(ifaceClassIdx) },
                    )
                } else {
                    Text("  $ifaceName", style = DexMonoStyle)
                }
            }
        }
        if (sourceFile != null) Text("source file: $sourceFile", style = DexMonoStyle)
        if (classData != null) {
            Text("static fields: ${classData.staticFields.size}", style = DexMonoStyle)
            Text("instance fields: ${classData.instanceFields.size}", style = DexMonoStyle)
            Text("direct methods: ${classData.directMethods.size}", style = DexMonoStyle)
            Text("virtual methods: ${classData.virtualMethods.size}", style = DexMonoStyle)
        } else {
            Text("(no class data)", style = DexMonoStyle)
        }
    }
}

@Composable
private fun DexClassFieldsTab(dex: DexFile, classData: DexClassData?) {
    if (classData == null) {
        Text("(no class data)", style = DexRowTextStyle)
        return
    }
    data class FieldRow(val index: Int, val label: String, val text: String)

    val rows = remember(dex, classData) {
        val result = mutableListOf<FieldRow>()
        var idx = 0
        fun addFields(fields: List<DexEncodedField>, category: String) {
            if (fields.isEmpty()) return
            result += FieldRow(idx++, category, "--- $category ---")
            for (f in fields) {
                val fieldId = dex.fieldIds.getOrNull(f.fieldIdx)
                val name = fieldId?.let { dex.string(it.nameIdx) } ?: "<#${f.fieldIdx}>"
                val type = fieldId?.let { dex.typeName(it.typeIdx) } ?: "?"
                val flags = renderDexFieldAccessFlags(f.accessFlags)
                val text = buildString {
                    if (flags.isNotEmpty()) { append(flags); append(' ') }
                    append(type); append(' '); append(name)
                }
                result += FieldRow(idx++, name, text)
            }
        }
        addFields(classData.staticFields, "Static fields")
        addFields(classData.instanceFields, "Instance fields")
        result
    }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (rows.isEmpty()) {
                item { Text("(no fields)", style = DexRowTextStyle) }
                return@LazyColumn
            }
            items(rows.size, key = { rows[it].index }) { idx ->
                val row = rows[idx]
                val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
                Text(
                    row.text,
                    style = if (row.text.startsWith("---")) DexRowTextStyle.copy(fontWeight = FontWeight.Bold) else DexRowTextStyle,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (idx % 2 == 0) Color.Transparent else bg)
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun DexClassMethodsTab(dex: DexFile, classData: DexClassData?) {
    if (classData == null) {
        Text("(no class data)", style = DexRowTextStyle)
        return
    }
    data class MethodRow(val index: Int, val label: String, val text: String)

    val rows = remember(dex, classData) {
        val result = mutableListOf<MethodRow>()
        var idx = 0
        fun addMethods(methods: List<DexEncodedMethod>, category: String) {
            if (methods.isEmpty()) return
            result += MethodRow(idx++, category, "--- $category ---")
            for (m in methods) {
                val methodId = dex.methodIds.getOrNull(m.methodIdx)
                val name = methodId?.let { dex.string(it.nameIdx) } ?: "<#${m.methodIdx}>"
                val proto = methodId?.let { dex.protoIds.getOrNull(it.protoIdx) }
                val returnType = proto?.let { dex.typeName(it.returnTypeIdx) } ?: "?"
                val flags = renderDexMethodAccessFlags(m.accessFlags)
                val text = buildString {
                    if (flags.isNotEmpty()) { append(flags); append(' ') }
                    append(returnType); append(' '); append(name); append("()")
                }
                result += MethodRow(idx++, name, text)
            }
        }
        addMethods(classData.directMethods, "Direct methods")
        addMethods(classData.virtualMethods, "Virtual methods")
        result
    }

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (rows.isEmpty()) {
                item { Text("(no methods)", style = DexRowTextStyle) }
                return@LazyColumn
            }
            items(rows.size, key = { rows[it].index }) { idx ->
                val row = rows[idx]
                val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
                Text(
                    row.text,
                    style = if (row.text.startsWith("---")) DexRowTextStyle.copy(fontWeight = FontWeight.Bold) else DexRowTextStyle,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (idx % 2 == 0) Color.Transparent else bg)
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

private data class DexClassRowData(
    val index: Int,
    val classDefIndex: Int,
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
        DexClassRowData(index = idx, classDefIndex = idx, text = text)
    }

private val DexMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
)

private val DexRowTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
)
