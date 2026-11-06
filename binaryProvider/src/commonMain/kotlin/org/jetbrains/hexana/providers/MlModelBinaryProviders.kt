package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.gguf.GgufFile
import org.jetbrains.hexana.mlmodel.gguf.GgufParser
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsFile
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsParser
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode

/**
 * Binary provider for GGUF model files (llama.cpp / ggml). Extends [GenericBinaryProvider]
 * so the hex view keeps working; the header, metadata and tensor infos are parsed once,
 * lazily, for all panels. Tensor data is never read.
 */
class GgufBinaryProvider(
    sharedBytes: CommonByteBuffer,
    /** Size of the whole file; larger than [sharedBytes] when only its head was loaded. */
    override val fileSize: Long = sharedBytes.size.toLong(),
) : GenericBinaryProvider(sharedBytes) {

    /** The parsed file, or `null` when the header is not GGUF. */
    val parsedGguf: GgufFile? by lazy { GgufParser.parse(bytes, fileSize) }

    override val structureRanges: List<StructureRange> by lazy {
        val file = parsedGguf ?: return@lazy emptyList()
        val total = bytes.size
        val dataRange = file.tensorDataOffset?.let { ModelByteRange(it, (total - it).coerceAtLeast(0)) }
        listOfNotNull(
            file.headerRange.toStructureRange("Header", total, tabName = GGUF_HEADER_TAB),
            file.metadataRange?.toStructureRange("Metadata", total, tabName = GGUF_METADATA_TAB),
            file.tensorInfoRange?.toStructureRange("Tensor Infos", total, tabName = MODEL_TENSORS_TAB),
            dataRange?.toStructureRange("Tensor Data", total, tabName = MODEL_TENSORS_TAB),
        )
    }

    override val sizeAnalysis: BinarySizeNode? by lazy {
        // Int-sized nodes cannot describe the whole of a file larger than the loaded head.
        if (fileSize > bytes.size) return@lazy null
        val file = parsedGguf ?: return@lazy null
        val headerEnd = file.tensorDataOffset ?: return@lazy null
        modelSizeTree(
            totalSize = bytes.size,
            preamble = listOfNotNull(
                file.metadataRange?.let { "Metadata" to it },
                file.tensorInfoRange?.let { "Tensor Infos" to it },
            ),
            headerEnd = headerEnd,
            tensors = file.tensors.mapNotNull { tensor -> tensor.dataRange?.let { tensor.name to it } },
        )
    }

    companion object {
        /** `"GGUF"` and a known version, in either byte order. */
        fun isGgufFile(buffer: CommonByteBuffer): Boolean = GgufParser.isGguf(buffer)
    }
}

/**
 * Binary provider for Hugging Face safetensors files. Extends [GenericBinaryProvider] so the
 * hex view keeps working; the JSON header is parsed once, lazily. Tensor data is never read.
 */
class SafetensorsBinaryProvider(
    sharedBytes: CommonByteBuffer,
    /** Size of the whole file; larger than [sharedBytes] when only its head was loaded. */
    override val fileSize: Long = sharedBytes.size.toLong(),
) : GenericBinaryProvider(sharedBytes) {

    /** The parsed header, or `null` when the file does not start like safetensors. */
    val parsedSafetensors: SafetensorsFile? by lazy { SafetensorsParser.parse(bytes, fileSize) }

    override val structureRanges: List<StructureRange> by lazy {
        val file = parsedSafetensors ?: return@lazy emptyList()
        val total = bytes.size
        listOfNotNull(
            file.lengthRange.toStructureRange("Header Length", total, tabName = SAFETENSORS_HEADER_TAB),
            file.headerRange.toStructureRange("Header (JSON)", total, tabName = SAFETENSORS_HEADER_TAB),
            ModelByteRange(file.dataOffset, (total - file.dataOffset).coerceAtLeast(0))
                .toStructureRange("Tensor Data", total, tabName = MODEL_TENSORS_TAB),
        )
    }

    override val sizeAnalysis: BinarySizeNode? by lazy {
        if (fileSize > bytes.size) return@lazy null
        val file = parsedSafetensors ?: return@lazy null
        modelSizeTree(
            totalSize = bytes.size,
            preamble = listOf("Header (JSON)" to file.headerRange),
            headerEnd = file.dataOffset,
            tensors = file.tensors.mapNotNull { tensor -> tensor.dataRange?.let { tensor.name to it } },
        )
    }

    companion object {
        /** A plausible `u64` header length followed by a JSON object; see [SafetensorsParser.isSafetensors]. */
        fun isSafetensorsFile(buffer: CommonByteBuffer, fileSize: Long = buffer.size.toLong()): Boolean =
            SafetensorsParser.isSafetensors(buffer, fileSize)
    }
}

/** Tab names shared with the table tabs in `plugins-shared`, so structure ranges can switch to them. */
const val GGUF_HEADER_TAB: String = "Header"
const val GGUF_METADATA_TAB: String = "Metadata"
const val SAFETENSORS_HEADER_TAB: String = "Header"
const val MODEL_TENSORS_TAB: String = "Tensors"

private fun ModelByteRange.toStructureRange(label: String, fileSize: Int, tabName: String): StructureRange? =
    structureRangeOrNull(offset, size, label, fileSize, tabName)

/**
 * Size tree for a model file: the header parts, then the tensors grouped by layer
 * (`blk.12.attn_q.weight` and `model.layers.12.mlp.up_proj.weight` both group under the
 * name up to the layer number), so the treemap shows where the bytes go per block.
 */
private fun modelSizeTree(
    totalSize: Int,
    preamble: List<Pair<String, ModelByteRange>>,
    headerEnd: Long,
    tensors: List<Pair<String, ModelByteRange>>,
): BinarySizeNode? {
    fun fits(range: ModelByteRange) = range.offset <= totalSize && range.size <= totalSize - range.offset
    val children = mutableListOf<BinarySizeNode>()
    children += BinarySizeNode("Header", minOf(headerEnd, totalSize.toLong()).toInt(), 0,
        preamble.filter { fits(it.second) }.map { (name, range) -> BinarySizeNode(name, range.size.toInt(), range.offset.toInt()) })
    val groups = tensors.filter { fits(it.second) }.groupBy { (name, _) -> tensorGroupOf(name) }
    val tensorNodes = groups.map { (group, members) ->
        if (members.size == 1) {
            val (name, range) = members.single()
            BinarySizeNode(name, range.size.toInt(), range.offset.toInt())
        } else {
            val nodes = members.map { (name, range) -> BinarySizeNode(name, range.size.toInt(), range.offset.toInt()) }
            BinarySizeNode(group, nodes.sumOf { it.size }, nodes.minOf { it.offset }, nodes.sortedByDescending { it.size })
        }
    }
    if (tensorNodes.isNotEmpty()) {
        children += BinarySizeNode("Tensors", tensorNodes.sumOf { it.size }, tensorNodes.minOf { it.offset },
            tensorNodes.sortedByDescending { it.size })
    }
    return BinarySizeNode("Model", totalSize, 0, children)
}

/** `blk.12.attn_q.weight` → `blk.12`; `token_embd.weight` → `token_embd`. */
internal fun tensorGroupOf(name: String): String {
    val parts = name.split('.')
    val layer = parts.indexOfFirst { part -> part.isNotEmpty() && part.all(Char::isDigit) }
    return if (layer >= 0) parts.subList(0, layer + 1).joinToString(".") else parts.first()
}
