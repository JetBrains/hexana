package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.protobuf.RawProtoField
import org.jetbrains.hexana.protobuf.RawWireDecoder

/**
 * Binary provider for an opaque Protobuf wire-encoded message decoded without a schema.
 * Distinct from [ProtobufBinaryProvider] (which handles FileDescriptorSet binaries): this
 * one is never auto-detected — it is created only via the explicit "Open As Protobuf
 * Message" action. Extends [GenericBinaryProvider] so the hex view works out of the box,
 * and adds a [decodedFields] lazy property that runs the fault-tolerant [RawWireDecoder]
 * once and caches the result for all panels.
 */
class ProtobufMessageBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /**
     * Lazily decoded raw wire fields. [RawWireDecoder] is fault-tolerant and never throws;
     * the surrounding guard is defensive only.
     */
    val decodedFields: List<RawProtoField> by lazy {
        try {
            RawWireDecoder.decode(bytes.position(0))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * One range per outermost field. Submessage fields are left out — they are nested
     * inside their parent's range, and the hex view tints top-level regions only.
     *
     * This walks the wire format directly instead of filtering [decodedFields] to
     * depth 0: the two produce the same regions, but the full decode also classifies
     * and formats every nested field, which costs two orders of magnitude more on a
     * multi-megabyte message for information no region needs.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val total = bytes.size
        RawWireDecoder.topLevelFieldRanges(bytes.position(0)).mapNotNull { field ->
            structureRangeOrNull(
                offset = field.offset.toLong(),
                size = field.size.toLong(),
                label = "#${field.fieldNumber} ${field.wireType}",
                fileSize = total,
                tabName = "Protobuf Message",
            )
        }
    }
}
