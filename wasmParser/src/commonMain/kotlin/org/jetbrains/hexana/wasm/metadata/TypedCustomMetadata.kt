package org.jetbrains.hexana.wasm.metadata

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.boundedDuplicate
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom

enum class CustomMetadataStatus {
    ABSENT,
    DECODED,
    PARTIAL,
    MALFORMED,
}

enum class CustomMetadataSectionStatus {
    DECODED,
    MALFORMED,
}

data class MetadataDecodeDiagnostic(
    val message: String,
    val byteOffset: Int?,
)

data class TypedCustomMetadata(
    val producers: ProducersMetadata,
    val targetFeatures: TargetFeaturesMetadata,
)

data class ProducersMetadata(
    val status: CustomMetadataStatus,
    val sections: List<ProducerMetadataSection>,
)

data class ProducerMetadataSection(
    val occurrence: Int,
    val offset: Int,
    val status: CustomMetadataSectionStatus,
    val fields: List<ProducerField>?,
    val diagnostics: List<MetadataDecodeDiagnostic>,
)

data class ProducerField(
    val fieldIndex: Int,
    val field: String,
    val values: List<ProducerValue>,
)

data class ProducerValue(
    val name: String,
    val version: String,
)

data class TargetFeaturesMetadata(
    val status: CustomMetadataStatus,
    val sections: List<TargetFeaturesMetadataSection>,
)

data class TargetFeaturesMetadataSection(
    val occurrence: Int,
    val offset: Int,
    val status: CustomMetadataSectionStatus,
    val features: List<TargetFeature>?,
    val diagnostics: List<MetadataDecodeDiagnostic>,
)

data class TargetFeature(
    val featureIndex: Int,
    val name: String,
    val state: TargetFeatureState,
)

enum class TargetFeatureState {
    ENABLED,
    DISABLED,
}

/** Decodes typed custom metadata without changing the raw custom-section inventory. */
fun BaseModule.decodeTypedCustomMetadata(bytes: CommonByteBuffer): TypedCustomMetadata =
    TypedCustomMetadata(
        producers = decodeProducers(bytes),
        targetFeatures = decodeTargetFeatures(bytes),
    )

private fun BaseModule.decodeProducers(bytes: CommonByteBuffer): ProducersMetadata {
    val sections = customSections.named("producers").mapIndexed { occurrence, section ->
        try {
            ProducerMetadataSection(
                occurrence = occurrence,
                offset = section.sectionOffset,
                status = CustomMetadataSectionStatus.DECODED,
                fields = section.decodePayload(bytes) { reader -> reader.readProducerFields() },
                diagnostics = emptyList(),
            )
        } catch (error: MetadataDecodingException) {
            ProducerMetadataSection(
                occurrence = occurrence,
                offset = section.sectionOffset,
                status = CustomMetadataSectionStatus.MALFORMED,
                fields = null,
                diagnostics = listOf(error.toDiagnostic()),
            )
        } catch (error: Exception) {
            ProducerMetadataSection(
                occurrence = occurrence,
                offset = section.sectionOffset,
                status = CustomMetadataSectionStatus.MALFORMED,
                fields = null,
                diagnostics = listOf(MetadataDecodeDiagnostic(error.message ?: "metadata decode failed", section.contentOffset)),
            )
        }
    }
    return ProducersMetadata(sections.metadataStatus(), sections)
}

private fun BaseModule.decodeTargetFeatures(bytes: CommonByteBuffer): TargetFeaturesMetadata {
    val sections = customSections.named("target_features").mapIndexed { occurrence, section ->
        try {
            TargetFeaturesMetadataSection(
                occurrence = occurrence,
                offset = section.sectionOffset,
                status = CustomMetadataSectionStatus.DECODED,
                features = section.decodePayload(bytes) { reader -> reader.readTargetFeatures() },
                diagnostics = emptyList(),
            )
        } catch (error: MetadataDecodingException) {
            TargetFeaturesMetadataSection(
                occurrence = occurrence,
                offset = section.sectionOffset,
                status = CustomMetadataSectionStatus.MALFORMED,
                features = null,
                diagnostics = listOf(error.toDiagnostic()),
            )
        } catch (error: Exception) {
            TargetFeaturesMetadataSection(
                occurrence = occurrence,
                offset = section.sectionOffset,
                status = CustomMetadataSectionStatus.MALFORMED,
                features = null,
                diagnostics = listOf(MetadataDecodeDiagnostic(error.message ?: "metadata decode failed", section.contentOffset)),
            )
        }
    }
    return TargetFeaturesMetadata(sections.metadataStatus(), sections)
}

private fun List<WasmSectionCustom>.named(name: String): List<WasmSectionCustom> = filter { it.rawName == name }

private fun List<Any>.metadataStatus(): CustomMetadataStatus {
    if (isEmpty()) return CustomMetadataStatus.ABSENT
    val malformed = count {
        when (it) {
            is ProducerMetadataSection -> it.status == CustomMetadataSectionStatus.MALFORMED
            is TargetFeaturesMetadataSection -> it.status == CustomMetadataSectionStatus.MALFORMED
            else -> error("Unsupported metadata section")
        }
    }
    return when (malformed) {
        0 -> CustomMetadataStatus.DECODED
        size -> CustomMetadataStatus.MALFORMED
        else -> CustomMetadataStatus.PARTIAL
    }
}

private fun <T> WasmSectionCustom.decodePayload(
    bytes: CommonByteBuffer,
    decoder: (MetadataReader) -> T,
): T {
    val contentEnd = contentOffset.toLong() + contentSize.toLong()
    if (contentEnd > bytes.limit().toLong()) {
        throw MetadataDecodingException("Custom-section payload exceeds available bytes", contentOffset)
    }
    val view = bytes.duplicate().position(contentOffset).boundedDuplicate(contentEnd.toInt())
    val reader = MetadataReader(view)
    val value = decoder(reader)
    if (view.hasRemaining()) {
        throw MetadataDecodingException("Trailing bytes in custom-section payload", view.position())
    }
    return value
}

private class MetadataReader(private val bytes: CommonByteBuffer) {
    fun readProducerFields(): List<ProducerField> = readVector("producer fields") { fieldIndex ->
        ProducerField(
            fieldIndex = fieldIndex,
            field = readString("producer field name"),
            values = readVector("producer values") {
                ProducerValue(
                    name = readString("producer name"),
                    version = readString("producer version"),
                )
            },
        )
    }

    fun readTargetFeatures(): List<TargetFeature> = readVector("target features") { featureIndex ->
        val prefixOffset = bytes.position()
        if (!bytes.hasRemaining()) fail("Truncated target feature prefix", prefixOffset)
        val state = when ((bytes.get().toInt() and 0xFF).toChar()) {
            '+' -> TargetFeatureState.ENABLED
            '-' -> TargetFeatureState.DISABLED
            else -> fail("Target feature has invalid prefix", prefixOffset)
        }
        TargetFeature(featureIndex, readString("target feature name"), state)
    }

    private fun <T> readVector(label: String, entry: (Int) -> T): List<T> {
        val countOffset = bytes.position()
        val count = readUnsignedInt("$label count")
        if (count > bytes.remaining()) {
            fail("$label count $count exceeds remaining payload bytes", countOffset)
        }
        val values = mutableListOf<T>()
        repeat(count) { index ->
            val entryOffset = bytes.position()
            if (!bytes.hasRemaining()) fail("Truncated $label entry", entryOffset)
            values += entry(index)
        }
        return values
    }

    private fun readString(label: String): String {
        val lengthOffset = bytes.position()
        val length = readUnsignedInt("$label length")
        if (length > bytes.remaining()) {
            fail("$label length $length exceeds remaining payload bytes", lengthOffset)
        }
        val start = bytes.position()
        val encoded = ByteArray(length)
        bytes.get(encoded, 0, length)
        return try {
            encoded.decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            fail("$label is not valid UTF-8", start)
        }
    }

    private fun readUnsignedInt(label: String): Int {
        val start = bytes.position()
        var result = 0L
        repeat(5) { index ->
            if (!bytes.hasRemaining()) fail("Truncated $label", start)
            val byte = bytes.get().toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl (index * 7))
            if (byte and 0x80 == 0) {
                if (index == 4 && byte > 0x0F) fail("$label exceeds u32 range", start)
                if (result > Int.MAX_VALUE.toLong()) fail("$label exceeds supported size", start)
                return result.toInt()
            }
        }
        fail("$label exceeds u32 encoding length", start)
    }

    private fun fail(message: String, offset: Int): Nothing = throw MetadataDecodingException(message, offset)
}

private class MetadataDecodingException(message: String, val offset: Int?) : Exception(message) {
    fun toDiagnostic(): MetadataDecodeDiagnostic = MetadataDecodeDiagnostic(message.orEmpty(), offset)
}
