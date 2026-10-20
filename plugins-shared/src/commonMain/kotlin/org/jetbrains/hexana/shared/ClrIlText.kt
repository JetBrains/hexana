package org.jetbrains.hexana.shared

import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.pe.clr.ClrExceptionClause
import org.jetbrains.hexana.providers.pe.clr.ClrExceptionClauseKind
import org.jetbrains.hexana.providers.pe.clr.ClrIlDecoder
import org.jetbrains.hexana.providers.pe.clr.ClrIlInstruction
import org.jetbrains.hexana.providers.pe.clr.ClrIlOperand
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMethodDef
import org.jetbrains.hexana.providers.pe.clr.ClrOperandKind
import org.jetbrains.hexana.providers.pe.clr.ClrTableId
import org.jetbrains.hexana.providers.pe.clr.ClrTypeDef

// ---------------------------------------------------------------------------
// IL text generation for .NET assemblies.
//
// The CLR counterpart of DexSmaliText: a UI-free renderer that turns decoded
// metadata into tokenized rows a host can display. The cheap half
// (clrIlTypes, clrIlChunkTree, clrIlSearchIndex) reads only metadata the parser
// has already decoded; clrIlTypeRows decodes IL and resolves its tokens, and is
// meant to be called per type when that type is expanded.
//
// Token resolution goes through ClrMetadata.tokens, which is not thread-safe:
// hosts must not render two types concurrently.
// ---------------------------------------------------------------------------

/** Tab label of the IL view, shared so every host names the tab identically. */
const val CLR_IL_TAB_NAME: String = "IL"

/** Section kinds of the IL view; see [clrIlChunkTree]. */
object ClrIlSectionKinds {
    /** Foldable per-type header row. */
    val TYPE: SectionKind = SectionKind("clr.type")

    /** The type body: one element chunk rendered as many [ClrIlRow]s. */
    val TYPE_BODY: SectionKind = SectionKind("clr.type-body")
}

/**
 * A type in the IL view. [byteRange] is the type's TypeDef row, so ranges stay ordered and
 * disjoint as [ChunkTree] requires; method bodies elsewhere in the file still map back to the
 * type through [ChunkTree.chunkContainingByte].
 */
data class ClrIlType(
    /** Index into [ClrMetadata.typeDefs]. */
    val index: Int,
    /** `Ns.Outer+Box<T>`. */
    val name: String,
    /** Header words before the name, e.g. `public sealed class`. */
    val declaration: String,
    val extends: String?,
    val memberCount: Int,
    val byteRange: LongRange,
)

/** One rendered line of the IL view. */
data class ClrIlRow(
    /** Absolute file offset this line describes, or `null` for structural lines. */
    val fileOffset: Long?,
    /** Bytes the line covers starting at [fileOffset], for hosts that select a range. */
    val byteSize: Int,
    val tokens: List<RenderToken>,
) {
    /** The line as plain text, for tests, tooltips and copy-to-clipboard. */
    val text: String get() = tokens.joinToString("") { it.text }
}

/** A searchable type or method name, resolving to the type that declares it. */
data class ClrIlSearchEntry(
    val label: String,
    val detail: String?,
    /** Index into [ClrMetadata.typeDefs]; navigate via [clrIlTypeBodyId]. */
    val typeIndex: Int,
)

/** Every type definition, from metadata already decoded by the parser. */
fun clrIlTypes(metadata: ClrMetadata): List<ClrIlType> = metadata.typeDefs.mapIndexed { index, type ->
    val start = type.recordRange.offset.toLong()
    ClrIlType(
        index = index,
        name = clrTypeDisplayName(type),
        declaration = typeDeclaration(type),
        extends = type.extends,
        memberCount = metadata.fieldsByType[type.row].orEmpty().size + metadata.methodsByType[type.row].orEmpty().size,
        byteRange = start until start + type.recordRange.size,
    )
}

/** Chunk id of a type's foldable header row. */
fun clrIlTypeHeaderId(type: ClrIlType): ChunkId = ChunkId.Indexed(ClrIlSectionKinds.TYPE, type.index)

/** Chunk id of a type's body element. */
fun clrIlTypeBodyId(typeIndex: Int): ChunkId = ChunkId.Indexed(ClrIlSectionKinds.TYPE_BODY, typeIndex)

/**
 * Builds the [ChunkTree] of the IL view: per type a foldable [ClrIlSectionKinds.TYPE] header
 * followed by one [ClrIlSectionKinds.TYPE_BODY] element whose rows come from [clrIlTypeRows].
 */
fun clrIlChunkTree(metadata: ClrMetadata, types: List<ClrIlType>): ChunkTree = ClrIlChunkTree(metadata, types)

/** Type names, plus every method as `Type::name`. */
fun clrIlSearchIndex(metadata: ClrMetadata, types: List<ClrIlType>, limit: Int = 100_000): List<ClrIlSearchEntry> {
    val out = ArrayList<ClrIlSearchEntry>(types.size + metadata.methods.size)
    for (type in types) {
        if (out.size >= limit) return out
        out += ClrIlSearchEntry(type.name, detail = null, typeIndex = type.index)
    }
    for (method in metadata.methods) {
        if (out.size >= limit) return out
        val type = types.getOrNull(method.declaringTypeRow - 1) ?: continue
        out += ClrIlSearchEntry("${type.name}::${method.name}", detail = method.signature, typeIndex = type.index)
    }
    return out
}

/**
 * Renders one type as IL: its declaration, fields, and every method with its decoded body.
 * Decodes IL and resolves tokens, so this is the expensive half of the model — call it lazily,
 * per type, and never concurrently with another call over the same [metadata].
 *
 * A method whose body cannot be read degrades to an error row instead of taking the listing down.
 */
fun clrIlTypeRows(metadata: ClrMetadata, bytes: CommonByteBuffer, type: ClrIlType): List<ClrIlRow> {
    val typeDef = metadata.typeDefs.getOrNull(type.index) ?: return listOf(ilErrorRow("type #${type.index} is not in this assembly"))
    val buffer = bytes.duplicate()
    val rows = ArrayList<ClrIlRow>()
    appendTypeHeader(metadata, typeDef, type, rows)

    val fields = metadata.fieldsByType[typeDef.row].orEmpty()
    if (fields.isNotEmpty()) {
        rows += ilBlankRow()
        for (field in fields) {
            rows += ilRow(field.recordRange) {
                directive(".field")
                keywords(clrFieldModifiers(field))
                type(field.type)
                plain(" ")
                identifier(field.name)
            }
        }
    }
    for (method in metadata.methodsByType[typeDef.row].orEmpty()) {
        rows += ilBlankRow()
        appendMethod(metadata, buffer, method, rows)
    }
    return rows
}

// ---------------------------------------------------------------------------
// Type and method rendering
// ---------------------------------------------------------------------------

private fun typeDeclaration(type: ClrTypeDef): String =
    listOf(clrTypeModifiers(type), type.kind.label).filter { it.isNotEmpty() }.joinToString(" ")

private fun appendTypeHeader(metadata: ClrMetadata, typeDef: ClrTypeDef, type: ClrIlType, rows: MutableList<ClrIlRow>) {
    rows += ilRow(typeDef.recordRange) {
        directive(".class")
        keywords(type.declaration)
        type(type.name)
    }
    typeDef.extends?.let { extends ->
        rows += ilRow(null) {
            indent()
            directive("extends")
            type(extends)
        }
    }
    for (implemented in typeDef.interfaces) {
        rows += ilRow(null) {
            indent()
            directive("implements")
            type(implemented)
        }
    }
    typeDef.enclosingTypeRow?.let { metadata.typeDefs.getOrNull(it - 1) }?.let { enclosing ->
        rows += ilRow(null) {
            indent()
            comment("// nested in ${clrTypeDisplayName(enclosing)}")
        }
    }
}

private fun appendMethod(metadata: ClrMetadata, buffer: CommonByteBuffer, method: ClrMethodDef, rows: MutableList<ClrIlRow>) {
    val body = method.body
    rows += ilRow(body?.range ?: method.recordRange) {
        directive(".method")
        keywords(clrMethodModifiers(method))
        func(method.signature)
    }
    if (body == null) {
        rows += ilRow(null) {
            indent()
            comment(
                when {
                    method.rva == 0u && method.flags and METHOD_ABSTRACT != 0 -> "// abstract: no body"
                    method.rva == 0u -> "// no IL body (extern, runtime or P/Invoke)"
                    else -> "// IL body at RVA 0x${method.rva.toString(16).uppercase()} is unreadable"
                },
            )
        }
        return
    }
    rows += ilRow(null) { punctuation("{") }
    rows += ilRow(null) {
        indent()
        comment(
            "// RVA 0x${method.rva.toString(16).uppercase()}, code size ${body.codeSize}, " +
                if (body.isFat) "fat header" else "tiny header",
        )
    }
    if (metadata.entryPoint != null && metadata.header.entryPointToken == method.token) {
        rows += ilRow(null) {
            indent()
            keyword(".entrypoint")
        }
    }
    rows += ilRow(null) {
        indent()
        directive(".maxstack")
        number(body.maxStack.toString())
    }
    val locals = metadata.tokens.localVariableTypes(method)
    if (locals.isNotEmpty()) {
        rows += ilRow(null) {
            indent()
            directive(".locals")
            if (body.initLocals) directive("init")
            punctuation("(")
            locals.forEachIndexed { index, local ->
                if (index > 0) punctuation(", ")
                type(local)
                plain(" ")
                local("V_$index")
            }
            punctuation(")")
        }
    }

    val listing = ClrIlDecoder.decode(buffer, body.codeRange)
    for (instruction in listing.instructions) {
        rows += instructionRow(metadata, method, instruction)
    }
    listing.truncatedAt?.let { offset ->
        rows += ilErrorRow("    IL is truncated at ${label(offset)}: an operand runs past the end of the code")
    }
    for (clause in body.exceptionClauses) {
        rows += exceptionClauseRow(clause)
    }
    rows += ilRow(null) { punctuation("}") }
}

private fun instructionRow(metadata: ClrMetadata, method: ClrMethodDef, instruction: ClrIlInstruction): ClrIlRow =
    ilRow(instruction.range) {
        indent()
        add(label(instruction.offset), RenderTokenKind.OFFSET_ANNOTATION)
        plain(": ")
        val opcode = instruction.opcode
        if (opcode == null) {
            error(".byte 0x" + ((instruction.operand as? ClrIlOperand.Integer)?.value ?: 0).toString(16).uppercase().padStart(2, '0'))
            comment("  // not an IL opcode")
            return@ilRow
        }
        val operand = instruction.operand
        if (operand == ClrIlOperand.None) {
            keyword(opcode.mnemonic)
            return@ilRow
        }
        keyword(opcode.mnemonic.padEnd(MNEMONIC_WIDTH))
        when (operand) {
            ClrIlOperand.None -> Unit
            is ClrIlOperand.Integer -> number(operand.value.toString())
            is ClrIlOperand.Real -> number(operand.value.toString())
            is ClrIlOperand.Variable ->
                if ("loc" in opcode.mnemonic) local("V_${operand.index}") else local(operand.index.toString())
            is ClrIlOperand.Target -> labelRef(operand.offset)
            is ClrIlOperand.Switch -> {
                punctuation("(")
                operand.targets.forEachIndexed { index, target ->
                    if (index > 0) punctuation(", ")
                    labelRef(target)
                }
                punctuation(")")
            }
            is ClrIlOperand.Token -> tokenOperand(metadata, method, opcode.operand, operand.token)
        }
    }

private fun IlRowBuilder.tokenOperand(metadata: ClrMetadata, method: ClrMethodDef, kind: ClrOperandKind, token: UInt) {
    val text = metadata.tokens.describe(token, method)
    if (text == null) {
        error("<unresolved token 0x${token.toString(16).uppercase().padStart(8, '0')}>")
        return
    }
    when (kind) {
        ClrOperandKind.String -> add("\"${escapeStringLiteral(text)}\"", RenderTokenKind.STRING)
        ClrOperandKind.Method -> func(text)
        ClrOperandKind.Field -> identifier(text)
        ClrOperandKind.Token -> when ((token shr 24).toInt()) {
            ClrTableId.METHOD_DEF, ClrTableId.MEMBER_REF, ClrTableId.METHOD_SPEC -> func(text)
            ClrTableId.FIELD -> identifier(text)
            else -> type(text)
        }
        else -> type(text)
    }
}

private fun exceptionClauseRow(clause: ClrExceptionClause): ClrIlRow = ilRow(null) {
    indent()
    directive(".try")
    labelRef(clause.tryOffset)
    plain(" to ")
    labelRef(clause.tryOffset + clause.tryLength)
    plain(" ")
    keyword(clause.kind.label)
    plain(" ")
    when (clause.kind) {
        ClrExceptionClauseKind.Catch -> clause.catchType?.let {
            type(it)
            plain(" ")
        }
        ClrExceptionClauseKind.Filter -> clause.filterOffset?.let {
            labelRef(it)
            plain(" ")
        }
        else -> Unit
    }
    directive("handler")
    labelRef(clause.handlerOffset)
    plain(" to ")
    labelRef(clause.handlerOffset + clause.handlerLength)
}

private fun label(offset: Int): String = "IL_" + offset.toString(16).padStart(4, '0')

// ---------------------------------------------------------------------------
// Row construction
// ---------------------------------------------------------------------------

/**
 * Builds one row's tokens. Every row starts with a fixed-width gutter — the file offset when
 * there is one, blanks otherwise — matching the smali view so hosts can share their rendering.
 */
private class IlRowBuilder(fileOffset: Long?) {
    val tokens = ArrayList<RenderToken>()

    init {
        if (fileOffset != null) {
            tokens += RenderToken("0x" + fileOffset.toString(16).padStart(6, '0'), RenderTokenKind.OFFSET_ANNOTATION, fileOffset)
        } else {
            tokens += RenderToken(" ".repeat(IL_FILE_OFFSET_WIDTH), RenderTokenKind.WHITESPACE)
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
    fun local(text: String) = add(text, RenderTokenKind.LOCAL_REF)
    fun error(text: String) = add(text, RenderTokenKind.ERROR)
    fun labelRef(offset: Int) = add(label(offset), RenderTokenKind.LABEL_REF)

    /** Directive or keyword followed by a separating space. */
    fun directive(text: String) {
        keyword(text)
        plain(" ")
    }

    /** Modifier words, if any, followed by a separating space. */
    fun keywords(text: String) {
        if (text.isNotEmpty()) directive(text)
    }

    fun add(text: String, kind: RenderTokenKind) {
        tokens += RenderToken(text, kind)
    }
}

private inline fun ilRow(range: NativeByteRange?, build: IlRowBuilder.() -> Unit): ClrIlRow {
    val builder = IlRowBuilder(range?.offset?.toLong())
    builder.build()
    return ClrIlRow(range?.offset?.toLong(), range?.size ?: 0, builder.tokens)
}

private fun ilBlankRow(): ClrIlRow = ClrIlRow(null, 0, listOf(RenderToken("", RenderTokenKind.WHITESPACE)))

private fun ilErrorRow(text: String): ClrIlRow = ilRow(null) { error(text) }

private const val IL_FILE_OFFSET_WIDTH = 8
private const val MNEMONIC_WIDTH = 11
private const val METHOD_ABSTRACT = 0x0400

// ---------------------------------------------------------------------------
// Chunk tree
// ---------------------------------------------------------------------------

private class ClrIlChunkTree(metadata: ClrMetadata, types: List<ClrIlType>) : ChunkTree {

    private val chunks: List<BinaryChunk> = buildList(types.size * 2) {
        for (type in types) {
            add(
                BinaryChunk.SectionHeader(
                    id = clrIlTypeHeaderId(type),
                    byteRange = type.byteRange,
                    estimatedLines = 1,
                    kind = ClrIlSectionKinds.TYPE,
                    elementCount = 1,
                    totalSize = type.byteRange.last - type.byteRange.first + 1,
                ),
            )
            add(
                BinaryChunk.Element(
                    id = clrIlTypeBodyId(type.index),
                    byteRange = type.byteRange,
                    estimatedLines = 1,
                    kind = ClrIlSectionKinds.TYPE_BODY,
                    indexInSection = type.index,
                    displayName = type.name,
                ),
            )
        }
    }

    /**
     * Byte ranges that belong to a type — its TypeDef row, its Field and MethodDef rows and its
     * method bodies — sorted by start, so a hex offset anywhere in them finds the type body.
     */
    private val ownedStarts: LongArray
    private val ownedEnds: LongArray
    private val ownedTypes: IntArray

    init {
        val owned = ArrayList<Triple<Long, Long, Int>>()
        fun own(range: NativeByteRange?, typeIndex: Int) {
            if (range != null && range.size > 0 && typeIndex in types.indices) {
                owned += Triple(range.offset.toLong(), range.offset.toLong() + range.size, typeIndex)
            }
        }
        metadata.typeDefs.forEachIndexed { index, type -> own(type.recordRange, index) }
        for (field in metadata.fields) own(field.recordRange, field.declaringTypeRow - 1)
        for (method in metadata.methods) {
            own(method.recordRange, method.declaringTypeRow - 1)
            own(method.body?.range, method.declaringTypeRow - 1)
        }
        owned.sortBy { it.first }
        ownedStarts = LongArray(owned.size) { owned[it].first }
        ownedEnds = LongArray(owned.size) { owned[it].second }
        ownedTypes = IntArray(owned.size) { owned[it].third }
    }

    private val indexById: Map<ChunkId, Int> =
        HashMap<ChunkId, Int>(chunks.size).apply {
            chunks.forEachIndexed { index, chunk -> put(chunk.id, index) }
        }

    override val totalChunks: Int get() = chunks.size

    override fun chunkAt(index: Int): BinaryChunk = chunks[index]

    override fun chunkForId(id: ChunkId): Int? = indexById[id]

    override fun chunkContainingByte(offset: Long): Int? {
        val slot = ownedStarts.lastIndexAtOrBefore(offset)
        if (slot < 0 || offset >= ownedEnds[slot]) return null
        // Navigate to the body, which is what the reader wants to see.
        return indexById[clrIlTypeBodyId(ownedTypes[slot])]
    }
}
