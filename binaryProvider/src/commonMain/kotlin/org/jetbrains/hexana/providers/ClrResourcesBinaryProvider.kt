package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.pe.clr.ClrResourceKind
import org.jetbrains.hexana.providers.pe.clr.ClrResourcesFile
import org.jetbrains.hexana.providers.pe.clr.ClrResourcesParser

/**
 * Binary provider for .NET `.resources` files (`ResourceWriter` output, as embedded in assemblies
 * and satellite assemblies). Extends [GenericBinaryProvider] so the hex view keeps working, and
 * parses the resource set once for all panels.
 */
class ClrResourcesBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /** The resource set, or `null` when the header is unreadable. */
    val parsedResources: ClrResourcesFile? by lazy { ClrResourcesParser.parse(bytes) }

    /** String values, which are what a resource file mostly holds. */
    override val strings: List<BinaryStringRecord> by lazy {
        val resources = parsedResources ?: return@lazy emptyList()
        resources.resources.filter { it.kind == ClrResourceKind.String }.mapIndexed { index, resource ->
            BinaryStringRecord(value = resource.stringValue.orEmpty(), offset = resource.dataRange?.offset ?: -1, size = resource.dataRange?.size ?: -1, index = index)
        }
    }

    override val structureRanges: List<StructureRange> by lazy {
        val resources = parsedResources ?: return@lazy emptyList()
        val total = bytes.size
        listOfNotNull(
            structureRangeOrNull(resources.headerRange.offset.toLong(), resources.headerRange.size.toLong(), "Header", total, tabName = "Header"),
            resources.nameSectionRange?.let { structureRangeOrNull(it.offset.toLong(), it.size.toLong(), "Names", total, tabName = "Resources") },
            resources.dataSectionRange?.let { structureRangeOrNull(it.offset.toLong(), it.size.toLong(), "Data", total, tabName = "Resources") },
        )
    }

    companion object {
        /** `.resources` files start with the ResourceManager magic `0xBEEFCACE`. */
        fun isResourcesFile(buffer: CommonByteBuffer): Boolean = ClrResourcesParser.isResources(buffer)
    }
}
