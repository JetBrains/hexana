package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexanaToolCompatibilityTest {

    @Test
    fun `tool schema compatibility marker is explicit`() {
        assertEquals(1, HexanaToolSchemaCompatibility.schemaVersion)
        assertEquals(
            "Tool schemas are append-compatible within a version; breaking changes to names, required inputs, defaults, bounds, output keys, or descriptions require a schema version bump.",
            HexanaToolSchemaCompatibility.policy,
        )
    }

    @Test
    fun `tool schema compatibility fingerprint is reviewed intentionally`() {
        val snapshot = compatibilitySnapshot()
        val fingerprint = snapshot.sha256()

        assertEquals(
            EXPECTED_COMPATIBILITY_FINGERPRINT,
            fingerprint,
            "Hexana MCP tool schema contract changed. If this is intentional, review the snapshot below, " +
                "bump HexanaToolSchemaCompatibility.schemaVersion for breaking changes, and update the expected " +
                "fingerprint to $fingerprint.\n${snapshot.preview()}",
        )
    }

    private fun compatibilitySnapshot(): String = buildString {
        appendLine("compatibility.schemaVersion=${HexanaToolSchemaCompatibility.schemaVersion}")
        appendLine("compatibility.policy=${HexanaToolSchemaCompatibility.policy.compatibilityText()}")
        HexanaTools.forEachIndexed { index, tool ->
            val prefix = "tool[$index]"
            appendLine("$prefix.name=${tool.name}")
            appendLine("$prefix.description=${tool.mcpDescription.compatibilityText()}")
            appendLine("$prefix.inputPattern=${tool.inputPattern.compatibilityText()}")
            appendLine("$prefix.mcpAudiences=${tool.surfaces.mcp.audiences.joinToString(",")}")
            appendLine("$prefix.cli.visible=${tool.surfaces.cli.visible}")
            appendLine("$prefix.cli.commandName=${tool.surfaces.cli.commandName.orEmpty()}")
            appendLine("$prefix.cli.groups=${tool.surfaces.cli.groups.joinToString(",")}")
            appendLine("$prefix.cli.outputPolicy=${tool.surfaces.cli.outputPolicy?.name.orEmpty()}")
            appendLine("$prefix.handlerFqn=${tool.handlerFqn}")
            appendClass("$prefix.input", tool.input)
            appendClass("$prefix.output", tool.output)
        }
    }

    private fun StringBuilder.appendClass(prefix: String, spec: ClassSpec) {
        appendLine("$prefix.name=${spec.name}")
        appendLine("$prefix.kotlinFqn=${spec.kotlinFqn.orEmpty()}")
        appendLine("$prefix.oneOf=${spec.oneOf.orEmpty().joinToString(",")}")
        appendLine("$prefix.required=${spec.requiredSchemaKeys().joinToString(",")}")
        spec.fields.forEachIndexed { index, field ->
            appendField("$prefix.field[$index]", field)
        }
    }

    private fun StringBuilder.appendField(prefix: String, field: FieldSpec) {
        appendLine("$prefix.name=${field.name}")
        appendLine("$prefix.schemaKey=${field.schemaKey}")
        appendLine("$prefix.type=${field.type.compatibilityText()}")
        appendLine("$prefix.nullable=${field.nullable}")
        appendLine("$prefix.default=${field.default.compatibilityValueText()}")
        appendLine("$prefix.description=${field.describe.orEmpty().compatibilityText()}")
        appendLine("$prefix.serializerFqn=${field.serializerFqn.orEmpty()}")
        appendLine("$prefix.minimum=${field.minimum.compatibilityValueText()}")
        appendLine("$prefix.maximum=${field.maximum.compatibilityValueText()}")
        appendLine("$prefix.maxItems=${field.maxItems.compatibilityText()}")
        appendLine("$prefix.maxLength=${field.maxLength.compatibilityText()}")
        appendLine("$prefix.itemMinimum=${field.itemMinimum.compatibilityValueText()}")
        appendLine("$prefix.itemMaximum=${field.itemMaximum.compatibilityValueText()}")
        appendNestedClasses(prefix, field.type)
    }

    private fun StringBuilder.appendNestedClasses(prefix: String, type: FieldType) {
        when (type) {
            is FieldType.ClassRef -> appendClass("$prefix.class", type.clazz)
            is FieldType.ListOf -> appendNestedClasses("$prefix.items", type.element)
            is FieldType.MapOf -> appendNestedClasses("$prefix.values", type.value)
            FieldType.BooleanT,
            FieldType.ByteArrayT,
            FieldType.ByteT,
            FieldType.Float64,
            FieldType.Int32,
            FieldType.Int64,
            FieldType.StringT,
            FieldType.UInt32 -> Unit
        }
    }

    private fun ClassSpec.requiredSchemaKeys(): List<String> =
        fields.filter { !it.nullable && it.default == null }.map { it.schemaKey }

    private fun ToolInputPattern.compatibilityText(): String {
        return when (this) {
            is ToolInputPattern.ClosedRange -> "ClosedRange($parameterName)"
            else -> requireNotNull(this::class.simpleName)
        }
    }

    private fun FieldType.compatibilityText(): String {
        return when (this) {
            FieldType.Int32 -> "Int32"
            FieldType.Int64 -> "Int64"
            FieldType.Float64 -> "Float64"
            FieldType.UInt32 -> "UInt32"
            FieldType.ByteT -> "Byte"
            FieldType.BooleanT -> "Boolean"
            FieldType.StringT -> "String"
            FieldType.ByteArrayT -> "ByteArray"
            is FieldType.ListOf -> "List<${element.compatibilityText()}>"
            is FieldType.MapOf -> "Map<${value.compatibilityText()}>"
            is FieldType.ClassRef -> "Class<${clazz.name}>"
        }
    }

    private fun SchemaIntValue?.compatibilityText(): String {
        return when (this) {
            null -> ""
            is SchemaIntValue.Literal -> "Literal($value)"
            SchemaIntValue.MaxInlineBinaryBase64Length -> "MaxInlineBinaryBase64Length"
            SchemaIntValue.MaxInlineBinaryBytes -> "MaxInlineBinaryBytes"
            SchemaIntValue.MaxInlineWasmBase64Length -> "MaxInlineWasmBase64Length"
            SchemaIntValue.MaxInlineWasmBytes -> "MaxInlineWasmBytes"
        }
    }

    private fun Any?.compatibilityValueText(): String {
        return when (this) {
            null -> ""
            is ByteArray -> contentToString()
            is String -> compatibilityText()
            else -> toString()
        }
    }

    private fun String.compatibilityText(): String {
        return replace("\r\n", "\n")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        assertTrue(digest.isNotEmpty())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String.preview(): String {
        val lines = lineSequence().toList()
        val previewLineCount = 240
        val preview = lines.take(previewLineCount).joinToString("\n")
        return if (lines.size <= previewLineCount) {
            preview
        } else {
            "$preview\n... truncated ${lines.size - previewLineCount} compatibility lines ..."
        }
    }

    private companion object {
        const val EXPECTED_COMPATIBILITY_FINGERPRINT = "4f9d00d4f86602dba6098fa5c3af6466f99e7f6c96b5dc8f6ff76f20478ee58c"
    }
}
