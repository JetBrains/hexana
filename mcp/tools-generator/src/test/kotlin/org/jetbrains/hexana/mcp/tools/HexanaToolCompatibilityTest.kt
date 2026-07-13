package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexanaToolCompatibilityTest {

    @Test
    fun `tool schema compatibility marker is explicit`() {
        assertEquals(2, HexanaToolSchemaCompatibility.schemaVersion)
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

    @Test
    fun `compatibility snapshot includes CLI input model metadata`() {
        val snapshot = compatibilitySnapshot(listOf(explicitCliInputModelTool()))

        assertTrue(snapshot.contains("tool[0].cliInputModel.origin=explicit"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].name=current"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].kind=Wasm"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].target=field:current"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].required=true"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].cli.positionalPathName=current"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].cli.stdinOption=--stdin"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].cli.base64Option=--current-base64"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.source[0].cli.autoStdin=true"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.param[0].name=limit"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.param[0].kind=Value"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.param[0].targetField=limit"))
        assertTrue(snapshot.contains("tool[0].cliInputModel.param[0].cliOption=--limit"))
    }

    @Test
    fun `compatibility snapshot includes CLI product identity and closure metadata`() {
        val snapshot = compatibilitySnapshot()

        assertTrue(snapshot.contains("product[HexanaCli].productId=hexana-cli"))
        assertTrue(snapshot.contains("product[HexanaCliScript].executableName=hexana-cli-script"))
        assertTrue(snapshot.contains("tool[0].cli.releaseProducts=HexanaMcp,HexanaCli"))
        assertTrue(snapshot.contains("tool[0].cli.outputPolicy=ToolDefined"))
        assertTrue(snapshot.contains("tool[0].standaloneHandler=summarizeBinary"))
        assertTrue(snapshot.contains("tool[0].cli.executableRenderer=renderSummarizeBinaryCommandOutput"))
    }

    private fun compatibilitySnapshot(tools: List<ToolSpec> = HexanaTools): String = buildString {
        appendLine("compatibility.schemaVersion=${HexanaToolSchemaCompatibility.schemaVersion}")
        appendLine("compatibility.policy=${HexanaToolSchemaCompatibility.policy.compatibilityText()}")
        CliReleaseProduct.entries.forEach { product ->
            appendLine("product[${product.name}].productId=${product.productId}")
            appendLine("product[${product.name}].executableName=${product.executableName}")
        }
        tools.forEachIndexed { index, tool ->
            val prefix = "tool[$index]"
            appendLine("$prefix.name=${tool.name}")
            appendLine("$prefix.description=${tool.mcpDescription.compatibilityText()}")
            appendLine("$prefix.inputPattern=${tool.inputPattern.compatibilityText()}")
            appendCliInputModel("$prefix.cliInputModel", tool)
            appendLine("$prefix.mcpAudiences=${tool.surfaces.mcp.audiences.joinToString(",")}")
            appendLine("$prefix.publicMcpb.included=${tool.surfaces.publicMcpb.included}")
            appendLine("$prefix.cli.visible=${tool.surfaces.cli.visible}")
            appendLine("$prefix.cli.commandName=${tool.surfaces.cli.commandName.orEmpty()}")
            appendLine("$prefix.cli.groups=${tool.surfaces.cli.groups.joinToString(",")}")
            appendLine("$prefix.cli.outputPolicy=${tool.surfaces.cli.outputPolicy?.name.orEmpty()}")
            appendLine("$prefix.cli.executionStatus=${tool.surfaces.cli.executionStatus?.name.orEmpty()}")
            appendLine("$prefix.cli.outputOverride=${tool.surfaces.cli.outputOverride?.name.orEmpty()}")
            appendLine("$prefix.cli.defaultOutput=${tool.surfaces.cli.defaultOutput?.name.orEmpty()}")
            appendLine("$prefix.cli.executableRenderer=${tool.surfaces.cli.executableRenderer?.methodName.orEmpty()}")
            appendLine("$prefix.cli.releaseProducts=${tool.surfaces.cli.releaseProducts.joinToString(",")}")
            appendLine("$prefix.handlerFqn=${tool.handlerFqn}")
            appendLine("$prefix.standaloneHandler=${tool.standaloneHandler.methodName}")
            appendClass("$prefix.input", tool.input)
            appendClass("$prefix.output", tool.output)
        }
    }

    private fun StringBuilder.appendCliInputModel(prefix: String, tool: ToolSpec) {
        val explicitModel = tool.inputModel
        val model = when {
            explicitModel != null -> explicitModel.also {
                appendLine("$prefix.origin=explicit")
            }
            tool.surfaces.cli.executionStatus == CliExecutionStatus.Executable -> {
                val derivedModel = runCatching { tool.inputModelForCli() }.getOrElse { error ->
                    appendLine("$prefix.origin=unsupported")
                    appendLine("$prefix.unsupportedReason=${error.message.orEmpty().compatibilityText()}")
                    return
                }
                appendLine("$prefix.origin=derived")
                derivedModel
            }
            else -> {
                appendLine("$prefix.origin=not-applicable")
                return
            }
        }

        appendLine("$prefix.sources=${model.sources.size}")
        model.sources.forEachIndexed { index, source ->
            appendSourceSlot("$prefix.source[$index]", source)
        }
        appendLine("$prefix.params=${model.params.size}")
        model.params.forEachIndexed { index, param ->
            appendParamBinding("$prefix.param[$index]", param)
        }
    }

    private fun StringBuilder.appendSourceSlot(prefix: String, source: SourceSlotSpec) {
        appendLine("$prefix.name=${source.name}")
        appendLine("$prefix.kind=${source.kind.name}")
        appendLine("$prefix.target=${source.target.compatibilityText()}")
        appendLine("$prefix.required=${source.required}")
        appendLine("$prefix.cli.positionalPathName=${source.cli.positionalPathName.orEmpty()}")
        appendLine("$prefix.cli.pathOption=${source.cli.pathOption.orEmpty()}")
        appendLine("$prefix.cli.stdinOption=${source.cli.stdinOption.orEmpty()}")
        appendLine("$prefix.cli.base64Option=${source.cli.base64Option.orEmpty()}")
        appendLine("$prefix.cli.bytesOption=${source.cli.bytesOption.orEmpty()}")
        appendLine("$prefix.cli.autoStdin=${source.cli.autoStdin}")
    }

    private fun StringBuilder.appendParamBinding(prefix: String, param: ParamBindingSpec) {
        appendLine("$prefix.name=${param.name}")
        appendLine("$prefix.kind=${param.kind.name}")
        appendLine("$prefix.targetField=${param.targetField}")
        appendLine("$prefix.cliOption=${param.cliOption.orEmpty()}")
    }

    private fun StringBuilder.appendClass(prefix: String, spec: ClassSpec) {
        appendLine("$prefix.name=${spec.name}")
        appendLine("$prefix.kotlinFqn=${spec.kotlinFqn.orEmpty()}")
        appendLine("$prefix.oneOf=${spec.oneOf.orEmpty().joinToString(",")}")
        appendLine("$prefix.inputCapabilities=${spec.inputCapabilities.joinToString(",") { it.name }}")
        appendLine("$prefix.outputCapabilities=${spec.outputCapabilities.joinToString(",") { it.name }}")
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
            FieldType.JsonObjectT,
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

    private fun SourceTargetSpec.compatibilityText(): String {
        return when (this) {
            SourceTargetSpec.RootInput -> "root"
            is SourceTargetSpec.Field -> "field:$name"
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
            FieldType.JsonObjectT -> "JsonObject"
            is FieldType.ListOf -> "List<${element.compatibilityText()}>"
            is FieldType.MapOf -> "Map<${value.compatibilityText()}>"
            is FieldType.ClassRef -> "Class<${clazz.name}>"
        }
    }

    private fun explicitCliInputModelTool(): ToolSpec =
        tools {
            tool("snapshot_input_model") {
                summary = "Snapshot CLI input model."
                input = WasmInputClass.with("SnapshotInputModelInput") {
                    embed("current", WasmInputClass).describe("Current WebAssembly module source.")
                    int("limit", default = 25).describe("Maximum result count.")
                }
                inputPattern = ToolInputPattern.WasmOnly
                inputModel = toolInputModel {
                    wasmSource("current") {
                        cli {
                            positional("current")
                            stdin("--stdin")
                            base64("--current-base64")
                            autoStdin()
                        }
                    }
                    param(name = "limit", cliOption = "--limit")
                }
                output = class_("SnapshotInputModelResult") {
                    string("status").describe("Snapshot status.")
                }
                surfaces = productionCliSurface("snapshot-input-model", ToolGroup.Module, ToolOutputPolicy.ToolDefined)
                handlerFqn = "org.jetbrains.hexana.mcp.query.snapshotInputModel"
            }
        }.single()

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
        const val EXPECTED_COMPATIBILITY_FINGERPRINT = "ced4777bf63e25e91b142f466227384f7c4449683b6d6b9d62297b1342c835e5"
    }
}
