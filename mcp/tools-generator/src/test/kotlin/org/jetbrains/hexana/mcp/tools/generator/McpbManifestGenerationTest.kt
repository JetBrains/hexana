package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.tools.HexanaTools
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpbManifestGenerationTest {

    @Test
    fun `mcpb manifest tools are generated from the shared catalog`() {
        val dir = Files.createTempDirectory("hexana-mcpb-manifest-test")
        val template = dir.resolve("manifest.json")
        val output = dir.resolve("manifest.generated.json")
        Files.writeString(
            template,
            """
            {
              "manifest_version": "0.3",
              "version": "__GENERATED_BY_ASSEMBLY__",
              "compatibility": {
                "platforms": [
                  "__GENERATED_MCPB_PLATFORM__"
                ]
              },
              "tools_generated": false,
              "tools": []
            }
            """.trimIndent()
        )

        generateMcpbManifest(
            template = template,
            output = output,
            version = "9.8.7",
            platform = "darwin",
        )

        val manifest = Json.parseToJsonElement(Files.readString(output)).jsonObject
        val tools = manifest.getValue("tools").jsonArray
        assertEquals("9.8.7", manifest.getValue("version").jsonPrimitive.content)
        assertEquals(
            listOf("darwin"),
            manifest.getValue("compatibility").jsonObject.getValue("platforms").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(HexanaTools.size, tools.size)
        assertEquals(HexanaTools.map { it.name }, tools.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(
            HexanaTools.map { it.summary.trim() },
            tools.map { it.jsonObject.getValue("description").jsonPrimitive.content },
        )
    }

    @Test
    fun `checked in mcpb manifest tool metadata is up to date`() {
        val manifest = Json.parseToJsonElement(Files.readString(checkedInMcpbManifest())).jsonObject
        val tools = manifest.getValue("tools").jsonArray

        assertEquals("__GENERATED_BY_ASSEMBLY__", manifest.getValue("version").jsonPrimitive.content)
        assertEquals(
            listOf("__GENERATED_MCPB_PLATFORM__"),
            manifest.getValue("compatibility").jsonObject.getValue("platforms").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(true, manifest.getValue("tools_generated").jsonPrimitive.content.toBooleanStrict())
        assertEquals(HexanaTools.size, tools.size)
        assertEquals(HexanaTools.map { it.name }, tools.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(
            HexanaTools.map { it.summary.trim() },
            tools.map { it.jsonObject.getValue("description").jsonPrimitive.content },
        )
    }

    private fun checkedInMcpbManifest(): Path {
        val cwd = Path.of("").toAbsolutePath()
        val candidates = listOf(
            cwd.resolve("mcp/packaging/claude-desktop-mcpb/manifest.json"),
            cwd.resolve("../packaging/claude-desktop-mcpb/manifest.json").normalize(),
        )
        val manifest = candidates.firstOrNull(Files::isRegularFile)
        assertTrue(manifest != null, "Could not find checked-in MCPB manifest from $cwd")
        return manifest
    }
}
