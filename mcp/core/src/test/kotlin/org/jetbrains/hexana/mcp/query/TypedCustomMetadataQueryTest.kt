package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.metadata.MetadataStatus
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypedCustomMetadataQueryTest {
    @Test
    fun `query has stable snake case serialization and decodes beyond raw preview`() {
        val version = "v".repeat(140)
        val bytes = coreWasm(
            customSection("producers", producersPayload("processed-by", "tool", version)),
            customSection("target_features", targetFeaturesPayload("+simd", "-threads")),
            customSection("target_features", targetFeaturesPayload("?gcx")),
        )
        val provider = WasmBinaryProvider(bytes.toCommonByteBuffer())
        val rawInventory = provider.readCustomSections()
        val result = readTypedCustomMetadata(parseModule(bytes), bytes.toCommonByteBuffer())
        val serialized = Json.encodeToString(result)

        assertEquals(MetadataStatus.DECODED, result.producers.status)
        assertEquals(version, result.producers.sections.single().fields!!.single().values.single().version)
        assertEquals("producers", rawInventory.customSections.first().name)
        assertEquals("binary", rawInventory.customSections.first().encoding)
        assertEquals(96, rawInventory.customSections.first().preview.trim().split(" ").size)
        assertTrue(serialized.contains("\"target_features\""))
        assertTrue(serialized.contains("\"offset_bytes\""))
        assertTrue(serialized.contains("\"field_index\""))
        assertTrue(serialized.contains("\"byte_offset\""))
        assertTrue(!serialized.contains("targetFeatures"))
    }

    @Test
    fun `query accepts component modules as well as core modules`() {
        val bytes = COMPONENT_HEADER + customSection("target_features", targetFeaturesPayload("+gc"))

        val result = readTypedCustomMetadata(parseModule(bytes), bytes.toCommonByteBuffer())

        assertEquals(MetadataStatus.DECODED, result.targetFeatures.status)
        assertEquals("gc", result.targetFeatures.sections.single().features!!.single().name)
    }

    private fun parseModule(bytes: ByteArray): BaseModule =
        (WasmParser().parse(bytes.toCommonByteBuffer()) as WasmParseSuccess).wasmModule as BaseModule

    private fun coreWasm(vararg sections: ByteArray): ByteArray = CORE_HEADER + sections.fold(ByteArray(0)) { all, section -> all + section }

    private fun customSection(name: String, content: ByteArray): ByteArray = wasmSection(0, encodeString(name) + content)

    private fun producersPayload(field: String, name: String, version: String): ByteArray =
        encodeVector(listOf(encodeString(field) + encodeVector(listOf(encodeString(name) + encodeString(version)))))

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
        val CORE_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0)
        val COMPONENT_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6D, 0x0D, 0, 1, 0)
    }
}
