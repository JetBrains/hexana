package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.dex.*
import kotlinx.coroutines.delay
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider

private const val MIN_DEX_PANEL_HEIGHT = 80.0
private const val DEFAULT_DEX_PANEL_HEIGHT = 250.0

/**
 * Editor content for a parsed Android `.dex` file. Shows a hex viewer on top
 * and three structured tabs underneath: Header, Classes, Strings —
 * mirroring the IntelliJ plugin's `DexFileEditorView`.
 */
@Composable
internal fun DexFileEditorContent(
    fileName: String,
    dexFile: DexFile,
    buffer: CommonByteBuffer,
) {
    val selection = remember { HexSelectionState() }
    var panelHeight by remember { mutableStateOf(DEFAULT_DEX_PANEL_HEIGHT) }
    var activeTab by remember { mutableStateOf("Header") }
    var selectedClassDefIdx by remember { mutableStateOf<Int?>(null) }

    // When navigating into a class detail, force the Classes tab
    val effectiveTab = if (selectedClassDefIdx != null && activeTab != "Classes") {
        activeTab = "Classes"
        "Classes"
    } else {
        activeTab
    }

    Div({
        classes("hexana-editor")
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            width(100.percent)
            height(100.percent)
            property("overflow", "hidden")
        }
    }) {
        EditorToolbar(
            fileName = fileName,
            size = buffer.size,
            provider = GenericBinaryProvider(buffer),
            onSizeClick = null,
        )
        HexViewerPanel(bytes = buffer, selection = selection)
        SelectionStatusBar(selection = selection, totalBytes = buffer.size)
        ResizeDivider { deltaY ->
            panelHeight = (panelHeight - deltaY).coerceAtLeast(MIN_DEX_PANEL_HEIGHT)
        }
        DexFilePanel(
            dexFile = dexFile,
            buffer = buffer,
            heightPx = panelHeight,
            activeTab = effectiveTab,
            onActiveTabChange = {
                activeTab = it
                if (it != "Classes") selectedClassDefIdx = null
            },
            selectedClassDefIdx = selectedClassDefIdx,
            onClassSelected = { selectedClassDefIdx = it },
        )
    }
}

@Composable
private fun DexFilePanel(
    dexFile: DexFile,
    buffer: CommonByteBuffer,
    heightPx: Double,
    activeTab: String,
    onActiveTabChange: (String) -> Unit,
    selectedClassDefIdx: Int?,
    onClassSelected: (Int?) -> Unit,
) {
    val tabLabels = if (selectedClassDefIdx != null) {
        val classDef = dexFile.classDefs[selectedClassDefIdx]
        val className = dexFile.typeName(classDef.classIdx) ?: "<#${classDef.classIdx}>"
        listOf("Header", "\u2190 $className", "Strings")
    } else {
        listOf("Header", "Classes", "Strings")
    }

    Div({
        classes("hexana-panel")
        style {
            property("border-top", "1px solid var(--hexana-border, #333)")
            property("flex", "0 0 auto")
            height(heightPx.px)
            property("min-height", "80px")
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("overflow", "hidden")
        }
    }) {
        // Tab bar
        Div({
            classes("hexana-tab-bar")
            style {
                display(DisplayStyle.Flex)
                property("align-items", "center")
                property("gap", "0")
                property("flex-shrink", "0")
                property("border-bottom", "1px solid var(--hexana-border, #333)")
                property("background", "var(--hexana-tab-bg, rgba(255,255,255,0.03))")
            }
        }) {
            for ((index, label) in tabLabels.withIndex()) {
                val tabKey = when (index) {
                    0 -> "Header"
                    1 -> "Classes"
                    else -> "Strings"
                }
                val isActive = tabKey == activeTab
                Div({
                    classes("hexana-tab")
                    if (isActive) classes("hexana-tab-active")
                    style {
                        property("padding", "6px 16px")
                        property("cursor", "pointer")
                        property("font-size", "12px")
                        property("user-select", "none")
                        property("border-bottom", if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                        property("color", if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                        property("background", if (isActive) "var(--hexana-tab-active-bg, transparent)" else "transparent")
                    }
                    onClick {
                        if (tabKey == "Classes" && activeTab == "Classes" && selectedClassDefIdx != null) {
                            onClassSelected(null)
                        } else {
                            onActiveTabChange(tabKey)
                        }
                    }
                }) {
                    Text(label)
                }
            }
        }

        // Tab content
        Div({
            classes("hexana-tab-content")
            style {
                property("overflow", "hidden")
                property("padding", "8px 12px")
                property("flex", "1")
                property("min-height", "0")
                display(DisplayStyle.Flex)
                property("flex-direction", "column")
            }
        }) {
            when (activeTab) {
                "Header" -> DexHeaderTab(dexFile)
                "Classes" -> {
                    val classIdx = selectedClassDefIdx
                    if (classIdx != null) {
                        DexClassDetailView(
                            dex = dexFile,
                            classDefIndex = classIdx,
                            buffer = buffer,
                            onBack = { onClassSelected(null) },
                            onNavigateToClass = { onClassSelected(it) },
                        )
                    } else {
                        DexClassesTab(dexFile) { onClassSelected(it) }
                    }
                }
                "Strings" -> DexStringsTab(dexFile)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header tab
// ---------------------------------------------------------------------------

@Composable
private fun DexHeaderTab(dex: DexFile) {
    val h = dex.header
    Div({
        style {
            property("font-family", "monospace")
            property("font-size", "12px")
            property("line-height", "1.6")
            property("overflow-y", "auto")
            property("flex", "1")
            property("min-height", "0")
        }
    }) {
        Div({ style { property("font-weight", "bold") } }) {
            Text("Android DEX file")
        }
        Div { Text("version: ${h.version}") }
        Div { Text("file size: ${h.fileSize} bytes") }
        Div { Text("header size: ${h.headerSize} bytes") }
        Div { Text("checksum: 0x${h.checksum.toUInt().toString(16).padStart(8, '0')}") }
        Div {
            Text("signature: ${h.signature.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}")
        }
        Div { Text("strings: ${h.stringIdsSize}") }
        Div { Text("types: ${h.typeIdsSize}") }
        Div { Text("prototypes: ${h.protoIdsSize}") }
        Div { Text("fields: ${h.fieldIdsSize}") }
        Div { Text("methods: ${h.methodIdsSize}") }
        Div { Text("classes: ${h.classDefsSize}") }
    }
}

// ---------------------------------------------------------------------------
// Classes tab
// ---------------------------------------------------------------------------

private data class DexClassRowData(
    val index: Int,
    val classDefIndex: Int,
    val text: String,
)

@Composable
private fun DexClassesTab(dex: DexFile, onClassClick: (Int) -> Unit) {
    val rows = remember(dex) { buildClassRows(dex) }
    var searchQuery by remember(dex) { mutableStateOf("") }
    var debouncedQuery by remember(dex) { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    val filteredRows = remember(dex, debouncedQuery) {
        if (debouncedQuery.isBlank()) rows
        else {
            val q = debouncedQuery.trim().lowercase()
            rows.filter { it.text.lowercase().contains(q) }
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("gap", "4px")
            property("flex", "1")
            property("min-height", "0")
        }
    }) {
        Input(org.jetbrains.compose.web.attributes.InputType.Text) {
            classes("hexana-search-input")
            value(searchQuery)
            attr("placeholder", "Search classes\u2026")
            onInput { searchQuery = it.value }
            style {
                property("margin-bottom", "4px")
                property("padding", "4px 8px")
                property("font-size", "12px")
                property("background", "var(--vscode-input-background, #3c3c3c)")
                property("color", "var(--hexana-fg)")
                property("border", "1px solid var(--hexana-border, #333)")
                property("border-radius", "3px")
                property("outline", "none")
                property("flex", "none")
                width(100.percent)
            }
        }

        if (filteredRows.isEmpty()) {
            Div({
                style {
                    property("font-family", "monospace")
                    property("font-size", "12px")
                }
            }) {
                Text(if (searchQuery.isNotEmpty()) "No classes match the search." else "(no classes)")
            }
        } else {
            VirtualizedList(
                itemCount = filteredRows.size,
                itemHeightPx = 20,
            ) { idx ->
                val row = filteredRows[idx]
                val bg = if (row.index % 2 != 0) "var(--hexana-tab-bg, rgba(255,255,255,0.03))" else "transparent"
                Div({
                    style {
                        property("font-family", "monospace")
                        property("font-size", "12px")
                        property("padding", "2px 12px")
                        property("cursor", "pointer")
                        property("background", bg)
                        display(DisplayStyle.Flex)
                        property("gap", "12px")
                    }
                    onClick { onClassClick(row.classDefIndex) }
                }) {
                    Span({
                        style { property("color", "var(--vscode-editorLineNumber-foreground, #858585)") }
                    }) {
                        Text(row.index.toString().padStart(4, ' '))
                    }
                    Span { Text(row.text) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Strings tab
// ---------------------------------------------------------------------------

@Composable
private fun DexStringsTab(dex: DexFile) {
    data class IndexedString(val index: Int, val displayValue: String)

    val allStrings = remember(dex) {
        dex.stringIds.mapIndexed { idx, entry ->
            IndexedString(idx, escapeControlChars(entry.value))
        }
    }
    var searchQuery by remember(dex) { mutableStateOf("") }
    var debouncedQuery by remember(dex) { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    val filteredStrings = remember(dex, debouncedQuery) {
        if (debouncedQuery.isBlank()) allStrings
        else {
            val q = debouncedQuery.trim().lowercase()
            allStrings.filter { it.displayValue.lowercase().contains(q) }
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("gap", "4px")
            property("flex", "1")
            property("min-height", "0")
        }
    }) {
        Input(org.jetbrains.compose.web.attributes.InputType.Text) {
            classes("hexana-search-input")
            value(searchQuery)
            attr("placeholder", "Search strings\u2026")
            onInput { searchQuery = it.value }
            style {
                property("margin-bottom", "4px")
                property("padding", "4px 8px")
                property("font-size", "12px")
                property("background", "var(--vscode-input-background, #3c3c3c)")
                property("color", "var(--hexana-fg)")
                property("border", "1px solid var(--hexana-border, #333)")
                property("border-radius", "3px")
                property("outline", "none")
                property("flex", "none")
                width(100.percent)
            }
        }

        if (filteredStrings.isEmpty()) {
            Div({
                style {
                    property("font-family", "monospace")
                    property("font-size", "12px")
                }
            }) {
                Text(if (searchQuery.isNotEmpty()) "No strings match the search." else "(no strings)")
            }
        } else {
            VirtualizedList(
                itemCount = filteredStrings.size,
                itemHeightPx = 20,
            ) { idx ->
                val s = filteredStrings[idx]
                Div({
                    style {
                        property("font-family", "monospace")
                        property("font-size", "12px")
                        property("white-space", "nowrap")
                        property("overflow", "hidden")
                        property("text-overflow", "ellipsis")
                    }
                }) {
                    Text("#${s.index.toString().padStart(5, ' ')}  ${s.displayValue}")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Class detail view
// ---------------------------------------------------------------------------

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

    var detailTab by remember(classDefIndex) { mutableStateOf("Info") }
    val detailLabels = listOf("Info", "Fields", "Methods")
    val linkColor = "var(--vscode-textLink-foreground, #3794ff)"

    // Map type descriptors to classDef indices for navigation
    val typeToClassDefIdx = remember(dex) {
        dex.classDefs.withIndex().associate { (idx, cd) ->
            (dex.typeName(cd.classIdx) ?: "") to idx
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("gap", "4px")
            property("overflow-y", "auto")
            property("flex", "1")
            property("min-height", "0")
        }
    }) {
        // Back link
        Div({
            style {
                property("font-family", "monospace")
                property("font-size", "12px")
                property("color", linkColor)
                property("cursor", "pointer")
                property("margin-bottom", "4px")
            }
            onClick { onBack() }
        }) {
            Text("\u2190 Back to classes")
        }

        // Detail sub-tabs
        Div({
            style {
                display(DisplayStyle.Flex)
                property("gap", "0")
                property("border-bottom", "1px solid var(--hexana-border, #333)")
                property("margin-bottom", "4px")
            }
        }) {
            for (label in detailLabels) {
                val isActive = label == detailTab
                Div({
                    style {
                        property("padding", "4px 12px")
                        property("cursor", "pointer")
                        property("font-size", "12px")
                        property("user-select", "none")
                        property("border-bottom", if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                        property("color", if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                    }
                    onClick { detailTab = label }
                }) {
                    Text(label)
                }
            }
        }

        when (detailTab) {
            "Info" -> DexClassInfoContent(dex, classDef, className, interfaces, classData, typeToClassDefIdx, onNavigateToClass)
            "Fields" -> DexClassFieldsContent(dex, classData)
            "Methods" -> DexClassMethodsContent(dex, classData)
        }
    }
}

@Composable
private fun DexClassInfoContent(
    dex: DexFile,
    classDef: DexClassDef,
    className: String,
    interfaces: List<Int>,
    classData: DexClassData?,
    typeToClassDefIdx: Map<String, Int>,
    onNavigateToClass: (Int) -> Unit,
) {
    val linkColor = "var(--vscode-textLink-foreground, #3794ff)"
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

    Div({
        style {
            property("font-family", "monospace")
            property("font-size", "12px")
            property("line-height", "1.6")
        }
    }) {
        Div({ style { property("font-weight", "bold") } }) { Text(className) }
        if (flags.isNotEmpty()) Div { Text("access: $flags") }
        if (superName != null) {
            val superIdx = typeToClassDefIdx[superName]
            if (superIdx != null) {
                Div({
                    style {
                        property("color", linkColor)
                        property("cursor", "pointer")
                    }
                    onClick { onNavigateToClass(superIdx) }
                }) {
                    Text("extends: $superName")
                }
            } else {
                Div { Text("extends: $superName") }
            }
        }
        if (interfaces.isNotEmpty()) {
            Div { Text("implements:") }
            for (typeIdx in interfaces) {
                val ifaceName = dex.typeName(typeIdx) ?: "<#$typeIdx>"
                val ifaceClassIdx = typeToClassDefIdx[ifaceName]
                if (ifaceClassIdx != null) {
                    Div({
                        style {
                            property("padding-left", "16px")
                            property("color", linkColor)
                            property("cursor", "pointer")
                        }
                        onClick { onNavigateToClass(ifaceClassIdx) }
                    }) {
                        Text(ifaceName)
                    }
                } else {
                    Div({ style { property("padding-left", "16px") } }) { Text(ifaceName) }
                }
            }
        }
        if (sourceFile != null) Div { Text("source file: $sourceFile") }
        if (classData != null) {
            Div { Text("static fields: ${classData.staticFields.size}") }
            Div { Text("instance fields: ${classData.instanceFields.size}") }
            Div { Text("direct methods: ${classData.directMethods.size}") }
            Div { Text("virtual methods: ${classData.virtualMethods.size}") }
        } else {
            Div { Text("(no class data)") }
        }
    }
}

@Composable
private fun DexClassFieldsContent(dex: DexFile, classData: DexClassData?) {
    if (classData == null) {
        Div({
            style {
                property("font-family", "monospace")
                property("font-size", "12px")
            }
        }) { Text("(no class data)") }
        return
    }

    data class FieldRow(val index: Int, val text: String, val isCategory: Boolean)

    val rows = remember(dex, classData) {
        val result = mutableListOf<FieldRow>()
        var idx = 0
        fun addFields(fields: List<DexEncodedField>, category: String) {
            if (fields.isEmpty()) return
            result += FieldRow(idx++, "--- $category ---", true)
            for (f in fields) {
                val fieldId = dex.fieldIds.getOrNull(f.fieldIdx)
                val name = fieldId?.let { dex.string(it.nameIdx) } ?: "<#${f.fieldIdx}>"
                val type = fieldId?.let { dex.typeName(it.typeIdx) } ?: "?"
                val flags = renderDexFieldAccessFlags(f.accessFlags)
                val text = buildString {
                    if (flags.isNotEmpty()) { append(flags); append(' ') }
                    append(type); append(' '); append(name)
                }
                result += FieldRow(idx++, text, false)
            }
        }
        addFields(classData.staticFields, "Static fields")
        addFields(classData.instanceFields, "Instance fields")
        result
    }

    Div {
        if (rows.isEmpty()) {
            Div({
                style {
                    property("font-family", "monospace")
                    property("font-size", "12px")
                }
            }) { Text("(no fields)") }
        } else {
            for (row in rows) {
                val bg = if (row.index % 2 != 0) "var(--hexana-tab-bg, rgba(255,255,255,0.03))" else "transparent"
                Div({
                    style {
                        property("font-family", "monospace")
                        property("font-size", "12px")
                        property("padding", "2px 12px")
                        property("background", bg)
                        if (row.isCategory) property("font-weight", "bold")
                    }
                }) { Text(row.text) }
            }
        }
    }
}

@Composable
private fun DexClassMethodsContent(dex: DexFile, classData: DexClassData?) {
    if (classData == null) {
        Div({
            style {
                property("font-family", "monospace")
                property("font-size", "12px")
            }
        }) { Text("(no class data)") }
        return
    }

    data class MethodRow(val index: Int, val text: String, val isCategory: Boolean)

    val rows = remember(dex, classData) {
        val result = mutableListOf<MethodRow>()
        var idx = 0
        fun addMethods(methods: List<DexEncodedMethod>, category: String) {
            if (methods.isEmpty()) return
            result += MethodRow(idx++, "--- $category ---", true)
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
                result += MethodRow(idx++, text, false)
            }
        }
        addMethods(classData.directMethods, "Direct methods")
        addMethods(classData.virtualMethods, "Virtual methods")
        result
    }

    Div {
        if (rows.isEmpty()) {
            Div({
                style {
                    property("font-family", "monospace")
                    property("font-size", "12px")
                }
            }) { Text("(no methods)") }
        } else {
            for (row in rows) {
                val bg = if (row.index % 2 != 0) "var(--hexana-tab-bg, rgba(255,255,255,0.03))" else "transparent"
                Div({
                    style {
                        property("font-family", "monospace")
                        property("font-size", "12px")
                        property("padding", "2px 12px")
                        property("background", bg)
                        if (row.isCategory) property("font-weight", "bold")
                    }
                }) { Text(row.text) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
