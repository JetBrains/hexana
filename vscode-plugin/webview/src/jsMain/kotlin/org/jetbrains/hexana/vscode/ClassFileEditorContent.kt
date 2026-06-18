package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import kotlinx.coroutines.delay
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.javabytecode.*
import org.jetbrains.hexana.providers.GenericBinaryProvider

/**
 * Detects whether [buffer] starts with the JVM class file magic (`0xCAFEBABE`).
 */
internal fun isClassFile(buffer: CommonByteBuffer): Boolean {
    if (buffer.size < 4) return false
    return buffer[0] == 0xCA.toByte() &&
        buffer[1] == 0xFE.toByte() &&
        buffer[2] == 0xBA.toByte() &&
        buffer[3] == 0xBE.toByte()
}

private const val MIN_CLASS_PANEL_HEIGHT = 80.0
private const val DEFAULT_CLASS_PANEL_HEIGHT = 250.0

/**
 * Editor content for a parsed JVM `.class` file. Shows a hex viewer on top
 * and three structured tabs underneath: Header, Methods, Constant pool —
 * mirroring the IntelliJ plugin's `ClassFileEditorView`.
 */
@Composable
internal fun ClassFileEditorContent(
    fileName: String,
    classFile: JavaClassFile,
    buffer: CommonByteBuffer,
) {
    val selection = remember { HexSelectionState() }
    var panelHeight by remember { mutableStateOf(DEFAULT_CLASS_PANEL_HEIGHT) }
    var activeTab by remember { mutableStateOf("Header") }

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
            panelHeight = (panelHeight - deltaY).coerceAtLeast(MIN_CLASS_PANEL_HEIGHT)
        }
        ClassFilePanel(
            classFile = classFile,
            heightPx = panelHeight,
            activeTab = activeTab,
            onActiveTabChange = { activeTab = it },
        )
    }
}

@Composable
private fun ClassFilePanel(
    classFile: JavaClassFile,
    heightPx: Double,
    activeTab: String,
    onActiveTabChange: (String) -> Unit,
) {
    val tabs = listOf("Header", "Methods", "Constant pool")

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
            for (tab in tabs) {
                val isActive = tab == activeTab
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
                    onClick { onActiveTabChange(tab) }
                }) {
                    Text(tab)
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
                "Header" -> ClassHeaderTab(classFile)
                "Methods" -> ClassMethodsTab(classFile)
                "Constant pool" -> ClassConstantPoolTab(classFile)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header tab
// ---------------------------------------------------------------------------

@Composable
private fun ClassHeaderTab(cf: JavaClassFile) {
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
            Text("class ${cf.thisClassName}")
        }
        Div { Text("extends ${cf.superClassName ?: "<none>"}") }
        Div { Text("class file version: ${cf.majorVersion}.${cf.minorVersion}") }
        Div {
            Text("access flags: ${cf.accessFlags} ${renderClassAccessFlags(cf.accessFlags.raw)}")
        }

        if (cf.interfaceIndices.isEmpty()) {
            Div { Text("implements: (none)") }
        } else {
            Div { Text("implements:") }
            for (idx in cf.interfaceIndices) {
                val ref = cf.constantPool[idx]
                val name = (ref as? JavaConstantPoolEntry.ClassRef)
                    ?.let { runCatching { cf.constantPool.utf8(it.nameIndex) }.getOrNull() }
                    ?: "<#$idx ???>"
                Div({ style { property("padding-left", "16px") } }) { Text(name) }
            }
        }

        Div { Text("fields: ${cf.fields.size}") }
        Div { Text("methods: ${cf.methods.size}") }
        Div { Text("class attributes: ${cf.attributes.size}") }
    }
}

// ---------------------------------------------------------------------------
// Methods tab
// ---------------------------------------------------------------------------

private data class ClassMethodRow(
    val fileOffset: Int,
    val text: String,
    val bci: Int? = null,
)

private data class ClassMethodSection(
    val methodOffset: Int,
    val name: String,
    val descriptor: String,
    val signature: String,
    val bodyRows: List<ClassMethodRow>,
)

@Composable
private fun ClassMethodsTab(cf: JavaClassFile) {
    val sections = remember(cf) { buildMethodSections(cf) }
    var searchQuery by remember(cf) { mutableStateOf("") }
    var debouncedQuery by remember(cf) { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    val filteredSections = remember(cf, debouncedQuery) {
        if (debouncedQuery.isBlank()) sections
        else {
            val q = debouncedQuery.trim().lowercase()
            sections.filter { it.signature.lowercase().contains(q) }
        }
    }
    var folded by remember(cf) { mutableStateOf(emptySet<Int>()) }

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
        // Search field
        Input(org.jetbrains.compose.web.attributes.InputType.Text) {
            classes("hexana-search-input")
            value(searchQuery)
            attr("placeholder", "Search methods\u2026")
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

        if (filteredSections.isEmpty()) {
            Div({
                style {
                    property("font-family", "monospace")
                    property("font-size", "12px")
                }
            }) {
                Text(if (searchQuery.isNotEmpty()) "No methods match the search." else "(no methods)")
            }
        } else {
            for (section in filteredSections) {
                val isFolded = section.methodOffset in folded
                // Header row
                Div({
                    style {
                        property("font-family", "monospace")
                        property("font-size", "12px")
                        property("font-weight", "600")
                        property("background", "var(--hexana-tab-bg, rgba(255,255,255,0.05))")
                        property("padding", "4px 12px")
                        property("cursor", "pointer")
                        property("user-select", "none")
                        display(DisplayStyle.Flex)
                        property("gap", "8px")
                    }
                    onClick {
                        folded = if (isFolded) folded - section.methodOffset
                        else folded + section.methodOffset
                    }
                }) {
                    Span({
                        style { property("color", "var(--vscode-editorLineNumber-foreground, #858585)") }
                    }) {
                        Text(formatFileOffset(section.methodOffset))
                    }
                    Span({
                        style { property("color", "var(--vscode-editorLineNumber-foreground, #858585)") }
                    }) {
                        Text(if (isFolded) "\u25B6" else "\u25BC")
                    }
                    Span { Text(section.signature) }
                }
                // Body rows
                if (!isFolded) {
                    for (row in section.bodyRows) {
                        Div({
                            style {
                                property("font-family", "monospace")
                                property("font-size", "12px")
                                property("padding", "1px 12px")
                                display(DisplayStyle.Flex)
                                property("gap", "12px")
                            }
                        }) {
                            Span({
                                style { property("color", "var(--vscode-editorLineNumber-foreground, #858585)") }
                            }) {
                                Text(formatFileOffset(row.fileOffset))
                            }
                            Span { Text(row.text) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Constant pool tab
// ---------------------------------------------------------------------------

@Composable
private fun ClassConstantPoolTab(cf: JavaClassFile) {
    val rows = remember(cf) { cf.constantPool.asSequence().toList() }
    var searchQuery by remember(cf) { mutableStateOf("") }
    var debouncedQuery by remember(cf) { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    val filteredRows = remember(cf, debouncedQuery) {
        if (debouncedQuery.isBlank()) rows
        else {
            val q = debouncedQuery.trim().lowercase()
            rows.filter { (index, entry) ->
                val text = "#${index.toString().padStart(4, ' ')}  ${renderEntry(entry, cf.constantPool)}"
                text.lowercase().contains(q)
            }
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
            attr("placeholder", "Search constant pool\u2026")
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

        if (filteredRows.isEmpty() && searchQuery.isNotEmpty()) {
            Div({
                style {
                    property("font-family", "monospace")
                    property("font-size", "12px")
                }
            }) {
                Text("No entries match the search.")
            }
        } else {
            VirtualizedList(
                itemCount = filteredRows.size,
                itemHeightPx = 20,
            ) { idx ->
                val (index, entry) = filteredRows[idx]
                Div({
                    style {
                        property("font-family", "monospace")
                        property("font-size", "12px")
                        property("white-space", "pre")
                    }
                }) {
                    Text("#${index.toString().padStart(4, ' ')}  ${renderEntry(entry, cf.constantPool)}")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Methods-tab section builder
// ---------------------------------------------------------------------------

private fun buildMethodSections(cf: JavaClassFile): List<ClassMethodSection> {
    val sections = ArrayList<ClassMethodSection>()
    for (method in cf.methods) {
        val name = runCatching { cf.constantPool.utf8(method.nameIndex) }
            .getOrDefault("<#${method.nameIndex}>")
        val descriptor = runCatching { cf.constantPool.utf8(method.descriptorIndex) }
            .getOrDefault("<#${method.descriptorIndex}>")
        val flagsText = renderMethodAccessFlags(method.accessFlags.raw)
        val signature = buildString {
            if (flagsText.isNotEmpty()) {
                append(flagsText); append(' ')
            }
            append(name); append(' '); append(descriptor)
        }

        val body = ArrayList<ClassMethodRow>()
        body += ClassMethodRow(
            method.byteOffset,
            "flags: 0x${method.accessFlags.raw.toString(16).padStart(4, '0')}",
        )

        val code = runCatching { JavaCodeAttributeParser.parse(method, cf.constantPool) }.getOrNull()
        if (code == null) {
            body += ClassMethodRow(method.byteOffset, "(no Code attribute \u2014 abstract or native)")
        } else {
            body += ClassMethodRow(
                code.codeBaseFileOffset,
                "max_stack=${code.maxStack} max_locals=${code.maxLocals} " +
                    "code_length=${code.code.size} handlers=${code.exceptionTable.size}",
            )
            for (insn in code.instructions) {
                val fileOffset = code.codeBaseFileOffset + insn.bytecodeOffset
                body += ClassMethodRow(
                    fileOffset = fileOffset,
                    text = "${formatBytecodeOffset(insn.bytecodeOffset)}: ${formatInstruction(insn, cf.constantPool)}",
                    bci = insn.bytecodeOffset,
                )
            }
            if (code.exceptionTable.isNotEmpty()) {
                body += ClassMethodRow(code.codeBaseFileOffset, "exception_table:")
                for (handler in code.exceptionTable) {
                    val catchName = if (handler.catchTypeCpIndex == 0) {
                        "any"
                    } else {
                        val ref = cf.constantPool[handler.catchTypeCpIndex] as? JavaConstantPoolEntry.ClassRef
                        ref?.let { runCatching { cf.constantPool.utf8(it.nameIndex) }.getOrNull() }
                            ?: "<#${handler.catchTypeCpIndex} ???>"
                    }
                    body += ClassMethodRow(
                        code.codeBaseFileOffset + handler.handlerPc,
                        "[${formatBytecodeOffset(handler.startPc)}-${formatBytecodeOffset(handler.endPc)}) " +
                            "-> ${formatBytecodeOffset(handler.handlerPc)}  catch $catchName",
                    )
                }
            }
        }

        sections += ClassMethodSection(
            methodOffset = method.byteOffset,
            name = name,
            descriptor = descriptor,
            signature = signature,
            bodyRows = body,
        )
    }
    return sections
}

// ---------------------------------------------------------------------------
// Rendering helpers
// ---------------------------------------------------------------------------

private fun formatFileOffset(offset: Int): String =
    "0x" + offset.toString(16).padStart(6, '0')

private fun formatBytecodeOffset(offset: Int): String = offset.toString().padStart(4, ' ')

private fun formatInstruction(insn: JavaBytecodeInstruction, cp: JavaConstantPool): String {
    val mnemonic = insn.opcode.mnemonic
    return when (val ops = insn.operands) {
        JavaBytecodeOperands.None -> mnemonic
        is JavaBytecodeOperands.Byte1 -> "$mnemonic ${ops.value}"
        is JavaBytecodeOperands.Short1 -> {
            val resolved = resolveCpReference(ops.value, cp)
            if (resolved != null) "$mnemonic #${ops.value}  // $resolved" else "$mnemonic ${ops.value}"
        }
        is JavaBytecodeOperands.Wide1 -> "$mnemonic ${ops.value}"
        is JavaBytecodeOperands.IInc -> "$mnemonic ${ops.varIndex}, ${ops.delta}"
        is JavaBytecodeOperands.InvokeInterface ->
            "$mnemonic #${ops.cpIndex}, ${ops.count}  // ${resolveCpReference(ops.cpIndex, cp) ?: "?"}"
        is JavaBytecodeOperands.InvokeDynamic ->
            "$mnemonic #${ops.cpIndex}  // ${resolveCpReference(ops.cpIndex, cp) ?: "?"}"
        is JavaBytecodeOperands.MultiANewArray ->
            "$mnemonic #${ops.cpIndex}, ${ops.dimensions}  // ${resolveCpReference(ops.cpIndex, cp) ?: "?"}"
        is JavaBytecodeOperands.TableSwitch ->
            "$mnemonic default=${ops.defaultOffset} ${ops.low}..${ops.high} -> ${ops.jumpOffsets}"
        is JavaBytecodeOperands.LookupSwitch ->
            "$mnemonic default=${ops.defaultOffset} pairs=${ops.pairs}"
        is JavaBytecodeOperands.Wide ->
            "$mnemonic 0x${ops.innerOpcode.toString(16).padStart(2, '0')} var=${ops.varIndex}" +
                (ops.constant?.let { ", const=$it" } ?: "")
        is JavaBytecodeOperands.Raw -> "$mnemonic <raw ${ops.bytes.size} bytes>"
    }
}

private fun resolveCpReference(index: Int, cp: JavaConstantPool): String? {
    if (index <= 0) return null
    val entry = runCatching { cp[index] }.getOrNull() ?: return null
    return when (entry) {
        is JavaConstantPoolEntry.ClassRef ->
            runCatching { cp.utf8(entry.nameIndex) }.getOrNull()
        is JavaConstantPoolEntry.StringRef ->
            "\"${runCatching { cp.utf8(entry.stringIndex) }.getOrDefault("?")}\""
        is JavaConstantPoolEntry.Methodref,
        is JavaConstantPoolEntry.InterfaceMethodref,
        is JavaConstantPoolEntry.Fieldref,
        -> {
            val (classIdx, natIdx) = when (entry) {
                is JavaConstantPoolEntry.Methodref -> entry.classIndex to entry.nameAndTypeIndex
                is JavaConstantPoolEntry.InterfaceMethodref -> entry.classIndex to entry.nameAndTypeIndex
                is JavaConstantPoolEntry.Fieldref -> entry.classIndex to entry.nameAndTypeIndex
                else -> return null
            }
            val owner = (cp[classIdx] as? JavaConstantPoolEntry.ClassRef)
                ?.let { runCatching { cp.utf8(it.nameIndex) }.getOrNull() } ?: "?"
            val nat = cp[natIdx] as? JavaConstantPoolEntry.NameAndType ?: return owner
            val name = runCatching { cp.utf8(nat.nameIndex) }.getOrDefault("?")
            val desc = runCatching { cp.utf8(nat.descriptorIndex) }.getOrDefault("?")
            "$owner.$name:$desc"
        }
        is JavaConstantPoolEntry.Integer -> entry.value.toString()
        is JavaConstantPoolEntry.Float -> entry.value.toString() + "f"
        is JavaConstantPoolEntry.Long -> entry.value.toString() + "L"
        is JavaConstantPoolEntry.Double -> entry.value.toString()
        is JavaConstantPoolEntry.Utf8 -> "\"${entry.value}\""
        else -> null
    }
}

private fun renderEntry(entry: JavaConstantPoolEntry, cp: JavaConstantPool): String = when (entry) {
    is JavaConstantPoolEntry.Utf8 -> "Utf8                \"${entry.value}\""
    is JavaConstantPoolEntry.Integer -> "Integer             ${entry.value}"
    is JavaConstantPoolEntry.Float -> "Float               ${entry.value}"
    is JavaConstantPoolEntry.Long -> "Long                ${entry.value}"
    is JavaConstantPoolEntry.Double -> "Double              ${entry.value}"
    is JavaConstantPoolEntry.ClassRef -> "Class               #${entry.nameIndex}  // ${runCatching { cp.utf8(entry.nameIndex) }.getOrDefault("?")}"
    is JavaConstantPoolEntry.StringRef -> "String              #${entry.stringIndex}  // \"${runCatching { cp.utf8(entry.stringIndex) }.getOrDefault("?")}\""
    is JavaConstantPoolEntry.Fieldref -> "Fieldref            #${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.Methodref -> "Methodref           #${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.InterfaceMethodref -> "InterfaceMethodref  #${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.NameAndType -> "NameAndType         #${entry.nameIndex}:#${entry.descriptorIndex}"
    is JavaConstantPoolEntry.MethodHandle -> "MethodHandle        kind=${entry.referenceKind} ref=#${entry.referenceIndex}"
    is JavaConstantPoolEntry.MethodType -> "MethodType          #${entry.descriptorIndex}"
    is JavaConstantPoolEntry.Dynamic -> "Dynamic             bsm=${entry.bootstrapMethodAttrIndex} nat=#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.InvokeDynamic -> "InvokeDynamic       bsm=${entry.bootstrapMethodAttrIndex} nat=#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.ModuleRef -> "Module              #${entry.nameIndex}"
    is JavaConstantPoolEntry.PackageRef -> "Package             #${entry.nameIndex}"
    JavaConstantPoolEntry.Unusable -> "(unusable)"
}

private fun renderClassAccessFlags(raw: Int): String = buildString {
    if (raw and JavaClassAccess.PUBLIC != 0) append("public ")
    if (raw and JavaClassAccess.FINAL != 0) append("final ")
    if (raw and JavaClassAccess.SUPER != 0) append("super ")
    if (raw and JavaClassAccess.INTERFACE != 0) append("interface ")
    if (raw and JavaClassAccess.ABSTRACT != 0) append("abstract ")
    if (raw and JavaClassAccess.SYNTHETIC != 0) append("synthetic ")
    if (raw and JavaClassAccess.ANNOTATION != 0) append("annotation ")
    if (raw and JavaClassAccess.ENUM != 0) append("enum ")
    if (raw and JavaClassAccess.MODULE != 0) append("module ")
}.trim()

private fun renderMethodAccessFlags(raw: Int): String = buildString {
    if (raw and JavaMethodAccess.PUBLIC != 0) append("public ")
    if (raw and JavaMethodAccess.PRIVATE != 0) append("private ")
    if (raw and JavaMethodAccess.PROTECTED != 0) append("protected ")
    if (raw and JavaMethodAccess.STATIC != 0) append("static ")
    if (raw and JavaMethodAccess.FINAL != 0) append("final ")
    if (raw and JavaMethodAccess.SYNCHRONIZED != 0) append("synchronized ")
    if (raw and JavaMethodAccess.BRIDGE != 0) append("bridge ")
    if (raw and JavaMethodAccess.VARARGS != 0) append("varargs ")
    if (raw and JavaMethodAccess.NATIVE != 0) append("native ")
    if (raw and JavaMethodAccess.ABSTRACT != 0) append("abstract ")
    if (raw and JavaMethodAccess.STRICT != 0) append("strict ")
    if (raw and JavaMethodAccess.SYNTHETIC != 0) append("synthetic ")
}.trim()
