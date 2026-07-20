package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.tools.HexanaMcpbTools
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneDefaultToolIds
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpbManifestGenerationTest {
    @Test
    fun `MCPB manifest exactly follows standalone default order and metadata`() {
        val dir = Files.createTempDirectory("hexana-mcpb-manifest-test")
        val template = dir.resolve("manifest.json")
        val output = dir.resolve("manifest.generated.json")
        Files.writeString(
            template,
            """
            {
              "manifest_version": "0.3",
              "version": "placeholder",
              "display_name": "Hexana preserved",
              "compatibility": {
                "platforms": ["placeholder-platform"],
                "runtime": {"java": ">=21"}
              },
              "tools_generated": false,
              "tools": [],
              "preserved_extension": {"enabled": true}
            }
            """.trimIndent(),
        )

        generateMcpbManifest(template, output, "9.8.7", "darwin")

        val manifest = Json.parseToJsonElement(Files.readString(output)).jsonObject
        val tools = manifest.getValue("tools").jsonArray
        val compatibility = manifest.getValue("compatibility").jsonObject
        assertEquals("0.3", manifest.getValue("manifest_version").jsonPrimitive.content)
        assertEquals("9.8.7", manifest.getValue("version").jsonPrimitive.content)
        assertEquals(
            listOf("darwin"),
            compatibility.getValue("platforms").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(">=21", compatibility.getValue("runtime").jsonObject.getValue("java").jsonPrimitive.content)
        assertEquals("Hexana preserved", manifest.getValue("display_name").jsonPrimitive.content)
        assertEquals("true", manifest.getValue("tools_generated").jsonPrimitive.content)
        assertEquals(
            "true",
            manifest.getValue("preserved_extension").jsonObject.getValue("enabled").jsonPrimitive.content,
        )
        assertEquals(
            HexanaStandaloneDefaultToolIds.map { it.value },
            tools.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertEquals(HexanaStandaloneDefaultToolIds, mcpbManifestTools().map { it.id })
        assertEquals(
            HexanaMcpbTools.map { it.summary.trim() },
            tools.map { it.jsonObject.getValue("description").jsonPrimitive.content },
        )
        assertEquals(29, tools.size)
        assertFalse(tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "query_binary" })
    }

    @Test
    fun `checked in MCPB manifest tool metadata is current`() {
        val manifest = Json.parseToJsonElement(Files.readString(checkedInManifest())).jsonObject
        val tools = manifest.getValue("tools").jsonArray
        assertEquals("true", manifest.getValue("tools_generated").jsonPrimitive.content)
        assertEquals(
            HexanaStandaloneDefaultToolIds.map { it.value },
            tools.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertEquals(
            HexanaMcpbTools.map { it.summary.trim() },
            tools.map { it.jsonObject.getValue("description").jsonPrimitive.content },
        )
    }

    private fun checkedInManifest(): Path {
        val cwd = Path.of("").toAbsolutePath()
        val candidates = listOf(
            cwd.resolve("mcp/packaging/claude-desktop-mcpb/manifest.json"),
            cwd.resolve("../packaging/claude-desktop-mcpb/manifest.json").normalize(),
        )
        return candidates.firstOrNull(Files::isRegularFile).also {
            assertTrue(it != null, "Could not find checked-in MCPB manifest from $cwd")
        }!!
    }
}
