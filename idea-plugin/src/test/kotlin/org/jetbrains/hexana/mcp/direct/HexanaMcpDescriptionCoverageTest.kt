package org.jetbrains.hexana.mcp.direct

import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.HexanaToolset

/**
 * Content snapshots over the Hexana idea-plugin toolset. Not a full-coverage
 * contract — we pick a handful of representative tools and pin down short
 * substrings from their descriptions + input-field descriptions. When the
 * upcoming code generator takes over, these snapshots guard against silently
 * dropping the existing content.
 *
 * The snapshots are intentionally substring-based: editing a description will
 * break the tests, which is the point — the break forces a conscious update.
 */
class HexanaMcpDescriptionCoverageTest : HexanaMcpToolsetDirectTestBase() {

    fun testEveryToolHasNonBlankDescription() {
        val missing = descriptors()
            .filter { it.description.isBlank() }
            .map { it.name }

        assertEmpty("Tools without a `@McpDescription`", missing)
    }

    fun testEveryInputFieldHasDescription() {
        val missing = descriptors().flatMap { descriptor ->
            fieldsMissingDescription(descriptor.inputSchema).map { fieldName ->
                "${descriptor.name}.input.$fieldName"
            }
        }

        assertEmpty("Input fields without a `@McpDescription` annotation", missing)
    }

    fun testQueryArtifactsDescriptionContent() {
        val descriptor = descriptor("query_artifacts")
        assertContains(descriptor.description, "bounded output")
        assertContains(descriptor.description, "overview")
        assertContains(descriptor.description, "diff")
        assertContains(descriptor.description, "triage")
        assertContains(descriptor.description, "api_diff")
        assertContains(descriptor.description, "reference-type import")
        assertContains(descriptor.description, "abi_surface")
        assertContains(descriptor.description, "import_surface")
        assertContains(descriptor.description, "data_payload_audit")
        assertContains(descriptor.description, "local_budget")
        assertContains(descriptor.description, "entrypoint_effects")
        assertContains(descriptor.description, "crash_frames")
        assertContains(descriptor.description, "Stop after a focused operation")
        assertContains(descriptor.description, "host_call_diff")
        assertContains(descriptor.description, "modules[].path")
        assertContains(descriptor.description, "generic `trace`")
        assertContains(descriptor.description, "public_surface")
        assertContains(inputFieldDescription(descriptor, "path"), "relative")
        assertContains(inputFieldDescription(descriptor, "operation"), "overview")
        assertContains(inputFieldDescription(descriptor, "operation"), "triage")
        assertContains(inputFieldDescription(descriptor, "operation"), "api_diff")
        assertContains(inputFieldDescription(descriptor, "operation"), "reference-type import")
        assertContains(inputFieldDescription(descriptor, "operation"), "abi_surface")
        assertContains(inputFieldDescription(descriptor, "operation"), "import_surface")
        assertContains(inputFieldDescription(descriptor, "operation"), "data_payload_audit")
        assertContains(inputFieldDescription(descriptor, "operation"), "local_budget")
        assertContains(inputFieldDescription(descriptor, "operation"), "entrypoint_effects")
        assertContains(inputFieldDescription(descriptor, "operation"), "crash_frames")
        assertContains(inputFieldDescription(descriptor, "operation"), "memory_contract")
        assertContains(inputFieldDescription(descriptor, "query"), "data_payload_audit")
        assertContains(inputFieldDescription(descriptor, "query"), "crash_frames")
        assertContains(inputFieldDescription(descriptor, "query"), "numeric")
        assertContains(inputFieldDescription(descriptor, "relations"), "entrypoint_effects")
        assertContains(inputFieldDescription(descriptor, "entityTypes"), "export")
        assertContains(inputFieldDescription(descriptor, "entityTypes"), "element")
        assertContains(inputFieldDescription(descriptor, "limit"), "Maximum records")
    }

    // TODO(hexana-72): Output-field descriptions are currently missing on the generated schemas
    // (result data classes in mcp/core/model carry no @McpDescription). Once those are populated,
    // mirror the input snapshots here, e.g.:
    //
    //     assertContains(outputFieldDescription(descriptor, "imports"),
    //                    "ImportFunctionResult")
    //
    // Leaving this as a tracked gap rather than a red test.

    private fun descriptors(): List<McpToolDescriptor> {
        return HexanaToolset().getTools().map { it.descriptor }
    }

    private fun descriptor(name: String): McpToolDescriptor {
        return descriptors().single { it.name == name }
    }

    private fun fieldsMissingDescription(schema: McpToolSchema): List<String> {
        return schema.propertiesSchema.entries
            .filter { (name, node) -> requireObjectSchema(name, node).descriptionContent().isNullOrBlank() }
            .map { (name, _) -> name }
    }

    private fun inputFieldDescription(descriptor: McpToolDescriptor, fieldName: String): String {
        return fieldDescription(descriptor.inputSchema, fieldName)
            ?: assertionFailure(
                "Tool `${descriptor.name}` has no input field `$fieldName` with a description. " +
                    "Known fields: ${descriptor.inputSchema.propertiesSchema.keys}"
            )
    }

    private fun fieldDescription(schema: McpToolSchema, fieldName: String): String? {
        val node = schema.propertiesSchema[fieldName] ?: return null
        return requireObjectSchema(fieldName, node).descriptionContent()?.takeIf { it.isNotBlank() }
    }

    /** MCP property schemas are always objects (`{"type": "...", "description": "..."}`); fail loudly if not. */
    private fun requireObjectSchema(fieldName: String, node: kotlinx.serialization.json.JsonElement): JsonObject {
        return node as? JsonObject
            ?: assertionFailure(
                "Field `$fieldName` has non-object schema ${node::class.simpleName} — coverage test assumes object-shaped property schemas."
            )
    }

    private fun JsonObject.descriptionContent(): String? {
        return get("description")?.jsonPrimitive?.contentOrNull
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(
            "Expected substring `$needle` in:\n---\n$haystack\n---",
            haystack.contains(needle)
        )
    }

    private fun assertionFailure(message: String): Nothing {
        fail(message)
        error("unreachable")
    }
}
