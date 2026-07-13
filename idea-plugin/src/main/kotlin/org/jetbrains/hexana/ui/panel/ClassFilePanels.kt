package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.javabytecode.*
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.ui.ClassFileNavTarget
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

internal fun classFilePanels(
    classFile: JavaClassFile,
    navTargetFlow: MutableStateFlow<ClassFileNavTarget?>,
    state: HexViewStateHolder,
): List<Panel> = listOf(
    Panel("Header") { ClassHeaderTab(classFile) },
    Panel("Methods") { ClassMethodsTab(classFile, navTargetFlow, state) },
)

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
// Methods tab — common virtualized chunk panel
// -----------------------------------------------------------------------------

/** Section kinds for the JVM class-file methods view. */
private object JvmSectionKinds {
    val METHODS: SectionKind = SectionKind("jvm.methods")
    val METHOD: SectionKind = SectionKind("jvm.method")
    val METHOD_BODY: SectionKind = SectionKind("jvm.method-body")
}

/**
 * [ChunkTree] over [MethodSection]s. Layout mirrors the disassembly panel:
 * one top-level METHODS header, then per method a foldable METHOD header +
 * a METHOD_BODY element whose body rows are rendered by [elementContent].
 */
private class JvmMethodChunkTree(
    private val sections: List<MethodSection>,
) : ChunkTree {
    private val chunks: List<BinaryChunk> = buildList {
        sections.forEachIndexed { idx, section ->
            val sectionStart = section.methodOffset.toLong()
            val sectionEnd = (section.bodyRows.lastOrNull()?.fileOffset?.toLong() ?: sectionStart) + 1
            val byteRange = sectionStart..sectionEnd
            add(
                BinaryChunk.SectionHeader(
                    id = ChunkId.Symbolic(JvmSectionKinds.METHOD, "${section.name}${section.descriptor}"),
                    byteRange = byteRange,
                    estimatedLines = 1,
                    kind = JvmSectionKinds.METHOD,
                    elementCount = section.bodyRows.size,
                    totalSize = sectionEnd - sectionStart,
                ),
            )
            add(
                BinaryChunk.Element(
                    id = ChunkId.Indexed(JvmSectionKinds.METHOD_BODY, idx),
                    byteRange = byteRange,
                    estimatedLines = section.bodyRows.size,
                    kind = JvmSectionKinds.METHOD_BODY,
                    indexInSection = idx,
                    displayName = section.signature,
                ),
            )
        }
    }

    override val totalChunks: Int get() = chunks.size
    override fun chunkAt(index: Int): BinaryChunk = chunks[index]
    override fun chunkContainingByte(offset: Long): Int? {
        chunks.forEachIndexed { i, c -> if (offset in c.byteRange) return i }
        return null
    }
    override fun chunkForId(id: ChunkId): Int? {
        chunks.forEachIndexed { i, c -> if (c.id == id) return i }
        return null
    }
}

@Composable
private fun ClassMethodsTab(
    cf: JavaClassFile,
    navTargetFlow: MutableStateFlow<ClassFileNavTarget?>,
    state: HexViewStateHolder,
) {
    val sections = remember(cf) { buildMethodSections(cf) }
    val tree = remember(sections) { JvmMethodChunkTree(sections) }

    // Build a name → ChunkId index for search.
    val methodIndex: Map<String, ChunkId> = remember(sections) {
        val out = HashMap<String, ChunkId>(sections.size)
        sections.forEachIndexed { idx, s ->
            out[s.signature.lowercase()] = ChunkId.Indexed(JvmSectionKinds.METHOD_BODY, idx)
        }
        out
    }

    val config = remember(tree, methodIndex) {
        VirtualizedChunkPanelConfig(
            sectionDisplayName = { kind ->
                when (kind) {
                    JvmSectionKinds.METHODS -> "Methods"
                    JvmSectionKinds.METHOD -> "method"
                    JvmSectionKinds.METHOD_BODY -> "body"
                    else -> kind.id
                }
            },
            colorForToken = ::defaultColorFor,
            searchPlaceholder = "Search methods",
            computeSearchResults = { query ->
                val q = query.trim().lowercase()
                if (q.isEmpty()) emptyList()
                else methodIndex.entries
                    .filter { q in it.key }
                    .take(50)
                    .map { (name, chunkId) ->
                        SimpleChunkSearchResult(label = name, chunkId = chunkId)
                    }
            },
            itemCountForChunk = { chunkId ->
                val idx = (chunkId as? ChunkId.Indexed)?.index
                if (idx != null && chunkId.sectionKind == JvmSectionKinds.METHOD_BODY) {
                    sections.getOrNull(idx)?.bodyRows?.size ?: 1
                } else {
                    1
                }
            },
            sectionParent = mapOf(
                JvmSectionKinds.METHOD_BODY to JvmSectionKinds.METHOD,
            ),
            onOffsetClicked = { offset -> state.scrollHexViewTo(offset, setCursor = true) },
        )
    }

    // External navigation: capture navigator ref, then scroll on nav target.
    var navigatorRef by remember { mutableStateOf<ChunkPanelNavigator?>(null) }
    val navTarget by navTargetFlow.collectAsState()
    LaunchedEffect(navTarget, navigatorRef) {
        val target = navTarget ?: return@LaunchedEffect
        val nav = navigatorRef ?: return@LaunchedEffect
        val sectionIdx = sections.indexOfFirst {
            it.name == target.methodName && it.descriptor == target.methodDescriptor
        }
        if (sectionIdx >= 0) {
            val methodHeaderId = ChunkId.Symbolic(
                JvmSectionKinds.METHOD,
                "${sections[sectionIdx].name}${sections[sectionIdx].descriptor}",
            )
            nav.gotoChunk(methodHeaderId)
        }
        navTargetFlow.value = null
    }

    VirtualizedChunkPanel(
        chunkTree = tree,
        config = config,
        onNavigatorReady = { navigatorRef = it },
        sectionHeaderContent = { chunk, folded, onToggle ->
            if (chunk.kind == JvmSectionKinds.METHOD) {
                val key = (chunk.id as? ChunkId.Symbolic)?.name ?: ""
                val section = sections.firstOrNull { "${it.name}${it.descriptor}" == key }
                if (section != null) {
                    MethodHeaderRow(section = section, folded = folded, onToggle = onToggle)
                } else {
                    DefaultJvmSectionHeader(chunk, folded, onToggle)
                }
            } else {
                DefaultJvmSectionHeader(chunk, folded, onToggle)
            }
        },
        elementContent = { chunk, _ ->
            val idx = chunk.indexInSection
            val section = sections.getOrNull(idx)
            if (section != null) {
                items(
                    count = section.bodyRows.size,
                    key = { rowIdx -> "body-${section.methodOffset}-$rowIdx" },
                ) { rowIdx ->
                    MethodBodyRow(section.bodyRows[rowIdx])
                }
            }
        },
    )
}

/** Default section header for the top-level "Methods" row. */
@Composable
private fun DefaultJvmSectionHeader(
    chunk: BinaryChunk.SectionHeader,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (folded) "▶" else "▼", style = headerStyle.copy(color = HexanaTheme.hexTextColor))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "${chunk.elementCount} entries",
            style = headerStyle,
        )
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
    val tokens = remember(row) {
        listOf(
            RenderToken(
                text = formatFileOffset(row.fileOffset),
                kind = RenderTokenKind.OFFSET_ANNOTATION,
                sourceOffset = row.fileOffset.toLong(),
            ),
            RenderToken(text = "  ", kind = RenderTokenKind.WHITESPACE),
            RenderToken(text = row.text, kind = RenderTokenKind.IDENTIFIER),
        )
    }
    SelectableTokenRow(
        tokens = tokens,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
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
