package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dex.DexAccessFlags
import org.jetbrains.hexana.dex.DexClassData
import org.jetbrains.hexana.dex.DexClassDef
import org.jetbrains.hexana.dex.DexCodeItem
import org.jetbrains.hexana.dex.DexEncodedField
import org.jetbrains.hexana.dex.DexEncodedMethod
import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.dex.DexIndexKind
import org.jetbrains.hexana.dex.DexInstruction
import org.jetbrains.hexana.dex.DexOpcode
import org.jetbrains.hexana.dex.DexPayload
import org.jetbrains.hexana.dex.DexTry
import org.jetbrains.hexana.dex.renderDexClassAccessFlags
import org.jetbrains.hexana.dex.renderDexFieldAccessFlags
import org.jetbrains.hexana.dex.renderDexMethodAccessFlags
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.io.CommonByteBuffer

// ---------------------------------------------------------------------------
// Smali text generation for Android DEX files.
//
// This is the DEX counterpart of WatTextGenerator: a UI-free renderer that
// turns parsed structure into tokenized rows a host can display. Hosts own the
// widgets; this file owns what the listing says.
//
// The model is deliberately split into a cheap eager part and an expensive lazy
// part. dexSmaliClasses() and dexSmaliChunkTree() read only class_def_item
// entries, which the parser has already decoded, so a multi-megabyte DEX opens
// instantly with every class collapsed. dexSmaliClassRows() is what actually
// reads class_data_item and code_item, and is meant to be called per class, off
// the UI thread, when that class is expanded.
// ---------------------------------------------------------------------------

/**
 * Tab label of the smali view. Shared so every host names the tab identically —
 * the IntelliJ side also matches it against its panel-name discovery.
 */
const val DEX_SMALI_TAB_NAME: String = "Smali"

/** Section kinds of the smali view; see [dexSmaliChunkTree]. */
object DexSmaliSectionKinds {
    /** Foldable per-class header row. */
    val CLASS: SectionKind = SectionKind("dex.class")

    /** The class body: one element chunk rendered as many [DexSmaliRow]s. */
    val CLASS_BODY: SectionKind = SectionKind("dex.class-body")
}

/** Size of a `class_def_item` in bytes — eight `uint`s. */
private const val CLASS_DEF_ITEM_SIZE = 32L

/** `NO_INDEX`, the DEX sentinel for "absent" in an index field. */
private const val NO_INDEX = -1

/**
 * A class in the smali view, built from its `class_def_item` alone.
 *
 * [byteRange] covers the `class_def_item` itself, not the class data spread
 * across the data section, so ranges stay ordered and disjoint as [ChunkTree]
 * requires.
 */
data class DexSmaliClass(
    /** Index into [DexFile.classDefs]. */
    val index: Int,
    /** Type descriptor, e.g. `Lcom/example/Foo;`. */
    val descriptor: String,
    val accessFlags: Int,
    /** Superclass descriptor, or `null` for `java.lang.Object` and interfaces. */
    val superDescriptor: String?,
    val byteRange: LongRange,
    val classDataOff: Long,
)

/** One rendered line of the smali view. */
data class DexSmaliRow(
    /** Absolute file offset this line describes, or `null` for structural lines. */
    val fileOffset: Long?,
    val tokens: List<RenderToken>,
) {
    /** The line as plain text, for tests, tooltips and copy-to-clipboard. */
    val text: String get() = tokens.joinToString("") { it.text }
}

/** A searchable name in the smali view, resolving to the class that declares it. */
data class DexSmaliSearchEntry(
    val label: String,
    val detail: String?,
    /** Index into [DexFile.classDefs]; navigate via [dexSmaliClassBodyId]. */
    val classIndex: Int,
)

/** Cheap pass over `class_def_item`s; reads no class data. */
fun dexSmaliClasses(dex: DexFile): List<DexSmaliClass> {
    val base = dex.header.classDefsOff
    return dex.classDefs.mapIndexed { index, def ->
        val start = base + index * CLASS_DEF_ITEM_SIZE
        DexSmaliClass(
            index = index,
            descriptor = dex.typeRef(def.classIdx),
            accessFlags = def.accessFlags,
            superDescriptor = def.superclassIdx.takeIf { it != NO_INDEX }?.let { dex.typeRef(it) },
            byteRange = start until (start + CLASS_DEF_ITEM_SIZE),
            classDataOff = def.classDataOff,
        )
    }
}

/** Chunk id of a class's foldable header row. */
fun dexSmaliClassHeaderId(cls: DexSmaliClass): ChunkId =
    ChunkId.Symbolic(DexSmaliSectionKinds.CLASS, cls.descriptor)

/** Chunk id of a class's body element. */
fun dexSmaliClassBodyId(classIndex: Int): ChunkId =
    ChunkId.Indexed(DexSmaliSectionKinds.CLASS_BODY, classIndex)

/**
 * Builds the [ChunkTree] of the smali view: per class a foldable
 * [DexSmaliSectionKinds.CLASS] header followed by one
 * [DexSmaliSectionKinds.CLASS_BODY] element whose rows come from
 * [dexSmaliClassRows].
 */
fun dexSmaliChunkTree(classes: List<DexSmaliClass>): ChunkTree = DexSmaliChunkTree(classes)

/**
 * Renders one class as smali. Reads the class's `class_data_item` and every
 * method's `code_item`, so this is the expensive half of the model — call it
 * lazily, per class, off the UI thread.
 *
 * The buffer's cursor is not shared: [CommonByteBuffer.duplicate] is used
 * internally, so concurrent calls over the same buffer are safe.
 *
 * A class or method whose data cannot be read degrades to an error row instead
 * of taking the whole listing down.
 */
fun dexSmaliClassRows(dex: DexFile, bytes: CommonByteBuffer, cls: DexSmaliClass): List<DexSmaliRow> {
    val buffer = bytes.duplicate()
    val classDef = dex.classDefs.getOrNull(cls.index)
        ?: return listOf(errorRow("class #${cls.index} is not in this DEX file"))
    val rows = ArrayList<DexSmaliRow>()

    appendClassHeader(dex, buffer, classDef, cls, rows)

    val classData = try {
        DexFileParser.parseClassData(buffer, classDef)
    } catch (e: Throwable) {
        rows += errorRow("class_data_item at 0x${classDef.classDataOff.toString(16)} is unreadable: ${e.message}")
        null
    }
    if (classData == null) {
        if (classDef.classDataOff == 0L) {
            rows += blankRow()
            rows += commentRow("# no class data")
        }
        return rows
    }

    appendFields(dex, "# static fields", classData.staticFields, rows)
    appendFields(dex, "# instance fields", classData.instanceFields, rows)
    appendMethods(dex, buffer, "# direct methods", classData.directMethods, rows)
    appendMethods(dex, buffer, "# virtual methods", classData.virtualMethods, rows)
    return rows
}

/**
 * Builds the search index: class descriptors plus, when the file is small
 * enough to justify the extra pass, every declared method signature.
 *
 * Reading method names means parsing every `class_data_item`, so files with
 * more than [methodIndexClassLimit] classes are indexed by class name only —
 * the panel stays responsive on APK-sized DEX files, where a full pass would
 * cost far more than the search is worth.
 */
fun dexSmaliSearchIndex(
    dex: DexFile,
    bytes: CommonByteBuffer,
    classes: List<DexSmaliClass>,
    methodIndexClassLimit: Int = 4_000,
    limit: Int = 100_000,
): List<DexSmaliSearchEntry> {
    val out = ArrayList<DexSmaliSearchEntry>(classes.size)
    for (cls in classes) {
        if (out.size >= limit) return out
        out += DexSmaliSearchEntry(cls.descriptor, detail = null, classIndex = cls.index)
    }
    if (classes.size > methodIndexClassLimit) return out

    val buffer = bytes.duplicate()
    for (cls in classes) {
        val classDef = dex.classDefs.getOrNull(cls.index) ?: continue
        val classData = try {
            DexFileParser.parseClassData(buffer, classDef)
        } catch (_: Throwable) {
            null
        } ?: continue
        for (method in classData.directMethods + classData.virtualMethods) {
            if (out.size >= limit) return out
            val methodId = dex.methodIds.getOrNull(method.methodIdx) ?: continue
            val name = dex.stringRef(methodId.nameIdx)
            out += DexSmaliSearchEntry(
                label = name + dex.protoDescriptor(methodId.protoIdx),
                detail = cls.descriptor,
                classIndex = cls.index,
            )
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// Class body rendering
// ---------------------------------------------------------------------------

private fun appendClassHeader(
    dex: DexFile,
    buffer: CommonByteBuffer,
    classDef: DexClassDef,
    cls: DexSmaliClass,
    rows: MutableList<DexSmaliRow>,
) {
    val classDefOffset = cls.byteRange.first
    rows += row(classDefOffset) {
        directive(".class")
        flags(renderDexClassAccessFlags(cls.accessFlags))
        type(cls.descriptor)
    }
    if (cls.superDescriptor != null) {
        rows += row(classDefOffset) {
            directive(".super")
            type(cls.superDescriptor)
        }
    }
    classDef.sourceFileIdx.takeIf { it != NO_INDEX }?.let { sourceIdx ->
        rows += row(classDefOffset) {
            directive(".source")
            string(dex.stringRef(sourceIdx))
        }
    }

    val interfaces = try {
        DexFileParser.parseTypeList(buffer, classDef.interfacesOff)
    } catch (_: Throwable) {
        emptyList()
    }
    if (interfaces.isNotEmpty()) {
        rows += blankRow()
        rows += commentRow("# interfaces")
        for (typeIdx in interfaces) {
            rows += row(null) {
                directive(".implements")
                type(dex.typeRef(typeIdx))
            }
        }
    }
}

private fun appendFields(
    dex: DexFile,
    header: String,
    fields: List<DexEncodedField>,
    rows: MutableList<DexSmaliRow>,
) {
    if (fields.isEmpty()) return
    rows += blankRow()
    rows += commentRow(header)
    for (field in fields) {
        val fieldId = dex.fieldIds.getOrNull(field.fieldIdx)
        rows += row(null) {
            directive(".field")
            flags(renderDexFieldAccessFlags(field.accessFlags))
            if (fieldId == null) {
                plain("<field@${field.fieldIdx}>")
            } else {
                identifier(dex.stringRef(fieldId.nameIdx))
                punctuation(":")
                type(dex.typeRef(fieldId.typeIdx))
            }
        }
    }
}

private fun appendMethods(
    dex: DexFile,
    buffer: CommonByteBuffer,
    header: String,
    methods: List<DexEncodedMethod>,
    rows: MutableList<DexSmaliRow>,
) {
    if (methods.isEmpty()) return
    rows += blankRow()
    rows += commentRow(header)
    for (method in methods) {
        rows += blankRow()
        appendMethod(dex, buffer, method, rows)
    }
}

private fun appendMethod(
    dex: DexFile,
    buffer: CommonByteBuffer,
    method: DexEncodedMethod,
    rows: MutableList<DexSmaliRow>,
) {
    val methodId = dex.methodIds.getOrNull(method.methodIdx)
    val codeOffset = method.codeOff.takeIf { it != 0L }
    rows += row(codeOffset) {
        directive(".method")
        flags(renderDexMethodAccessFlags(method.accessFlags))
        if (methodId == null) {
            plain("<meth@${method.methodIdx}>")
        } else {
            func(dex.stringRef(methodId.nameIdx) + dex.protoDescriptor(methodId.protoIdx))
        }
    }

    val code = try {
        DexFileParser.parseCodeItem(buffer, method.codeOff)
    } catch (e: Throwable) {
        rows += errorRow("code_item at 0x${method.codeOff.toString(16)} is unreadable: ${e.message}")
        null
    }

    if (code == null) {
        val abstractOrNative = method.accessFlags and
            (DexAccessFlags.ACC_ABSTRACT or DexAccessFlags.ACC_NATIVE) != 0
        if (method.codeOff == 0L) {
            rows += commentRow(if (abstractOrNative) "    # no code (abstract or native)" else "    # no code")
        }
    } else {
        rows += row(codeOffset) {
            indent()
            directive(".registers")
            number(code.registersSize.toString())
            plain("  ")
            comment("# ins ${code.insSize}, outs ${code.outsSize}")
        }
        appendInstructions(dex, code, rows)
        appendTries(dex, code, rows)
    }
    rows += row(null) { keyword(".end method") }
}

private fun appendInstructions(dex: DexFile, code: DexCodeItem, rows: MutableList<DexSmaliRow>) {
    // Payload targets are relative to the switch that jumps to them, so the
    // referring instruction has to be known before payloads can be rendered.
    val payloadBase = HashMap<Int, Int>()
    for (insn in code.instructions) {
        val target = insn.operands.branchTarget ?: continue
        if (insn.opcode == DexOpcode.PACKED_SWITCH ||
            insn.opcode == DexOpcode.SPARSE_SWITCH ||
            insn.opcode == DexOpcode.FILL_ARRAY_DATA
        ) {
            payloadBase[target] = insn.address
        }
    }

    for (insn in code.instructions) {
        val payload = insn.operands.payload
        if (payload != null) {
            appendPayload(insn, payload, payloadBase[insn.address] ?: insn.address, code, rows)
        } else {
            rows += instructionRow(dex, code, insn)
        }
    }
}

private fun instructionRow(dex: DexFile, code: DexCodeItem, insn: DexInstruction): DexSmaliRow =
    row(insn.fileOffset(code)) {
        indent()
        address(insn.address)
        keyword(insn.opcode.mnemonic)
        appendOperands(dex, code, insn)
    }

private fun RowBuilder.appendOperands(dex: DexFile, code: DexCodeItem, insn: DexInstruction) {
    val operands = insn.operands
    val bracketed = insn.opcode.indexKind == DexIndexKind.METHOD ||
        insn.opcode.indexKind == DexIndexKind.METHOD_AND_PROTO ||
        insn.opcode.indexKind == DexIndexKind.CALL_SITE ||
        insn.opcode == DexOpcode.FILLED_NEW_ARRAY ||
        insn.opcode == DexOpcode.FILLED_NEW_ARRAY_RANGE

    // The first operand is separated from the mnemonic by a space, the rest by commas.
    var written = false
    fun separator() {
        if (written) punctuation(", ") else plain(" ")
        written = true
    }

    if (operands.registers.isNotEmpty()) {
        separator()
        if (bracketed) punctuation("{")
        if (bracketed && operands.isRange && operands.registers.size > 1) {
            register(operands.registers.first(), code)
            plain(" .. ")
            register(operands.registers.last(), code)
        } else {
            operands.registers.forEachIndexed { i, reg ->
                if (i > 0) punctuation(", ")
                register(reg, code)
            }
        }
        if (bracketed) punctuation("}")
    }

    operands.literal?.let { literal ->
        separator()
        number(formatLiteral(literal))
    }
    operands.branchTarget?.let { target ->
        separator()
        label(target)
    }
    operands.index?.let { index ->
        separator()
        appendIndex(dex, insn.opcode.indexKind, index)
    }
    operands.secondaryIndex?.let { index ->
        punctuation(", ")
        type(dex.protoDescriptor(index))
    }
}

private fun RowBuilder.appendIndex(dex: DexFile, kind: DexIndexKind, index: Int) {
    when (kind) {
        DexIndexKind.STRING -> string(dex.stringRef(index))
        DexIndexKind.TYPE -> type(dex.typeRef(index))
        DexIndexKind.FIELD -> identifier(dex.fieldRef(index))
        DexIndexKind.METHOD, DexIndexKind.METHOD_AND_PROTO -> func(dex.methodRef(index))
        DexIndexKind.PROTO -> type(dex.protoDescriptor(index))
        DexIndexKind.METHOD_HANDLE -> plain("method_handle@$index")
        DexIndexKind.CALL_SITE -> plain("call_site@$index")
        DexIndexKind.NONE, DexIndexKind.UNKNOWN -> number(index.toString())
    }
}

private fun appendPayload(
    insn: DexInstruction,
    payload: DexPayload,
    base: Int,
    code: DexCodeItem,
    rows: MutableList<DexSmaliRow>,
) {
    val offset = insn.fileOffset(code)
    when (payload) {
        is DexPayload.PackedSwitch -> {
            rows += row(offset) {
                indent()
                address(insn.address)
                directive(".packed-switch")
                number(formatLiteral(payload.firstKey.toLong()))
            }
            payload.targets.forEachIndexed { i, target ->
                rows += row(offset) {
                    indent()
                    plain("        ")
                    number(formatLiteral((payload.firstKey + i).toLong()))
                    plain(" -> ")
                    label(base + target)
                }
            }
            rows += row(offset) {
                indent()
                keyword(".end packed-switch")
            }
        }
        is DexPayload.SparseSwitch -> {
            rows += row(offset) {
                indent()
                address(insn.address)
                keyword(".sparse-switch")
            }
            payload.keys.forEachIndexed { i, key ->
                rows += row(offset) {
                    indent()
                    plain("        ")
                    number(formatLiteral(key.toLong()))
                    plain(" -> ")
                    label(base + (payload.targets.getOrNull(i) ?: 0))
                }
            }
            rows += row(offset) {
                indent()
                keyword(".end sparse-switch")
            }
        }
        is DexPayload.FillArrayData -> {
            rows += row(offset) {
                indent()
                address(insn.address)
                directive(".array-data")
                number(payload.elementWidth.toString())
                plain("  ")
                comment("# ${payload.size} elements")
            }
            val shown = payload.size.coerceAtMost(PAYLOAD_ELEMENTS_SHOWN)
            for (i in 0 until shown) {
                val value = payload.elementAt(i)
                rows += row(offset) {
                    indent()
                    plain("        ")
                    number(formatLiteral(value))
                }
            }
            if (payload.size > shown) {
                rows += row(offset) {
                    indent()
                    plain("        ")
                    comment("# ${payload.size - shown} more elements")
                }
            }
            rows += row(offset) {
                indent()
                keyword(".end array-data")
            }
        }
    }
}

/** Elements of a `fill-array-data-payload` rendered before the listing is elided. */
private const val PAYLOAD_ELEMENTS_SHOWN = 64

private fun DexPayload.FillArrayData.elementAt(index: Int): Long {
    var value = 0L
    for (byteIndex in 0 until elementWidth) {
        val at = index * elementWidth + byteIndex
        if (at >= data.size) break
        value = value or ((data[at].toLong() and 0xFF) shl (byteIndex * 8))
    }
    // Sign-extend from the element's own width, so a byte array of -1s reads as -1.
    val shift = 64 - elementWidth * 8
    return if (shift in 1..63) (value shl shift) shr shift else value
}

private fun appendTries(dex: DexFile, code: DexCodeItem, rows: MutableList<DexSmaliRow>) {
    if (code.tries.isEmpty()) return
    for (tryItem in code.tries) {
        val handler = code.handlerFor(tryItem)
        if (handler == null) {
            rows += errorRow("    unresolved catch handler at 0x${tryItem.handlerOff.toString(16)}")
            continue
        }
        for (typeHandler in handler.typeHandlers) {
            rows += tryRow(tryItem, dex.typeRef(typeHandler.typeIdx), typeHandler.addr)
        }
        handler.catchAllAddr?.let { rows += tryRow(tryItem, null, it) }
    }
}

private fun tryRow(tryItem: DexTry, type: String?, handlerAddr: Int): DexSmaliRow = row(null) {
    indent()
    directive(if (type == null) ".catchall" else ".catch")
    if (type != null) {
        type(type)
        plain(" ")
    }
    punctuation("{")
    addressRef(tryItem.startAddr)
    plain(" .. ")
    addressRef(tryItem.endAddr)
    punctuation("}")
    plain(" -> ")
    label(handlerAddr)
}

/** Absolute file offset of an instruction's first code unit. */
private fun DexInstruction.fileOffset(code: DexCodeItem): Long =
    code.insnsFileOffset.toLong() + address.toLong() * 2

// ---------------------------------------------------------------------------
// Row construction
// ---------------------------------------------------------------------------

/**
 * Builds one row's tokens. Every row starts with a fixed-width gutter — the
 * file offset when there is one, blanks otherwise — so hosts that render the
 * gutter as a clickable prefix keep their columns aligned.
 */
private class RowBuilder(fileOffset: Long?) {
    val tokens = ArrayList<RenderToken>()

    init {
        if (fileOffset != null) {
            tokens += RenderToken(formatFileOffset(fileOffset), RenderTokenKind.OFFSET_ANNOTATION, fileOffset)
        } else {
            tokens += RenderToken(" ".repeat(FILE_OFFSET_WIDTH), RenderTokenKind.WHITESPACE)
        }
        tokens += RenderToken("  ", RenderTokenKind.WHITESPACE)
    }

    fun indent() = plain("    ")
    fun plain(text: String) = add(text, RenderTokenKind.PUNCTUATION)
    fun punctuation(text: String) = add(text, RenderTokenKind.PUNCTUATION)
    fun keyword(text: String) = add(text, RenderTokenKind.KEYWORD)
    fun identifier(text: String) = add(text, RenderTokenKind.IDENTIFIER)
    fun number(text: String) = add(text, RenderTokenKind.NUMBER)
    fun comment(text: String) = add(text, RenderTokenKind.COMMENT)
    fun type(text: String) = add(text, RenderTokenKind.TYPE_REF)
    fun func(text: String) = add(text, RenderTokenKind.FUNC_REF)
    fun error(text: String) = add(text, RenderTokenKind.ERROR)

    fun string(value: String) = add("\"${escape(value)}\"", RenderTokenKind.STRING)

    fun register(register: Int, code: DexCodeItem?) =
        add(registerName(register, code), RenderTokenKind.LOCAL_REF)

    /** A jump destination, in the `:0012` form smali uses for labels. */
    fun label(address: Int) = add(":" + formatAddress(address), RenderTokenKind.LABEL_REF)

    /** A code-unit address that is a bound rather than a destination. */
    fun addressRef(address: Int) = add(formatAddress(address), RenderTokenKind.LABEL_REF)

    /** Directive or mnemonic followed by a separating space. */
    fun directive(text: String) {
        keyword(text)
        plain(" ")
    }

    /** Access-flag words, if any, followed by a separating space. */
    fun flags(text: String) {
        if (text.isEmpty()) return
        keyword(text)
        plain(" ")
    }

    /** Code-unit address prefix of an instruction line. */
    fun address(address: Int) {
        add(formatAddress(address), RenderTokenKind.OFFSET_ANNOTATION)
        plain(": ")
    }

    private fun add(text: String, kind: RenderTokenKind) {
        tokens += RenderToken(text, kind)
    }
}

private fun row(fileOffset: Long?, build: RowBuilder.() -> Unit): DexSmaliRow {
    val builder = RowBuilder(fileOffset)
    builder.build()
    return DexSmaliRow(fileOffset, builder.tokens)
}

private fun blankRow(): DexSmaliRow = DexSmaliRow(null, listOf(RenderToken("", RenderTokenKind.WHITESPACE)))

private fun commentRow(text: String): DexSmaliRow = row(null) { comment(text) }

private fun errorRow(text: String): DexSmaliRow = row(null) { error(text) }

/** Smali register naming: incoming arguments are `pN`, everything else `vN`. */
private fun registerName(register: Int, code: DexCodeItem?): String {
    val firstParameter = code?.firstParameterRegister
    return if (firstParameter != null && firstParameter in 0..register) {
        "p${register - firstParameter}"
    } else {
        "v$register"
    }
}

private const val FILE_OFFSET_WIDTH = 8

private fun formatFileOffset(offset: Long): String = "0x" + offset.toString(16).padStart(6, '0')

private fun formatAddress(address: Int): String = address.toString(16).padStart(4, '0')

/** Literals print as signed hex, the way baksmali and dexdump render them. */
private fun formatLiteral(value: Long): String = when {
    value < 0 -> "-0x" + (-value).toString(16)
    else -> "0x" + value.toString(16)
}

private fun escape(value: String): String = buildString(value.length + 2) {
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
        }
    }
}

// ---------------------------------------------------------------------------
// Chunk tree
// ---------------------------------------------------------------------------

private class DexSmaliChunkTree(classes: List<DexSmaliClass>) : ChunkTree {

    private val chunks: List<BinaryChunk> = buildList(classes.size * 2) {
        for (cls in classes) {
            add(
                BinaryChunk.SectionHeader(
                    id = dexSmaliClassHeaderId(cls),
                    byteRange = cls.byteRange,
                    estimatedLines = 1,
                    kind = DexSmaliSectionKinds.CLASS,
                    elementCount = 1,
                    totalSize = CLASS_DEF_ITEM_SIZE,
                ),
            )
            add(
                BinaryChunk.Element(
                    id = dexSmaliClassBodyId(cls.index),
                    byteRange = cls.byteRange,
                    estimatedLines = 1,
                    kind = DexSmaliSectionKinds.CLASS_BODY,
                    indexInSection = cls.index,
                    displayName = cls.descriptor,
                ),
            )
        }
    }

    /** `class_def_item` start offsets, ascending — the binary-search key. */
    private val classStarts = LongArray(classes.size) { classes[it].byteRange.first }

    private val indexById: Map<ChunkId, Int> =
        HashMap<ChunkId, Int>(chunks.size).apply {
            chunks.forEachIndexed { index, chunk -> put(chunk.id, index) }
        }

    override val totalChunks: Int get() = chunks.size

    override fun chunkAt(index: Int): BinaryChunk = chunks[index]

    override fun chunkForId(id: ChunkId): Int? = indexById[id]

    override fun chunkContainingByte(offset: Long): Int? {
        // A DEX can hold tens of thousands of classes, so this stays a binary
        // search rather than the linear scan smaller views can afford.
        val classIndex = classStarts.lastIndexAtOrBefore(offset)
        if (classIndex < 0) return null
        val header = chunks[classIndex * 2]
        if (offset !in header.byteRange) return null
        // Navigate to the body, which is what the reader wants to see.
        return classIndex * 2 + 1
    }
}

/**
 * Index of the last ascending entry that is `<= offset`, or `-1` when every
 * entry is greater. Hand-rolled because array `binarySearch` is JVM-only.
 */
private fun LongArray.lastIndexAtOrBefore(offset: Long): Int {
    var low = 0
    var high = size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid] <= offset) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

