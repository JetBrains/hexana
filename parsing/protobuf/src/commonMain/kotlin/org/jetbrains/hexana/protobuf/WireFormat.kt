package org.jetbrains.hexana.protobuf

import org.jetbrains.hexana.io.CommonByteBuffer

// Wire type constants (protobuf binary format spec).
const val WIRETYPE_VARINT = 0
const val WIRETYPE_I64 = 1
const val WIRETYPE_LEN = 2
const val WIRETYPE_I32 = 5

/** Extracts the field number from a decoded tag value. */
fun fieldNumber(tag: Long): Int = (tag ushr 3).toInt()

/** Extracts the wire type from a decoded tag value. */
fun wireType(tag: Long): Int = (tag and 0x7L).toInt()

/**
 * Reads a protobuf length-delimited string from the buffer.
 * Reads the varint byte-length, bounds-checks, then decodes UTF-8.
 */
fun CommonByteBuffer.readProtoString(): String {
    val len = getULEB128().toInt()
    require(len >= 0 && len <= remaining()) { "String length $len out of range (remaining=${remaining()})" }
    val bytes = ByteArray(len)
    if (len > 0) get(bytes, 0, len)
    return bytes.decodeToString()
}

/**
 * Skips one field value for the given [wireType].
 * Wire types 3 (SGROUP) and 4 (EGROUP) are rejected as they must not appear
 * in modern protobuf files.
 */
fun CommonByteBuffer.skipField(wireType: Int) {
    when (wireType) {
        WIRETYPE_VARINT -> getULEB128()
        WIRETYPE_I64 -> position(position() + 8)
        WIRETYPE_LEN -> {
            val n = getULEB128().toInt()
            require(n >= 0 && n <= remaining()) { "LEN field size $n out of range (remaining=${remaining()})" }
            position(position() + n)
        }
        WIRETYPE_I32 -> position(position() + 4)
        else -> error("Unsupported wire type $wireType (SGROUP/EGROUP are not allowed)")
    }
}

/**
 * Reads a LEN-delimited sub-message as a zero-copy slice, advancing this
 * buffer's cursor past the sub-message bytes.
 *
 * @param depth current nesting depth; must be less than [MAX_PROTO_DEPTH].
 */
fun CommonByteBuffer.readLenSlice(depth: Int = 0): CommonByteBuffer {
    require(depth < MAX_PROTO_DEPTH) { "Protobuf nesting depth exceeds $MAX_PROTO_DEPTH" }
    val len = getULEB128().toInt()
    require(len >= 0 && len <= remaining()) { "LEN slice size $len out of range (remaining=${remaining()})" }
    val sub = slice(position(), len)
    position(position() + len)
    return sub
}

/** Maximum allowed nesting depth for protobuf messages. */
const val MAX_PROTO_DEPTH = 16
