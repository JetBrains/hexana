package org.jetbrains.hexana.mlmodel.safetensors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.ModelDiagnostic
import org.jetbrains.hexana.mlmodel.elementCountOf

/**
 * Parser for the Hugging Face safetensors format (https://github.com/huggingface/safetensors).
 *
 * Layout: a little-endian `u64` header length `N`, `N` bytes of UTF-8 JSON (optionally
 * padded with spaces), then the tensor byte buffer. The JSON maps each tensor name to
 * `{"dtype", "shape", "data_offsets": [begin, end]}`, with offsets relative to the byte
 * buffer; the optional `__metadata__` key holds a string-to-string map.
 *
 * There is no magic number. [isSafetensors] accepts a file only when the length is
 * plausible, the header opens with `{` and its last non-space byte is `}`, which in
 * practice no other binary format produces by accident.
 */
object SafetensorsParser {
    /** The format's own upper bound on the header (the `safetensors` crate rejects larger ones). */
    const val MAX_HEADER_SIZE: Long = 100L * 1024 * 1024

    private const val METADATA_KEY = "__metadata__"

    private val json = Json { isLenient = false }

    /**
     * Whether [buffer] starts like a safetensors file. [fileSize] is the size of the whole
     * file when [buffer] holds only its head; the header itself must lie inside [buffer].
     */
    fun isSafetensors(buffer: CommonByteBuffer, fileSize: Long = buffer.size.toLong()): Boolean {
        val n = headerLength(buffer) ?: return false
        if (n < 2 || n > MAX_HEADER_SIZE || n > fileSize - 8 || n > buffer.size.toLong() - 8) return false
        if (buffer[8] != '{'.code.toByte()) return false
        var last = 8 + n.toInt() - 1
        while (last > 8 && buffer[last] == ' '.code.toByte()) last--
        if (buffer[last] != '}'.code.toByte()) return false
        // The first token after `{` is a quoted key or the closing brace.
        var next = 9
        while (next < last && buffer[next].toInt().toChar().isWhitespace()) next++
        return buffer[next] == '"'.code.toByte() || next == last
    }

    /**
     * Parses [buffer]; `null` when it is not a safetensors file at all. A header that is
     * not valid JSON, or entries that are malformed, produce diagnostics and whatever
     * tensors could be read.
     */
    fun parse(buffer: CommonByteBuffer, fileSize: Long = buffer.size.toLong()): SafetensorsFile? {
        if (!isSafetensors(buffer, fileSize)) return null
        val n = headerLength(buffer) ?: return null
        val diagnostics = mutableListOf<ModelDiagnostic>()
        val lengthRange = ModelByteRange(0, 8)
        val headerRange = ModelByteRange(8, n)
        val dataOffset = 8 + n
        val dataSize = fileSize - dataOffset

        val bytes = ByteArray(n.toInt())
        buffer.duplicate().position(8).get(bytes, 0, bytes.size)
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            diagnostics += ModelDiagnostic("safetensors.json", "Header is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}", headerRange)
            null
        }
        if (root == null) {
            if (diagnostics.isEmpty()) diagnostics += ModelDiagnostic("safetensors.json", "Header is not a JSON object", headerRange)
            return SafetensorsFile(n, lengthRange, headerRange, emptyList(), emptyList(), dataOffset, fileSize, diagnostics)
        }

        val metadata = mutableListOf<SafetensorsMetadataEntry>()
        val tensors = mutableListOf<SafetensorsTensor>()
        for ((name, element) in root) {
            if (name == METADATA_KEY) {
                val map = element as? JsonObject
                if (map == null) {
                    diagnostics += ModelDiagnostic("safetensors.metadata", "__metadata__ is not an object", headerRange)
                    continue
                }
                for ((key, value) in map) {
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (text == null) {
                        diagnostics += ModelDiagnostic("safetensors.metadata", "__metadata__ value \"$key\" is not a string", headerRange)
                    }
                    metadata += SafetensorsMetadataEntry(key, text ?: value.toString())
                }
                continue
            }
            val tensor = tensorOf(tensors.size, name, element, dataOffset, dataSize, diagnostics, headerRange)
            if (tensor != null) tensors += tensor
        }

        val ordered = tensors.sortedWith(compareBy<SafetensorsTensor> { it.begin }.thenBy { it.name })
            .mapIndexed { index, tensor -> tensor.copy(index = index) }
        checkLayout(ordered, dataSize, diagnostics, headerRange)
        return SafetensorsFile(n, lengthRange, headerRange, metadata, ordered, dataOffset, fileSize, diagnostics)
    }

    private fun headerLength(buffer: CommonByteBuffer): Long? {
        if (buffer.size < 10) return null
        var value = 0L
        for (i in 0 until 8) value = value or ((buffer[i].toLong() and 0xFF) shl (8 * i))
        return value.takeIf { it >= 0 }
    }

    private fun tensorOf(
        index: Int,
        name: String,
        element: kotlinx.serialization.json.JsonElement,
        dataOffset: Long,
        dataSize: Long,
        diagnostics: MutableList<ModelDiagnostic>,
        headerRange: ModelByteRange,
    ): SafetensorsTensor? {
        fun reject(reason: String): SafetensorsTensor? {
            diagnostics += ModelDiagnostic("safetensors.tensor", "Tensor \"$name\" $reason", headerRange)
            return null
        }
        val entry = element as? JsonObject ?: return reject("is not an object")
        val dtype = (entry["dtype"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return reject("has no dtype")
        val shape = (entry["shape"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull ?: -1L }
            ?: return reject("has no shape")
        if (shape.any { it < 0 }) return reject("has an invalid shape $shape")
        val offsets = (entry["data_offsets"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull ?: -1L }
        if (offsets == null || offsets.size != 2) return reject("has no [begin, end] data_offsets")
        val (begin, end) = offsets
        if (begin < 0 || end < begin) return reject("has invalid data_offsets [$begin, $end]")

        val elements = elementCountOf(shape)
        val bits = safetensorsDtypeBits(dtype)
        if (elements != null && bits != null && elements <= Long.MAX_VALUE / bits) {
            val expected = (elements * bits + 7) / 8
            if (expected != end - begin) {
                diagnostics += ModelDiagnostic(
                    "safetensors.tensor-size",
                    "Tensor \"$name\" is $dtype$shape ($expected bytes) but data_offsets span ${end - begin} bytes",
                    headerRange,
                )
            }
        }
        val inBounds = end <= dataSize
        if (!inBounds) {
            diagnostics += ModelDiagnostic(
                "safetensors.tensor-bounds",
                "Tensor \"$name\" data_offsets [$begin, $end] extend past the end of the file",
                headerRange,
            )
        }
        return SafetensorsTensor(
            index = index,
            name = name,
            dtype = dtype,
            shape = shape,
            begin = begin,
            end = end,
            elementCount = elements,
            dataRange = if (inBounds) ModelByteRange(dataOffset + begin, end - begin) else null,
        )
    }

    /** The format requires the tensors to tile the byte buffer exactly: no gaps, no overlaps. */
    private fun checkLayout(
        tensors: List<SafetensorsTensor>,
        dataSize: Long,
        diagnostics: MutableList<ModelDiagnostic>,
        headerRange: ModelByteRange,
    ) {
        var expected = 0L
        for (tensor in tensors) {
            when {
                tensor.begin < expected -> diagnostics += ModelDiagnostic(
                    "safetensors.overlap",
                    "Tensor \"${tensor.name}\" overlaps the previous tensor at $expected",
                    headerRange,
                )
                tensor.begin > expected -> diagnostics += ModelDiagnostic(
                    "safetensors.gap",
                    "${tensor.begin - expected} unused bytes before tensor \"${tensor.name}\"",
                    headerRange,
                )
            }
            expected = maxOf(expected, tensor.end)
        }
        if (tensors.isNotEmpty() && expected < dataSize) {
            diagnostics += ModelDiagnostic(
                "safetensors.trailing",
                "${dataSize - expected} bytes after the last tensor are not part of any tensor",
                headerRange,
            )
        }
    }
}
