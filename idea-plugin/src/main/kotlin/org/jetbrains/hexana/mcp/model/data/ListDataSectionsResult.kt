package org.jetbrains.hexana.mcp.model.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

@Serializable
data class ListDataSectionsResult(
    val from: Int,
    val to: Int,
    val returned: List<DataEntryResult>,
    @SerialName("total_segments")
    val totalSegments: Int,
    val truncate: Boolean,
    val nextOffset: Int?
)

@Serializable
data class DataEntryResult(
    val kind: Int,
    val index: Int,
    val offset: Long,
    @Serializable(with = Base64ByteArraySerializer::class)
    val data: ByteArray,
    val memoryIndex: Long? = null,
    val expression: String? = null
)

object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.getDecoder().decode(decoder.decodeString())
    }
}
