package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.tools.HexanaMcpbTools
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpbManifestGenerationTest {
    @Test
    fun `MCPB manifest uses its independent ordered authority`() {
        val dir = Files.createTempDirectory("hexana-mcpb-manifest-test")
        val template = dir.resolve("manifest.json")
        val output = dir.resolve("manifest.generated.json")
        Files.writeString(
            template,
            """{"version":"placeholder","compatibility":{"platforms":[]},"tools_generated":false,"tools":[]}""",
        )

        generateMcpbManifest(template, output, "9.8.7", "darwin")

        val manifest = Json.parseToJsonElement(Files.readString(output)).jsonObject
        val tools = manifest.getValue("tools").jsonArray
        assertEquals("9.8.7", manifest.getValue("version").jsonPrimitive.content)
        assertEquals(
            HexanaMcpbTools.map { it.name },
            tools.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertEquals(29, tools.size)
        assertFalse(tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "query_binary" })
    }

    @Test
    fun `checked in MCPB manifest tool metadata is current`() {
        val manifest = Json.parseToJsonElement(Files.readString(checkedInManifest())).jsonObject
        val tools = manifest.getValue("tools").jsonArray
        assertEquals(
            HexanaMcpbTools.map { it.name },
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
