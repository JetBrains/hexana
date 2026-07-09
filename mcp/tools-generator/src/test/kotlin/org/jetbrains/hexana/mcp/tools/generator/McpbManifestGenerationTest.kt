package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.tools.HexanaTools
import org.jetbrains.hexana.mcp.tools.McpSurfaceSpec
import org.jetbrains.hexana.mcp.tools.McpToolAudience
import org.jetbrains.hexana.mcp.tools.PublicMcpbSurfaceSpec
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.productionCliSurface
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val toolNames = tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        val metadataTools = publicMcpbCatalogTools()
        val metadataToolNames = metadataTools.map { it.name }
        assertEquals(metadataToolNames.size, tools.size)
        assertEquals(metadataToolNames, toolNames)
        assertFalse("query_binary" in toolNames)
        assertEquals(
            metadataTools.map { it.summary.trim() },
            tools.map { it.jsonObject.getValue("description").jsonPrimitive.content },
        )
    }

    @Test
    fun `MCPB manifest inclusion is not inferred from production MCP audience`() {
        val productionOnlyTool = publicMcpbCatalogTools().first().copy(
            name = "production_only",
            surfaces = productionCliSurface("production-only", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        )

        assertTrue(McpToolAudience.Production in productionOnlyTool.surfaces.mcp.audiences)
        assertFalse(productionOnlyTool.surfaces.publicMcpb.included)
        assertEquals(emptyList(), mcpbManifestTools(listOf(productionOnlyTool)))

        val publicMcpbNonProductionTool = productionOnlyTool.copy(
            name = "public_mcpb_non_production",
            surfaces = productionOnlyTool.surfaces.copy(
                mcp = McpSurfaceSpec.none(),
                publicMcpb = PublicMcpbSurfaceSpec.included(),
            ),
        )

        assertFalse(McpToolAudience.Production in publicMcpbNonProductionTool.surfaces.mcp.audiences)
        assertTrue(publicMcpbNonProductionTool.surfaces.publicMcpb.included)
        assertEquals(listOf(publicMcpbNonProductionTool), mcpbManifestTools(listOf(publicMcpbNonProductionTool)))
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
        val toolNames = tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        val metadataTools = publicMcpbCatalogTools()
        val metadataToolNames = metadataTools.map { it.name }
        assertEquals(metadataToolNames.size, tools.size)
        assertEquals(metadataToolNames, toolNames)
        assertFalse("list_binary_functions" in toolNames)
        assertEquals(
            metadataTools.map { it.summary.trim() },
            tools.map { it.jsonObject.getValue("description").jsonPrimitive.content },
        )
    }

    private fun publicMcpbCatalogTools(): List<ToolSpec> =
        HexanaTools.filter { it.surfaces.publicMcpb.included }

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
