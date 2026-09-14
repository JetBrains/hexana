package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.protobuf.FileDescriptorSetParser
import org.jetbrains.hexana.protobuf.ProtoDescriptorSet
import org.jetbrains.hexana.protobuf.RawWireDecoder

/** `FileDescriptorSet.file`, the repeated field holding every descriptor in the set. */
private const val FILE_FIELD_NUMBER = 1

/**
 * Binary provider for Protocol Buffers FileDescriptorSet binaries (`.desc`, `.protoset`).
 * Extends [GenericBinaryProvider] so the hex view works out of the box, and adds a
 * [parsedDescriptorSet] lazy property that parses the descriptor structure once and
 * caches the result for all panels.
 */
class ProtobufBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /**
     * Lazily parsed FileDescriptorSet, or `null` when parsing fails.
     */
    val parsedDescriptorSet: ProtoDescriptorSet? by lazy {
        try {
            FileDescriptorSetParser.parse(bytes.position(0))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * One range per `FileDescriptorProto` in the set, labelled with the proto file it
     * describes.
     *
     * A `FileDescriptorSet` is a message whose only field is `repeated file = 1`, so
     * its top-level field ranges are the per-file ranges. They come back in file order,
     * the same order [ProtoDescriptorSet.files] is built in, which is what lets the
     * names be matched up by index; a count mismatch (a file the parser skipped) falls
     * back to a positional label rather than a wrong name.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val total = bytes.size
        val names = parsedDescriptorSet?.files?.map { it.name }.orEmpty()
        val fileRanges = RawWireDecoder.topLevelFieldRanges(bytes.position(0))
            .filter { it.fieldNumber == FILE_FIELD_NUMBER }
        val namesLineUp = names.size == fileRanges.size
        fileRanges.mapIndexedNotNull { index, range ->
            structureRangeOrNull(
                offset = range.offset.toLong(),
                size = range.size.toLong(),
                label = if (namesLineUp) names[index] else "file $index",
                fileSize = total,
                tabName = "Files",
            )
        }
    }

    companion object {
        /** Delegates to [FileDescriptorSetParser.isFileDescriptorSet]. */
        fun isFileDescriptorSet(buf: CommonByteBuffer): Boolean =
            FileDescriptorSetParser.isFileDescriptorSet(buf)
    }
}
