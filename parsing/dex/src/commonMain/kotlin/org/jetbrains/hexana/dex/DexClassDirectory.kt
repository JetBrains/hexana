package org.jetbrains.hexana.dex

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * The class definitions of a DEX file, with type names decoded on demand; see
 * [DexFileParser.parseClassDirectory]. Each type name is decoded at most once.
 */
class DexClassDirectory internal constructor(
    private val buffer: CommonByteBuffer,
    private val stringDataOffsets: IntArray,
    private val typeDescriptorIdx: IntArray,
    val classDefs: List<DexClassDef>,
) {
    private val typeNames = arrayOfNulls<String>(typeDescriptorIdx.size)

    /** The descriptor of type [index], as [DexFile.typeName]; `null` for an index out of range. */
    fun typeName(index: Int): String? {
        if (index !in typeDescriptorIdx.indices) return null
        typeNames[index]?.let { return it }
        val stringIdx = typeDescriptorIdx[index]
        if (stringIdx !in stringDataOffsets.indices) return null
        buffer.position(stringDataOffsets[stringIdx])
        return readMutf8String(buffer).also { typeNames[index] = it }
    }
}
