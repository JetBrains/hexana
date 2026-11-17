package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.pe.byteOrNull

/** Generic parameter names in scope while decoding a signature; missing names render as `!n` / `!!n`. */
internal class ClrGenericContext(
    val typeParameters: List<String> = emptyList(),
    val methodParameters: List<String> = emptyList(),
) {
    companion object {
        val Empty: ClrGenericContext = ClrGenericContext()
    }
}

/**
 * Renders ECMA-335 II.23.2 signature blobs as C#-like text, e.g.
 * `List<string> Parse(string text, int count)`.
 *
 * [typeName] resolves a `TypeDefOrRefOrSpecEncoded` value (II.23.2.8) to a display name; the
 * decoder passes itself back through it so TypeSpec rows can be rendered recursively.
 */
internal class ClrSignatureDecoder(
    private val bytes: CommonByteBuffer,
    private val typeName: (encoded: Int, context: ClrGenericContext, depth: Int) -> String,
) {
    fun methodSignature(
        blob: NativeByteRange?,
        name: String,
        parameterNames: List<String?>,
        context: ClrGenericContext,
    ): String = decodeOrInvalid(blob) { cursor ->
        val callingConvention = cursor.u8()
        val genericCount = if (callingConvention and GENERIC != 0) cursor.compressed() else 0
        if (genericCount > MAX_GENERIC_PARAMETERS) throw InvalidSignature()
        val parameterCount = cursor.count()
        val returnType = type(cursor, context, 0)
        val parameters = parameters(cursor, parameterCount, context)
            .mapIndexed { index, parameterType ->
                parameterNames.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { "$parameterType $it" } ?: parameterType
            }
        val generics = if (genericCount > 0) {
            (0 until genericCount).joinToString(", ", "<", ">") { context.methodParameters.getOrNull(it) ?: "!!$it" }
        } else {
            ""
        }
        "$returnType $name$generics(${parameters.joinToString(", ")})"
    }

    /** True when [blob] is a field signature (II.23.2.4) rather than a method signature. */
    fun isFieldSignature(blob: NativeByteRange?): Boolean =
        blob != null && blob.size > 0 && bytes.byteOrNull(blob.offset) == FIELD

    fun fieldType(blob: NativeByteRange?, context: ClrGenericContext): String = decodeOrInvalid(blob) { cursor ->
        if (cursor.u8() != FIELD) throw InvalidSignature()
        type(cursor, context, 0)
    }

    fun typeSpec(blob: NativeByteRange?, context: ClrGenericContext, depth: Int): String =
        decodeOrInvalid(blob) { cursor -> type(cursor, context, depth) }

    /** Type arguments of a MethodSpec instantiation blob (II.23.2.15), or null when malformed. */
    fun genericInstantiation(blob: NativeByteRange?, context: ClrGenericContext): List<String>? {
        if (blob == null || blob.size == 0 || bytes.byteOrNull(blob.offset) != GENERIC_INST) return null
        val types = ArrayList<String>()
        val result = decodeOrInvalid(blob) { cursor ->
            cursor.u8()
            repeat(cursor.count()) { types += type(cursor, context, 0) }
            ""
        }
        return if (result == INVALID) null else types
    }

    /** Types of a `LOCAL_SIG` (II.23.2.6), or null when [blob] is not a local variable signature. */
    fun localTypes(blob: NativeByteRange?, context: ClrGenericContext): List<String>? {
        if (blob == null || blob.size == 0 || bytes.byteOrNull(blob.offset) != LOCAL_SIG) return null
        val types = ArrayList<String>()
        val result = decodeOrInvalid(blob) { cursor ->
            cursor.u8()
            repeat(cursor.count()) { types += type(cursor, context, 0) }
            ""
        }
        return if (result == INVALID) null else types
    }

    private fun parameters(cursor: Cursor, count: Int, context: ClrGenericContext): List<String> = buildList {
        repeat(count) {
            if (cursor.peek() == SENTINEL) {
                cursor.u8()
                add("...")
            }
            add(type(cursor, context, 0))
        }
    }

    private fun type(cursor: Cursor, context: ClrGenericContext, depth: Int): String {
        if (depth > MAX_DEPTH || --remainingNodes < 0) throw InvalidSignature()
        return when (cursor.u8()) {
            0x01 -> "void"
            0x02 -> "bool"
            0x03 -> "char"
            0x04 -> "sbyte"
            0x05 -> "byte"
            0x06 -> "short"
            0x07 -> "ushort"
            0x08 -> "int"
            0x09 -> "uint"
            0x0A -> "long"
            0x0B -> "ulong"
            0x0C -> "float"
            0x0D -> "double"
            0x0E -> "string"
            0x0F -> type(cursor, context, depth + 1) + "*"
            0x10 -> "ref " + type(cursor, context, depth + 1)
            0x11, 0x12 -> typeName(cursor.compressed(), context, depth + 1)
            0x13 -> cursor.compressed().let { context.typeParameters.getOrNull(it) ?: "!$it" }
            0x14 -> {
                val element = type(cursor, context, depth + 1)
                val rank = cursor.compressed()
                if (rank > MAX_ARRAY_RANK) throw InvalidSignature()
                repeat(cursor.count()) { cursor.compressed() }
                repeat(cursor.count()) { cursor.compressed() }
                element + "[" + ",".repeat((rank - 1).coerceAtLeast(0)) + "]"
            }
            0x15 -> {
                cursor.u8() // CLASS or VALUETYPE
                val generic = typeName(cursor.compressed(), context, depth + 1)
                val arguments = List(cursor.count()) { type(cursor, context, depth + 1) }
                "$generic<${arguments.joinToString(", ")}>"
            }
            0x16 -> "typedref"
            0x18 -> "nint"
            0x19 -> "nuint"
            0x1B -> {
                val callingConvention = cursor.u8()
                if (callingConvention and GENERIC != 0) cursor.compressed()
                val parameterCount = cursor.count()
                val returnType = type(cursor, context, depth + 1)
                val parameters = parameters(cursor, parameterCount, context)
                "delegate*<${(parameters + returnType).joinToString(", ")}>"
            }
            0x1C -> "object"
            0x1D -> type(cursor, context, depth + 1) + "[]"
            0x1E -> cursor.compressed().let { context.methodParameters.getOrNull(it) ?: "!!$it" }
            // Custom modifiers (modreq/modopt) do not change the displayed type.
            0x1F, 0x20 -> {
                cursor.compressed()
                type(cursor, context, depth + 1)
            }
            0x45 -> type(cursor, context, depth + 1) + " pinned"
            else -> throw InvalidSignature()
        }
    }

    // TypeSpec rows re-enter the decoder through [typeName]; the node budget is shared by the
    // whole outermost decode so self-referencing or deeply branching TypeSpecs stay bounded.
    private var activeDecodes = 0
    private var remainingNodes = 0

    private inline fun decodeOrInvalid(blob: NativeByteRange?, block: (Cursor) -> String): String {
        if (blob == null) return INVALID
        if (activeDecodes == 0) remainingNodes = MAX_NODES
        activeDecodes++
        return try {
            block(Cursor(bytes, blob.offset, blob.offset + blob.size))
        } catch (_: InvalidSignature) {
            INVALID
        } finally {
            activeDecodes--
        }
    }

    private class InvalidSignature : RuntimeException()

    private class Cursor(private val bytes: CommonByteBuffer, var position: Int, private val end: Int) {
        fun peek(): Int = if (position < end) bytes.byteOrNull(position) ?: throw InvalidSignature() else throw InvalidSignature()

        fun u8(): Int = peek().also { position++ }

        /** A compressed element count; each element takes at least one byte, so larger counts are corrupt. */
        fun count(): Int = compressed().also { if (it > end - position) throw InvalidSignature() }

        fun compressed(): Int {
            val first = u8()
            return when {
                first and 0x80 == 0 -> first
                first and 0xC0 == 0x80 -> ((first and 0x3F) shl 8) or u8()
                first and 0xE0 == 0xC0 -> ((first and 0x1F) shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
                else -> throw InvalidSignature()
            }
        }
    }

    companion object {
        const val INVALID: String = "<invalid signature>"
        private const val GENERIC = 0x10
        private const val FIELD = 0x06
        private const val LOCAL_SIG = 0x07
        private const val GENERIC_INST = 0x0A
        private const val SENTINEL = 0x41
        private const val MAX_DEPTH = 48
        private const val MAX_NODES = 4096
        private const val MAX_GENERIC_PARAMETERS = 1024
        private const val MAX_ARRAY_RANK = 32
    }
}
