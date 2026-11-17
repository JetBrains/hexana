package org.jetbrains.hexana.mcp.model.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.*

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

/**
 * Serializes [ByteArray] as a base64-encoded JSON string.
 *
 * TODO(hexana-72): this is a compatibility shim, not a long-term MCP contract. The old Jackson-based
 *  `list_data` path emitted `DataEntry.data: ByteArray` through `objectMapper.writeValueAsString(...)`,
 *  and Jackson's default `byte[]` encoding is base64. kotlinx.serialization's default is a JSON array
 *  of bytes, so we pin the old wire shape explicitly here.
 *
 *  Regression guard: `idea-plugin/src/test/kotlin/org/jetbrains/hexana/mcp/direct/HexanaMcpDataToolTest.kt`
 *  asserts `returned[0].data == "SGVsbG8="` for `(data (i32.const 0) "Hello")`.
 *
 *  When we redesign the `list_data` payload, drop this serializer together with the direct tests and
 *  any MCP clients that depend on the base64 shape — it should be one explicit behaviour-change step.
 */
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
