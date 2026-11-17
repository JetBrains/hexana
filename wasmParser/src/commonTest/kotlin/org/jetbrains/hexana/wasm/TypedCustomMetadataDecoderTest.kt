package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.metadata.CustomMetadataStatus
import org.jetbrains.hexana.wasm.metadata.TargetFeatureState
import org.jetbrains.hexana.wasm.metadata.decodeTypedCustomMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedCustomMetadataDecoderTest {
    @Test
    fun `reports absent metadata when no matching custom sections exist`() {
        val bytes = wasm(customSection("other", byteArrayOf(0)))
        val metadata = parseModule(bytes).decodeTypedCustomMetadata(bytes.toCommonByteBuffer())

        assertEquals(CustomMetadataStatus.ABSENT, metadata.producers.status)
        assertTrue(metadata.producers.sections.isEmpty())
        assertEquals(CustomMetadataStatus.ABSENT, metadata.targetFeatures.status)
        assertTrue(metadata.targetFeatures.sections.isEmpty())
    }

    @Test
    fun `preserves producer occurrences fields values and versions`() {
        val first = producersPayload(
            "language" to listOf("Kotlin" to "2.4.0", "Kotlin" to "2.4.1"),
            "sdk" to listOf("WASI" to "preview2"),
            "language" to listOf("Kotlin/JS" to "2.4.1"),
        )
        val second = producersPayload("language" to listOf("Rust" to "1.91"))
        val bytes = wasm(
            customSection("producers", first),
            customSection("producers", second),
        )
        val metadata = parseModule(bytes).decodeTypedCustomMetadata(bytes.toCommonByteBuffer())

        assertEquals(CustomMetadataStatus.DECODED, metadata.producers.status)
        assertEquals(listOf(0, 1), metadata.producers.sections.map { it.occurrence })
        val firstFields = assertNotNull(metadata.producers.sections[0].fields)
        assertEquals(listOf("language", "sdk", "language"), firstFields.map { it.field })
        assertEquals(listOf(0, 1, 2), firstFields.map { it.fieldIndex })
        assertEquals(listOf("Kotlin", "Kotlin"), firstFields[0].values.map { it.name })
        assertEquals(listOf("2.4.0", "2.4.1"), firstFields[0].values.map { it.version })
        assertEquals("Rust", assertNotNull(metadata.producers.sections[1].fields)[0].values.single().name)
    }

    @Test
    fun `decodes raw target feature prefixes in encounter order including duplicates`() {
        val bytes = wasm(
            customSection("target_features", targetFeaturesPayload("+simd", "-threads", "+simd")),
            customSection("target_features", targetFeaturesPayload("-gc")),
        )
        val metadata = parseModule(bytes).decodeTypedCustomMetadata(bytes.toCommonByteBuffer())

        assertEquals(CustomMetadataStatus.DECODED, metadata.targetFeatures.status)
        assertEquals(listOf(0, 1), metadata.targetFeatures.sections.map { it.occurrence })
        val first = assertNotNull(metadata.targetFeatures.sections[0].features)
        assertEquals(listOf("simd", "threads", "simd"), first.map { it.name })
        assertEquals(
            listOf(TargetFeatureState.ENABLED, TargetFeatureState.DISABLED, TargetFeatureState.ENABLED),
            first.map { it.state },
        )
        assertEquals("gc", assertNotNull(metadata.targetFeatures.sections[1].features).single().name)
    }

    @Test
    fun `keeps decoded occurrences when other occurrences are malformed`() {
        val bytes = wasm(
            customSection("producers", producersPayload("processed-by" to listOf("clang" to "20"))),
            customSection("producers", byteArrayOf(0x80.toByte())),
            customSection("target_features", targetFeaturesPayload("+simd")),
            customSection("target_features", targetFeaturesPayload("?simd")),
        )
        val module = parseModule(bytes)
        val metadata = module.decodeTypedCustomMetadata(bytes.toCommonByteBuffer())

        assertEquals(CustomMetadataStatus.PARTIAL, metadata.producers.status)
        assertNotNull(metadata.producers.sections[0].fields)
        assertNull(metadata.producers.sections[1].fields)
        assertNotNull(metadata.producers.sections[1].diagnostics.single().byteOffset)
        assertEquals(CustomMetadataStatus.PARTIAL, metadata.targetFeatures.status)
        assertNotNull(metadata.targetFeatures.sections[0].features)
        assertNull(metadata.targetFeatures.sections[1].features)
        assertEquals(
            module.customSections.filter { it.rawName == "target_features" }[1].contentOffset + 1,
            metadata.targetFeatures.sections[1].diagnostics.single().byteOffset,
        )
    }

    @Test
    fun `reports invalid utf8 prefixes overflowing lengths and malformed vector entries`() {
        val bytes = wasm(
            customSection("producers", byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)),
            customSection("producers", byteArrayOf(0, 0)),
            customSection("target_features", byteArrayOf(1, '+'.code.toByte(), 2, '+'.code.toByte(), 0x80.toByte())),
            customSection("target_features", targetFeaturesPayload("?simd")),
            customSection("target_features", byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)),
        )
        val metadata = parseModule(bytes).decodeTypedCustomMetadata(bytes.toCommonByteBuffer())

        assertEquals(CustomMetadataStatus.MALFORMED, metadata.producers.status)
        assertTrue(metadata.producers.sections.any { it.diagnostics.single().message.contains("Trailing bytes") })
        assertTrue(metadata.producers.sections.any { it.diagnostics.single().message.contains("producer field name length") })
        assertEquals(CustomMetadataStatus.MALFORMED, metadata.targetFeatures.status)
        assertTrue(metadata.targetFeatures.sections.all { it.diagnostics.single().byteOffset != null })
    }

    @Test
    fun `does not read a following section while decoding malformed content`() {
        val malformedProducer = byteArrayOf(1, 1)
        val bytes = wasm(
            customSection("producers", malformedProducer),
            customSection("target_features", targetFeaturesPayload("+simd")),
        )
        val metadata = parseModule(bytes).decodeTypedCustomMetadata(bytes.toCommonByteBuffer())

        assertEquals(CustomMetadataStatus.MALFORMED, metadata.producers.status)
        assertNull(metadata.producers.sections.single().fields)
        assertEquals(CustomMetadataStatus.DECODED, metadata.targetFeatures.status)
        assertEquals("simd", assertNotNull(metadata.targetFeatures.sections.single().features).single().name)
    }

    private fun parseModule(bytes: ByteArray): BaseModule =
        (WasmParser().parse(bytes.toCommonByteBuffer()) as WasmParseSuccess).wasmModule as BaseModule

    private fun wasm(vararg sections: ByteArray): ByteArray = HEADER + sections.fold(ByteArray(0)) { all, section -> all + section }

    private fun customSection(name: String, content: ByteArray): ByteArray = wasmSection(0, encodeString(name) + content)

    private fun producersPayload(vararg fields: Pair<String, List<Pair<String, String>>>): ByteArray =
        encodeVector(fields.map { (field, values) ->
            encodeString(field) + encodeVector(values.map { (name, version) -> encodeString(name) + encodeString(version) })
        })

    private fun targetFeaturesPayload(vararg features: String): ByteArray = encodeVector(features.map { feature ->
        require(feature.isNotEmpty())
        byteArrayOf(feature.first().code.toByte()) + encodeString(feature.drop(1))
    })

    private fun wasmSection(id: Int, payload: ByteArray): ByteArray = byteArrayOf(id.toByte()) + encodeUnsigned(payload.size) + payload

    private fun encodeString(value: String): ByteArray = value.encodeToByteArray().let { encodeUnsigned(it.size) + it }

    private fun encodeVector(items: List<ByteArray>): ByteArray = encodeUnsigned(items.size) + items.fold(ByteArray(0)) { all, item -> all + item }

    private fun encodeUnsigned(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            var next = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            bytes += next.toByte()
        } while (remaining != 0)
        return bytes.toByteArray()
    }

    private companion object {
        val HEADER = byteArrayOf(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0)
    }
}
