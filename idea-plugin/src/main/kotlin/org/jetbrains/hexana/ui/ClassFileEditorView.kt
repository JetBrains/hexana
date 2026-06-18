package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.util.Key
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.javabytecode.JavaBytecodeInstruction
import org.jetbrains.hexana.javabytecode.JavaBytecodeOperands
import org.jetbrains.hexana.javabytecode.JavaClassAccess
import org.jetbrains.hexana.javabytecode.JavaClassFile
import org.jetbrains.hexana.javabytecode.JavaCodeAttribute
import org.jetbrains.hexana.javabytecode.JavaCodeAttributeParser
import org.jetbrains.hexana.javabytecode.JavaConstantPool
import org.jetbrains.hexana.javabytecode.JavaConstantPoolEntry
import org.jetbrains.hexana.javabytecode.JavaFieldAccess
import org.jetbrains.hexana.javabytecode.JavaMethodAccess
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Navigation target handed to [ClassFileEditorView] when another view (today: the
 * JIT inline-frame click in `JitFileEditorView`) wants the bytecode editor to
 * scroll to a specific method and optionally a specific BCI inside it.
 *
 * Producers stash an instance in [CLASS_FILE_NAV_TARGET_KEY] on the
 * [com.intellij.openapi.vfs.VirtualFile] before opening, or push it into the
 * editor's `MutableStateFlow` if the editor is already open.
 */
data class ClassFileNavTarget(
    /** Method name as written in the class file's constant pool, e.g. `decode`, `<init>`. */
    val methodName: String,
    /** JVM method descriptor, e.g. `(Lfoo/Bar;I)V`. Matched byte-for-byte against `Code` rows. */
    val methodDescriptor: String,
    /** Optional bytecode index to scroll to within the matched method. */
    val bci: Int? = null,
)

val CLASS_FILE_NAV_TARGET_KEY: Key<ClassFileNavTarget> = Key.create("hexana.classFile.navTarget")

/**
 * Editor view for a parsed JVM `.class` file. Vertically split: a [HexViewer]
 * over the raw class bytes on top, three structured tabs underneath:
 *  1. Header — class name + access flags + super class + interfaces.
 *  2. Methods — list of foldable per-method sections styled to match
 *     WAT (virtualized): gray header row with a file offset + ▶/▼ +
 *     signature; monospace body rows underneath when expanded.
 *  3. Constant pool — all `cp_info` entries by index.
 *
 * [navTargetFlow] lets external callers drive the Methods tab to a specific
 * method (and optionally BCI). The flow's value is consumed (set to null)
 * after each successful navigation so subsequent equal requests still fire.
 */
@Composable
internal fun ClassFileEditorView(
    classFile: JavaClassFile,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
    navTargetFlow: MutableStateFlow<ClassFileNavTarget?> = remember { MutableStateFlow(null) },
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
            ClassFileTabs(classFile, navTargetFlow)
        },
    )
}

@Composable
private fun ClassFileTabs(
    classFile: JavaClassFile,
    navTargetFlow: MutableStateFlow<ClassFileNavTarget?>,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val navTarget by navTargetFlow.collectAsState()

    // External nav requests always target the Methods tab — switch as soon as
    // a non-null target arrives. The actual unfold + scroll happens inside
    // [ClassMethodsTab] once it composes.
    LaunchedEffect(navTarget) {
        if (navTarget != null) selectedTab = 1
    }

    val labels = listOf("Header", "Methods", "Constant pool")
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
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            when (selectedTab) {
                0 -> ClassHeaderTab(classFile)
                1 -> ClassMethodsTab(classFile, navTargetFlow)
                else -> ClassConstantPoolTab(classFile)
            }
        }
    }
}

@Composable
private fun ClassHeaderTab(cf: JavaClassFile) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "class ${cf.thisClassName}",
            style = TextStyle(fontWeight = FontWeight.Bold),
        )
        Text("extends ${cf.superClassName ?: "<none>"}")
        Text("class file version: ${cf.majorVersion}.${cf.minorVersion}")
        Text(
            "access flags: ${cf.accessFlags} ${renderClassAccessFlags(cf.accessFlags.raw)}",
            style = MonoStyle,
        )

        if (cf.interfaceIndices.isEmpty()) {
            Text("implements: (none)")
        } else {
            Text("implements:")
            for (idx in cf.interfaceIndices) {
                val ref = cf.constantPool[idx]
                val name = (ref as? JavaConstantPoolEntry.ClassRef)
                    ?.let { runCatching { cf.constantPool.utf8(it.nameIndex) }.getOrNull() }
                    ?: "<#$idx ???>"
                Text("  $name", style = MonoStyle)
            }
        }

        Text("fields: ${cf.fields.size}")
        Text("methods: ${cf.methods.size}")
        Text("class attributes: ${cf.attributes.size}")
    }
}

// -----------------------------------------------------------------------------
// Methods tab — WAT (virtualized) style: each method is a foldable section
// -----------------------------------------------------------------------------

@Composable
private fun ClassMethodsTab(
    cf: JavaClassFile,
    navTargetFlow: MutableStateFlow<ClassFileNavTarget?>,
) {
    val sections = remember(cf) { buildMethodSections(cf) }
    var searchQuery by remember(cf) { mutableStateOf(TextFieldValue("")) }
    val filteredSections = remember(cf, searchQuery.text) {
        if (searchQuery.text.isBlank()) sections
        else {
            val q = searchQuery.text.trim().lowercase()
            sections.filter { it.signature.lowercase().contains(q) }
        }
    }
    var folded by remember(cf) { mutableStateOf(emptySet<Int>()) }
    val listState = rememberLazyListState()
    val navTarget by navTargetFlow.collectAsState()

    LaunchedEffect(navTarget, sections) {
        val target = navTarget ?: return@LaunchedEffect
        val sectionIdx = sections.indexOfFirst {
            it.name == target.methodName && it.descriptor == target.methodDescriptor
        }
        if (sectionIdx < 0) {
            // Method not found in this class — leave the view alone and clear
            // the request so a later identical click can retry.
            navTargetFlow.value = null
            return@LaunchedEffect
        }
        // Clear search so the full list is rendered and scroll indices are valid.
        searchQuery = TextFieldValue("")
        // Ensure the target method is unfolded so its body rows participate
        // in the LazyColumn index computation below.
        val targetOffset = sections[sectionIdx].methodOffset
        folded = folded - targetOffset
        val globalIdx = globalRowIndex(sections, folded, sectionIdx, target.bci)
        listState.scrollToItem(globalIdx)
        navTargetFlow.value = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search methods",
        )
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (filteredSections.isEmpty()) {
                item { Text(if (searchQuery.text.isNotEmpty()) "No methods match the search." else "(no methods)", style = RowTextStyle) }
                return@LazyColumn
            }
            for (section in filteredSections) {
                val isFolded = section.methodOffset in folded
                item(key = "header-${section.methodOffset}") {
                    MethodHeaderRow(
                        section = section,
                        folded = isFolded,
                        onToggle = {
                            folded = if (isFolded) folded - section.methodOffset
                            else folded + section.methodOffset
                        },
                    )
                }
                if (!isFolded) {
                    items(
                        count = section.bodyRows.size,
                        key = { idx -> "body-${section.methodOffset}-$idx" },
                    ) { idx ->
                        MethodBodyRow(section.bodyRows[idx])
                    }
                }
            }
        }
        VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
    }
}

@Composable
private fun MethodHeaderRow(
    section: MethodSection,
    folded: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val headerStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )
    val gutterStyle = headerStyle.copy(color = HexanaTheme.hexTextColor)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatFileOffset(section.methodOffset), style = gutterStyle)
        Spacer(modifier = Modifier.width(12.dp))
        Text(if (folded) "▶" else "▼", style = gutterStyle)
        Spacer(modifier = Modifier.width(8.dp))
        Text(section.signature, style = headerStyle)
    }
}

@Composable
private fun MethodBodyRow(row: MethodRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatFileOffset(row.fileOffset),
            style = RowTextStyle.copy(color = HexanaTheme.hexTextColor),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(row.text, style = RowTextStyle)
    }
}

@Composable
private fun ClassConstantPoolTab(cf: JavaClassFile) {
    val rows = remember(cf) { cf.constantPool.asSequence().toList() }
    var searchQuery by remember(cf) { mutableStateOf(TextFieldValue("")) }
    val filteredRows = remember(cf, searchQuery.text) {
        if (searchQuery.text.isBlank()) rows
        else {
            val q = searchQuery.text.trim().lowercase()
            rows.filter { (index, entry) ->
                val text = "#${index.toString().padStart(4, ' ')}  ${renderEntry(entry, cf.constantPool)}"
                text.lowercase().contains(q)
            }
        }
    }
    val listState = rememberLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search constant pool",
        )
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (filteredRows.isEmpty() && searchQuery.text.isNotEmpty()) {
                    item { Text("No entries match the search.", style = RowTextStyle) }
                } else {
                    items(filteredRows.size) { idx ->
                        val (index, entry) = filteredRows[idx]
                        Text(
                            "#${index.toString().padStart(4, ' ')}  ${renderEntry(entry, cf.constantPool)}",
                            style = RowTextStyle,
                        )
                    }
                }
            }
            VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

// -----------------------------------------------------------------------------
// Methods-tab section builder
// -----------------------------------------------------------------------------

private data class MethodRow(
    val fileOffset: Int,
    val text: String,
    /** BCI for instruction rows; null for flags / max_stack / exception_table rows. */
    val bci: Int? = null,
)

private data class MethodSection(
    val methodOffset: Int,
    /** Method name as stored in the constant pool, used to match [ClassFileNavTarget]. */
    val name: String,
    /** JVM method descriptor as stored in the constant pool, used to match [ClassFileNavTarget]. */
    val descriptor: String,
    val signature: String,
    val bodyRows: List<MethodRow>,
)

/**
 * Compute the LazyColumn item index that [ClassMethodsTab] should scroll to
 * for [navBci] inside the section at [targetSectionIdx], assuming that
 * section is currently unfolded. Walks preceding sections, counting one
 * item per header plus body rows when the section isn't in [folded].
 */
private fun globalRowIndex(
    sections: List<MethodSection>,
    folded: Set<Int>,
    targetSectionIdx: Int,
    navBci: Int?,
): Int {
    var idx = 0
    for (i in 0 until targetSectionIdx) {
        val s = sections[i]
        idx += 1 // header row
        if (s.methodOffset !in folded) idx += s.bodyRows.size
    }
    // idx now points at the target section's header.
    if (navBci == null) return idx
    val target = sections[targetSectionIdx]
    val rowIdx = target.bodyRows.indexOfFirst { it.bci == navBci }
    return if (rowIdx >= 0) idx + 1 + rowIdx else idx
}

private fun buildMethodSections(cf: JavaClassFile): List<MethodSection> {
    val sections = ArrayList<MethodSection>()
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

        val body = ArrayList<MethodRow>()
        body += MethodRow(
            method.byteOffset,
            "flags: 0x${method.accessFlags.raw.toString(16).padStart(4, '0')}",
        )

        val code = runCatching { JavaCodeAttributeParser.parse(method, cf.constantPool) }.getOrNull()
        if (code == null) {
            body += MethodRow(method.byteOffset, "(no Code attribute — abstract or native)")
        } else {
            body += MethodRow(
                code.codeBaseFileOffset,
                "max_stack=${code.maxStack} max_locals=${code.maxLocals} " +
                    "code_length=${code.code.size} handlers=${code.exceptionTable.size}",
            )
            appendInstructionRows(body, code, cf.constantPool)
            if (code.exceptionTable.isNotEmpty()) {
                body += MethodRow(code.codeBaseFileOffset, "exception_table:")
                for (handler in code.exceptionTable) {
                    val catchName = if (handler.catchTypeCpIndex == 0) {
                        "any"
                    } else {
                        val ref = cf.constantPool[handler.catchTypeCpIndex] as? JavaConstantPoolEntry.ClassRef
                        ref?.let { runCatching { cf.constantPool.utf8(it.nameIndex) }.getOrNull() }
                            ?: "<#${handler.catchTypeCpIndex} ???>"
                    }
                    body += MethodRow(
                        code.codeBaseFileOffset + handler.handlerPc,
                        "[${formatBytecodeOffset(handler.startPc)}-${formatBytecodeOffset(handler.endPc)}) " +
                            "-> ${formatBytecodeOffset(handler.handlerPc)}  catch $catchName",
                    )
                }
            }
        }

        sections += MethodSection(
            methodOffset = method.byteOffset,
            name = name,
            descriptor = descriptor,
            signature = signature,
            bodyRows = body,
        )
    }
    return sections
}

private fun appendInstructionRows(
    out: MutableList<MethodRow>,
    code: JavaCodeAttribute,
    cp: JavaConstantPool,
) {
    for (insn in code.instructions) {
        val fileOffset = code.codeBaseFileOffset + insn.bytecodeOffset
        out += MethodRow(
            fileOffset = fileOffset,
            text = "${formatBytecodeOffset(insn.bytecodeOffset)}: ${formatInstruction(insn, cp)}",
            bci = insn.bytecodeOffset,
        )
    }
}

// -----------------------------------------------------------------------------
// Rendering helpers
// -----------------------------------------------------------------------------

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)
private val RowTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

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

/** Kept for symmetry; field rendering follows in a later commit. */
@Suppress("unused")
private fun renderFieldAccessFlags(raw: Int): String = buildString {
    if (raw and JavaFieldAccess.PUBLIC != 0) append("public ")
    if (raw and JavaFieldAccess.PRIVATE != 0) append("private ")
    if (raw and JavaFieldAccess.PROTECTED != 0) append("protected ")
    if (raw and JavaFieldAccess.STATIC != 0) append("static ")
    if (raw and JavaFieldAccess.FINAL != 0) append("final ")
    if (raw and JavaFieldAccess.VOLATILE != 0) append("volatile ")
    if (raw and JavaFieldAccess.TRANSIENT != 0) append("transient ")
    if (raw and JavaFieldAccess.SYNTHETIC != 0) append("synthetic ")
    if (raw and JavaFieldAccess.ENUM != 0) append("enum ")
}.trim()
