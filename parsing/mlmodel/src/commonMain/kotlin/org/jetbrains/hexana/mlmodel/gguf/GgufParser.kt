package org.jetbrains.hexana.mlmodel.gguf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.ModelDiagnostic
import org.jetbrains.hexana.mlmodel.elementCountOf

/**
 * Parser for GGUF, the llama.cpp / ggml model container
 * (https://github.com/ggml-org/ggml/blob/master/docs/gguf.md).
 *
 * Layout: `"GGUF"`, a `u32` version, the tensor and metadata counts, the metadata
 * key/value pairs, the tensor info records, padding to `general.alignment`, then the
 * tensor data. Only the parts before the tensor data are read, so the buffer may be
 * just the head of a larger file as long as [parse] is told the real file size.
 *
 * Versions 1–3 are accepted. Version 1 stored lengths and counts as `u32` where later
 * versions use `u64`. Big-endian files (a ggml convention for s390x) are recognised by
 * the byte-swapped version word.
 *
 * Every count and length comes from the file, so each is checked against the bytes that
 * remain before anything is allocated for it. A truncated or damaged file yields what
 * was read before the damage plus a diagnostic, never an exception.
 */
object GgufParser {
    /** Default `general.alignment`. */
    const val DEFAULT_ALIGNMENT: Int = 32

    /** Array elements kept per metadata array; the rest are skipped but still counted. */
    const val MAX_ARRAY_ELEMENTS_KEPT: Int = 1 shl 20

    private const val MAX_ARRAY_NESTING = 4
    private const val MAX_DIMS = 8

    /** `"GGUF"` followed by a version this parser knows, in either byte order. */
    fun isGguf(buffer: CommonByteBuffer): Boolean {
        if (buffer.size < 8) return false
        if (buffer[0] != 'G'.code.toByte() || buffer[1] != 'G'.code.toByte() ||
            buffer[2] != 'U'.code.toByte() || buffer[3] != 'F'.code.toByte()
        ) {
            return false
        }
        return versionByteOrder(buffer) != null
    }

    /**
     * Parses [buffer]. [fileSize] is the size of the whole file, which is larger than the
     * buffer when only a head was loaded; tensor data ranges are validated against it.
     * Returns `null` when the header itself is not GGUF.
     */
    fun parse(buffer: CommonByteBuffer, fileSize: Long = buffer.size.toLong()): GgufFile? {
        if (!isGguf(buffer)) return null
        val bigEndian = versionByteOrder(buffer) ?: return null
        val reader = Reader(buffer, bigEndian)
        val diagnostics = mutableListOf<ModelDiagnostic>()

        reader.position = 4L
        val version = reader.u32().toInt()
        val wide = version >= 2
        val headerEnd: Long
        val declaredTensorCount: Long
        val declaredMetadataCount: Long
        try {
            declaredTensorCount = reader.count(wide)
            declaredMetadataCount = reader.count(wide)
            headerEnd = reader.position
        } catch (e: GgufReadException) {
            return GgufFile(
                version = version, bigEndian = bigEndian, declaredTensorCount = 0, declaredMetadataCount = 0,
                metadata = emptyList(), tensors = emptyList(), alignment = DEFAULT_ALIGNMENT,
                headerRange = ModelByteRange(0, buffer.size.toLong()), metadataRange = null, tensorInfoRange = null,
                tensorDataOffset = null, fileSize = fileSize,
                diagnostics = listOf(ModelDiagnostic("gguf.truncated", "Header is truncated: ${e.message}")),
            )
        }
        val headerRange = ModelByteRange(0, headerEnd)

        // Metadata: each entry needs at least a key length, a type and a one-byte value.
        val metadata = mutableListOf<GgufMetadataEntry>()
        val metadataStart = reader.position
        var complete = true
        val minEntrySize = (if (wide) 8 else 4) + 4 + 1
        if (declaredMetadataCount > reader.remaining / minEntrySize) {
            diagnostics += ModelDiagnostic(
                "gguf.metadata-count",
                "Header declares $declaredMetadataCount metadata entries, more than the file can hold",
                headerRange,
            )
        }
        try {
            for (i in 0 until declaredMetadataCount) {
                val start = reader.position
                val key = reader.string(wide)
                val typeId = reader.u32().toInt()
                val type = GgufValueType.fromId(typeId)
                    ?: throw GgufReadException("metadata entry \"$key\" has unknown value type $typeId")
                val value = reader.value(type, wide, depth = 0)
                metadata += GgufMetadataEntry(key, type, value, ModelByteRange(start, reader.position - start))
            }
        } catch (e: GgufReadException) {
            complete = false
            diagnostics += ModelDiagnostic(
                "gguf.metadata",
                "Metadata stops after ${metadata.size} of $declaredMetadataCount entries: ${e.message}",
                ModelByteRange(reader.position.coerceAtMost(buffer.size.toLong()), 0),
            )
        }
        val metadataRange = ModelByteRange(metadataStart, reader.position - metadataStart).takeIf { it.size > 0 }

        val alignment = alignmentOf(metadata, diagnostics)

        // Tensor infos: name length, n_dims, at least one dim, type and offset.
        val tensors = mutableListOf<GgufTensorInfo>()
        val tensorInfoStart = reader.position
        if (complete) {
            val minInfoSize = (if (wide) 8 else 4) + 4 + (if (wide) 8 else 4) + 4 + 8
            if (declaredTensorCount > reader.remaining / minInfoSize) {
                diagnostics += ModelDiagnostic(
                    "gguf.tensor-count",
                    "Header declares $declaredTensorCount tensors, more than the file can hold",
                    headerRange,
                )
            }
            try {
                for (i in 0 until declaredTensorCount) {
                    val start = reader.position
                    val name = reader.string(wide)
                    val nDims = reader.u32()
                    if (nDims > MAX_DIMS) throw GgufReadException("tensor \"$name\" declares $nDims dimensions")
                    val dims = List(nDims.toInt()) { reader.count(wide) }
                    val typeId = reader.u32().toInt()
                    val relativeOffset = reader.u64()
                    val type = GgmlType.fromId(typeId)
                    val elements = elementCountOf(dims)
                    tensors += GgufTensorInfo(
                        index = i.toInt(),
                        name = name,
                        dims = dims,
                        typeId = typeId,
                        type = type,
                        relativeOffset = relativeOffset,
                        elementCount = elements,
                        dataSize = elements?.let { type?.byteSize(it) },
                        dataRange = null,
                        infoRange = ModelByteRange(start, reader.position - start),
                    )
                }
            } catch (e: GgufReadException) {
                complete = false
                diagnostics += ModelDiagnostic(
                    "gguf.tensor-info",
                    "Tensor infos stop after ${tensors.size} of $declaredTensorCount: ${e.message}",
                    ModelByteRange(reader.position.coerceAtMost(buffer.size.toLong()), 0),
                )
            }
        }
        val tensorInfoRange = ModelByteRange(tensorInfoStart, reader.position - tensorInfoStart).takeIf { it.size > 0 }

        // The data section starts at the next alignment boundary after the infos; tensor
        // offsets are relative to it. Unknown if the infos were cut short.
        val tensorDataOffset = if (complete) alignUp(reader.position, alignment) else null
        val placedTensors = if (tensorDataOffset == null) {
            tensors
        } else {
            tensors.map { tensor -> place(tensor, tensorDataOffset, alignment, fileSize, diagnostics) }
        }

        return GgufFile(
            version = version,
            bigEndian = bigEndian,
            declaredTensorCount = declaredTensorCount,
            declaredMetadataCount = declaredMetadataCount,
            metadata = metadata,
            tensors = placedTensors,
            alignment = alignment,
            headerRange = headerRange,
            metadataRange = metadataRange,
            tensorInfoRange = tensorInfoRange,
            tensorDataOffset = tensorDataOffset,
            fileSize = fileSize,
            diagnostics = diagnostics,
        )
    }

    /** `false` for little-endian, `true` for big-endian, `null` when neither reading is a known version. */
    private fun versionByteOrder(buffer: CommonByteBuffer): Boolean? {
        val b = IntArray(4) { buffer[4 + it].toInt() and 0xFF }
        val le = b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
        val be = b[3] or (b[2] shl 8) or (b[1] shl 16) or (b[0] shl 24)
        return when {
            le in 1..3 -> false
            be in 1..3 -> true
            else -> null
        }
    }

    private fun alignmentOf(metadata: List<GgufMetadataEntry>, diagnostics: MutableList<ModelDiagnostic>): Int {
        val entry = metadata.firstOrNull { it.key == "general.alignment" } ?: return DEFAULT_ALIGNMENT
        val value = entry.value.integer
        if (value == null || value <= 0 || value > (1 shl 30) || (value and (value - 1)) != 0L) {
            diagnostics += ModelDiagnostic(
                "gguf.alignment",
                "general.alignment ${entry.value.display()} is not a power of two; using $DEFAULT_ALIGNMENT",
                entry.range,
            )
            return DEFAULT_ALIGNMENT
        }
        return value.toInt()
    }

    private fun place(
        tensor: GgufTensorInfo,
        dataOffset: Long,
        alignment: Int,
        fileSize: Long,
        diagnostics: MutableList<ModelDiagnostic>,
    ): GgufTensorInfo {
        if (tensor.relativeOffset < 0 || tensor.relativeOffset > Long.MAX_VALUE - dataOffset) {
            diagnostics += ModelDiagnostic("gguf.tensor-offset", "Tensor \"${tensor.name}\" has an invalid offset", tensor.infoRange)
            return tensor
        }
        if (tensor.relativeOffset % alignment != 0L) {
            diagnostics += ModelDiagnostic(
                "gguf.tensor-alignment",
                "Tensor \"${tensor.name}\" offset ${tensor.relativeOffset} is not a multiple of the alignment $alignment",
                tensor.infoRange,
            )
        }
        val start = dataOffset + tensor.relativeOffset
        val size = tensor.dataSize ?: return tensor
        if (start > fileSize || size > fileSize - start) {
            diagnostics += ModelDiagnostic(
                "gguf.tensor-bounds",
                "Tensor \"${tensor.name}\" data ($size bytes at $start) extends past the end of the file",
                tensor.infoRange,
            )
            return tensor
        }
        return tensor.copy(dataRange = ModelByteRange(start, size))
    }

    private fun alignUp(value: Long, alignment: Int): Long {
        val remainder = value % alignment
        return if (remainder == 0L) value else value + (alignment - remainder)
    }

    private class Reader(private val buffer: CommonByteBuffer, private val bigEndian: Boolean) {
        var position: Long = 0
        private val limit: Long = buffer.size.toLong()

        val remaining: Long get() = limit - position

        private fun require(bytes: Long) {
            if (bytes < 0 || bytes > remaining) throw GgufReadException("needs $bytes bytes at offset $position, file has $remaining left")
        }

        private fun unsigned(width: Int): Long {
            require(width.toLong())
            val base = position.toInt()
            var value = 0L
            for (i in 0 until width) {
                val byte = buffer[base + i].toLong() and 0xFF
                value = if (bigEndian) (value shl 8) or byte else value or (byte shl (8 * i))
            }
            position += width
            return value
        }

        fun u8(): Long = unsigned(1)
        fun u16(): Long = unsigned(2)
        fun u32(): Long = unsigned(4)
        fun u64(): Long = unsigned(8)

        /** A `u64` count or length (`u32` in version 1). Rejects values that do not fit a non-negative [Long]. */
        fun count(wide: Boolean): Long {
            val at = position
            val value = if (wide) u64() else u32()
            if (value < 0) throw GgufReadException("count ${value.toULong()} at offset $at is out of range")
            return value
        }

        fun string(wide: Boolean): String {
            val length = count(wide)
            require(length)
            val bytes = ByteArray(length.toInt())
            buffer.duplicate().position(position.toInt()).get(bytes, 0, bytes.size)
            position += length
            return bytes.decodeToString()
        }

        private fun skipString(wide: Boolean) {
            val length = count(wide)
            require(length)
            position += length
        }

        fun value(type: GgufValueType, wide: Boolean, depth: Int): GgufValue = when (type) {
            GgufValueType.UINT8 -> GgufValue(type, integer = u8())
            GgufValueType.INT8 -> GgufValue(type, integer = u8().toByte().toLong())
            GgufValueType.UINT16 -> GgufValue(type, integer = u16())
            GgufValueType.INT16 -> GgufValue(type, integer = u16().toShort().toLong())
            GgufValueType.UINT32 -> GgufValue(type, integer = u32())
            GgufValueType.INT32 -> GgufValue(type, integer = u32().toInt().toLong())
            GgufValueType.UINT64, GgufValueType.INT64 -> GgufValue(type, integer = u64())
            GgufValueType.FLOAT32 -> GgufValue(type, real = Float.fromBits(u32().toInt()).toDouble())
            GgufValueType.FLOAT64 -> GgufValue(type, real = Double.fromBits(u64()))
            GgufValueType.BOOL -> GgufValue(type, bool = u8() != 0L)
            GgufValueType.STRING -> GgufValue(type, string = string(wide))
            GgufValueType.ARRAY -> array(wide, depth)
        }

        private fun array(wide: Boolean, depth: Int): GgufValue {
            if (depth >= MAX_ARRAY_NESTING) throw GgufReadException("arrays nested deeper than $MAX_ARRAY_NESTING")
            val elementTypeId = u32().toInt()
            val elementType = GgufValueType.fromId(elementTypeId)
                ?: throw GgufReadException("array has unknown element type $elementTypeId")
            val count = count(wide)
            val fixedWidth = fixedWidthOf(elementType)
            if (fixedWidth != null) {
                if (count > remaining / fixedWidth) throw GgufReadException("array of $count $elementType elements exceeds the file")
            } else if (count > remaining / (if (wide) 8 else 4)) {
                throw GgufReadException("array of $count $elementType elements exceeds the file")
            }
            val kept = minOf(count, MAX_ARRAY_ELEMENTS_KEPT.toLong()).toInt()
            val elements = ArrayList<GgufValue>(kept)
            for (i in 0 until kept) elements += value(elementType, wide, depth + 1)
            val skipped = count - kept
            if (skipped > 0) {
                when {
                    fixedWidth != null -> { require(skipped * fixedWidth); position += skipped * fixedWidth }
                    elementType == GgufValueType.STRING -> for (i in 0 until skipped) skipString(wide)
                    else -> for (i in 0 until skipped) value(elementType, wide, depth + 1)
                }
            }
            return GgufValue(GgufValueType.ARRAY, elementType = elementType, count = count, elements = elements)
        }

        private fun fixedWidthOf(type: GgufValueType): Long? = when (type) {
            GgufValueType.UINT8, GgufValueType.INT8, GgufValueType.BOOL -> 1
            GgufValueType.UINT16, GgufValueType.INT16 -> 2
            GgufValueType.UINT32, GgufValueType.INT32, GgufValueType.FLOAT32 -> 4
            GgufValueType.UINT64, GgufValueType.INT64, GgufValueType.FLOAT64 -> 8
            GgufValueType.STRING, GgufValueType.ARRAY -> null
        }
    }
}

private class GgufReadException(message: String) : Exception(message)
