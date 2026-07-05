package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.protobuf.FileDescriptorSetParser
import org.jetbrains.hexana.protobuf.ProtoDescriptorSet

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

    companion object {
        /** Delegates to [FileDescriptorSetParser.isFileDescriptorSet]. */
        fun isFileDescriptorSet(buf: CommonByteBuffer): Boolean =
            FileDescriptorSetParser.isFileDescriptorSet(buf)
    }
}
