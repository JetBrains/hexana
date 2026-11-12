package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HexanaNativeImageMetadataTest {
    @Test
    fun nativeImageReflectConfigRetainsMcpProtocolSerializers() {
        val entries = nativeImageJson("reflect-config.json").jsonArray
        val retainedClasses = entries.mapNotNull { entry ->
            entry.jsonObject["name"]?.jsonPrimitive?.content
        }.toSet()

        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest\$Companion" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.RequestId\$StringId" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.RequestId\$NumberId" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.InitializeResult" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.CallToolResult" in retainedClasses)
        assertTrue("io.modelcontextprotocol.kotlin.sdk.types.TextContent" in retainedClasses)
    }

    @Test
    fun nativeImageReflectConfigRetainsKtorHttpSelectorFields() {
        val entries = nativeImageJson("reflect-config.json").jsonArray
        val selectorEntry = entries
            .map { it.jsonObject }
            .single { entry ->
                entry["name"]?.jsonPrimitive?.content == "io.ktor.network.selector.InterestSuspensionsMap"
            }

        assertEquals("true", selectorEntry["allDeclaredFields"]?.jsonPrimitive?.content)
    }

    @Test
    fun nativeImageResourceConfigIncludesSlf4jServiceProvider() {
        val root = nativeImageJson("resource-config.json").jsonObject
        val includes = root
            .objectValue("resources")
            .arrayValue("includes")
            .mapNotNull { include -> include.jsonObject["pattern"]?.jsonPrimitive?.content }
            .toSet()

        assertTrue("META-INF/services/org\\.slf4j\\.spi\\.SLF4JServiceProvider" in includes)
    }

    private fun nativeImageJson(fileName: String) = Json.parseToJsonElement(
        readNativeImageResource(fileName)
    )

    private fun readNativeImageResource(fileName: String): String {
        val path = "$NATIVE_IMAGE_METADATA_PATH/$fileName"
        val input = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(input, "Expected committed native-image metadata resource: $path")
        return input.bufferedReader().use { it.readText() }
    }

    private fun JsonObject.objectValue(name: String): JsonObject {
        return requireNotNull(this[name] as? JsonObject) {
            "Expected `$name` object in native-image metadata"
        }
    }

    private fun JsonObject.arrayValue(name: String): JsonArray {
        return requireNotNull(this[name] as? JsonArray) {
            "Expected `$name` array in native-image metadata"
        }
    }

    private companion object {
        const val NATIVE_IMAGE_METADATA_PATH =
            "META-INF/native-image/org.jetbrains.hexana/hexana-mcp-server"
    }
}
