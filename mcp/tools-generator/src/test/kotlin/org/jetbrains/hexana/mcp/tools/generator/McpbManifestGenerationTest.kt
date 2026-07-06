package org.jetbrains.hexana.mcp.tools.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.tools.McpToolAudience
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.productionCliSurface
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val CURRENT_PUBLIC_MCPB_TOOL_NAMES = listOf(
    "summarize_binary",
    "summarize_module",
    "list_enabled_features",
    "get_entrypoint_policy",
    "list_required_proposals",
    "diff_required_proposals",
    "check_runtime_compatibility",
    "check_component_compatibility",
    "plan_abi_binding_regeneration",
    "check_memory_contract",
    "audit_global_state_policy",
    "audit_local_stack_budget",
    "list_imports",
    "list_exports",
    "search_exports",
    "list_exported_functions",
    "list_functions",
    "resolve_function_signature",
    "disassemble_function",
    "get_function_call_graph",
    "search_functions_by_name",
    "resolve_wasm_frames",
    "resolve_function_symbols",
    "resolve_source_locations",
    "list_memory",
    "list_globals",
    "list_element_segments",
    "list_data_segments",
    "audit_data_payload_policy",
)

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
        val metadataToolNames = mcpbManifestTools().map { it.name }
        assertEquals(CURRENT_PUBLIC_MCPB_TOOL_NAMES, metadataToolNames)
        assertEquals(metadataToolNames.size, tools.size)
        assertEquals(metadataToolNames, toolNames)
        assertFalse("query_binary" in toolNames)
        assertEquals(
            mcpbManifestTools().map { it.summary.trim() },
            tools.map { it.jsonObject.getValue("description").jsonPrimitive.content },
        )
    }

    @Test
    fun `MCPB manifest inclusion is not inferred from production MCP audience`() {
        val productionOnlyTool = mcpbManifestTools().first().copy(
            name = "production_only",
            surfaces = productionCliSurface("production-only", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        )

        assertTrue(McpToolAudience.Production in productionOnlyTool.surfaces.mcp.audiences)
        assertFalse(productionOnlyTool.surfaces.publicMcpb.included)
        assertEquals(emptyList(), mcpbManifestTools(listOf(productionOnlyTool)))
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
        val metadataToolNames = mcpbManifestTools().map { it.name }
        assertEquals(CURRENT_PUBLIC_MCPB_TOOL_NAMES, metadataToolNames)
        assertEquals(metadataToolNames.size, tools.size)
        assertEquals(metadataToolNames, toolNames)
        assertFalse("list_binary_functions" in toolNames)
        assertEquals(
            mcpbManifestTools().map { it.summary.trim() },
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
