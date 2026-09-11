package org.jetbrains.hexana.mcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Deterministic, static developer-facing view of the resolved tool authorities. */
class ResolvedToolCatalogReport(
    private val semanticContracts: List<ToolSpec> = HexanaTools,
    private val ideaTools: List<IdeaTool> = HexanaIdeaTools,
    private val standaloneAuthority: StandaloneToolAuthority = HexanaStandaloneAuthority,
    private val cliTools: List<CliTool> = HexanaCliTools,
    private val cliProducts: CliProductRegistry = HexanaCliProductRegistry,
) {
    fun json(): String = JSON.encodeToString(JsonObject.serializer(), document()) + "\n"

    fun text(): String = buildString {
        appendLine("Hexana resolved tool catalog report")
        renderText(document(), 0)
    }

    internal fun document(): JsonObject {
        validate()
        return buildJsonObject {
            put("formatVersion", JsonPrimitive(1))
            put("runtimeAvailability", JsonPrimitive("not evaluated"))
            put("requirements", JsonArray(emptyList()))
            put("cliProducts", buildJsonArray { cliProducts.products.forEach { add(cliProductDocument(it)) } })
            put("tools", buildJsonArray { normalizedContracts.forEach { add(toolDocument(it)) } })
        }
    }

    private fun validate() {
        val known = semanticContracts.map { it.id }.toSet()
        require(known.size == semanticContracts.size) { "semantic report contracts contain duplicate tool IDs" }
        require(known == HexanaToolCatalog.contracts.map { it.id }.toSet()) {
            "semantic report contracts must contain exactly the canonical tool IDs"
        }
        fun requireKnown(name: String, ids: Collection<ToolId>) = require(ids.all(known::contains)) {
            "$name references unknown tool IDs: ${ids.filterNot(known::contains).joinToString()}"
        }
        requireKnown("IDEA report authority", ideaTools.map { it.id })
        requireKnown("standalone report authority", standaloneAuthority.registrations.map { it.id })
        requireKnown("CLI report authority", cliTools.map { it.id })
        validateCliProducts(cliTools, cliProducts, requireCompleteSelection = false)
        normalizedContracts.forEach { tool ->
            views(tool).forEach { view ->
                requireNonBlankProvenance(tool.name, view.name, view.selected, view.provenance)
            }
        }
    }

    private val normalizedContracts: List<ToolSpec> by lazy {
        val byId = semanticContracts.associateBy(ToolSpec::id)
        HexanaToolCatalog.contracts.map { canonical -> byId.getValue(canonical.id) }
    }

    private fun toolDocument(tool: ToolSpec): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(tool.id.value))
        put("summary", JsonPrimitive(tool.summary.trim()))
        put("guidance", tool.guidance?.trim()?.takeIf(String::isNotBlank)?.let(::JsonPrimitive) ?: JsonNull)
        put("inputSchema", schema(tool.input))
        put("outputSchema", schema(tool.output))
        put("runtimeAvailability", JsonPrimitive("not evaluated"))
        put("requirements", JsonArray(emptyList()))
        put("views", buildJsonObject { views(tool).forEach { put(it.name, it.json()) } })
    }

    private fun cliProductDocument(product: CliProductSpec): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(product.id.value))
        put("executableName", JsonPrimitive(product.executableName))
        put("description", JsonPrimitive(product.description))
        put("selections", buildJsonArray {
            normalizedContracts.mapNotNull { contract -> cliTools.singleOrNull { it.id == contract.id } }
                .filter { product.id in it.binding.products }
                .forEach { tool -> add(buildJsonObject {
                    put("tool", JsonPrimitive(tool.id.value))
                    put("command", JsonPrimitive(tool.binding.commandName))
                }) }
        })
    }

    private fun views(tool: ToolSpec): List<View> {
        val idea = ideaTools.firstOrNull { it.id == tool.id }
        val standalone = standaloneAuthority.registrations.firstOrNull { it.id == tool.id }
        val cli = cliTools.firstOrNull { it.id == tool.id }
        val mcpbSelected = tool.id in standaloneAuthority.defaultToolIds
        return listOf(
            View("idea", idea != null, idea?.let { buildJsonObject { put("inputPattern", JsonPrimitive(pattern(it.inputPattern))); put("handlerFqn", JsonPrimitive(it.handlerFqn)) } }, if (idea != null) "IDEA default selection and binding" else "not selected by IDEA default authority"),
            View("standaloneRegistration", standalone != null, standalone?.let { buildJsonObject { put("handler", JsonPrimitive(it.standaloneHandler.methodName)) } }, if (standalone != null) "standalone registration authority" else "not registered by standalone authority"),
            profileView("standaloneDefault", tool.id, standaloneAuthority.defaultToolIds, "standalone default profile"),
            profileView("standaloneAdvanced", tool.id, standaloneAuthority.advancedToolIds, "standalone advanced profile"),
            profileView("standaloneScripting", tool.id, standaloneAuthority.scriptingToolIds, "standalone scripting profile"),
            View("mcpb", mcpbSelected, null, if (mcpbSelected) "derived exactly from standalone default profile" else "excluded because it is absent from standalone default profile"),
            cliView(cli),
        )
    }

    private fun profileView(name: String, id: ToolId, selected: List<ToolId>, profile: String) =
        View(name, id in selected, null, if (id in selected) "$profile selection" else "excluded by $profile selection")

    private fun cliView(cli: CliTool?): View =
        if (cli == null) View("cli", false, null, "not selected by CLI authority")
        else View("cli", true, buildJsonObject {
            put("command", JsonPrimitive(cli.binding.commandName))
            put("groups", strings(cli.binding.groups.map { it.name }.sorted()))
            put("outputPolicy", JsonPrimitive(cli.binding.outputPolicy.name))
            put("executionStatus", JsonPrimitive(cli.binding.executionStatus.name))
            put("outputOverride", cli.binding.outputOverride?.let { JsonPrimitive(it.name) } ?: JsonNull)
            put("defaultOutput", JsonPrimitive(cli.binding.defaultOutput.name))
            put("formats", strings(cliFormats(cli).sorted()))
            put("inputBinding", cli.binding.inputModel?.let(::inputBinding) ?: JsonNull)
            put("products", strings(cli.binding.products.map { it.value }.sorted()))
        }, "CLI binding authority")

    private fun inputBinding(model: ToolInputModel): JsonObject = buildJsonObject {
        put("sources", buildJsonArray {
            model.sources.sortedBy { it.name }.forEach { source -> add(buildJsonObject {
                put("name", JsonPrimitive(source.name)); put("kind", JsonPrimitive(source.kind.name))
                put("target", JsonPrimitive(if (source.target == SourceTargetSpec.RootInput) "rootInput" else (source.target as SourceTargetSpec.Field).name))
                put("required", JsonPrimitive(source.required)); put("bindings", strings(source.cli.bindings().map { "${it.kind}:${it.value}" }.sorted()))
                put("autoStdin", JsonPrimitive(source.cli.autoStdin))
            }) }
        })
        put("params", buildJsonArray {
            model.params.sortedBy { it.name }.forEach { param -> add(buildJsonObject {
                put("name", JsonPrimitive(param.name)); put("kind", JsonPrimitive(param.kind.name)); put("targetField", JsonPrimitive(param.targetField))
                put("cliOption", param.cliOption?.let(::JsonPrimitive) ?: JsonNull)
            }) }
        })
    }

    private fun cliFormats(cli: CliTool): List<String> =
        (cli.binding.outputOverride?.let(::listOf) ?: cli.output.outputCapabilities.toList())
            .mapNotNull {
                when (it) {
                    OutputConversionCapability.SemanticJson -> "JSON"
                    OutputConversionCapability.Pretty -> "PRETTY"
                    OutputConversionCapability.Table -> "TABLE"
                    OutputConversionCapability.RawText, OutputConversionCapability.RawBytes -> "RAW"
                    OutputConversionCapability.McpWrapper -> null
                }
            }.distinct()

    private fun schema(spec: ClassSpec): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(spec.name)); put("kotlinFqn", spec.kotlinFqn?.let(::JsonPrimitive) ?: JsonNull)
        put("oneOf", spec.oneOf?.let(::strings) ?: JsonNull)
        put("inputCapabilities", strings(spec.inputCapabilities.map { it.name }.sorted()))
        put("outputCapabilities", strings(spec.outputCapabilities.map { it.name }.sorted()))
        put("fields", buildJsonArray { spec.fields.forEach { add(field(it)) } })
    }

    private fun field(field: FieldSpec): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(field.name)); put("wireName", JsonPrimitive(field.schemaKey)); put("type", type(field.type))
        put("nullable", JsonPrimitive(field.nullable)); put("default", value(field.default)); put("description", field.describe?.let(::JsonPrimitive) ?: JsonNull)
        put("serializerFqn", field.serializerFqn?.let(::JsonPrimitive) ?: JsonNull); put("minimum", field.minimum?.let(::JsonPrimitive) ?: JsonNull)
        put("maximum", field.maximum?.let(::JsonPrimitive) ?: JsonNull); put("maxItems", schemaInt(field.maxItems)); put("maxLength", schemaInt(field.maxLength))
        put("itemMinimum", field.itemMinimum?.let(::JsonPrimitive) ?: JsonNull); put("itemMaximum", field.itemMaximum?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun type(type: FieldType): JsonObject = buildJsonObject { when (type) {
        FieldType.Int32 -> put("kind", JsonPrimitive("int32"))
        FieldType.Int64 -> put("kind", JsonPrimitive("int64"))
        FieldType.Float64 -> put("kind", JsonPrimitive("float64"))
        FieldType.UInt32 -> put("kind", JsonPrimitive("uint32"))
        FieldType.ByteT -> put("kind", JsonPrimitive("byte"))
        FieldType.BooleanT -> put("kind", JsonPrimitive("boolean"))
        FieldType.StringT -> put("kind", JsonPrimitive("string"))
        FieldType.ByteArrayT -> put("kind", JsonPrimitive("byteArray"))
        FieldType.JsonObjectT -> put("kind", JsonPrimitive("jsonObject"))
        is FieldType.ListOf -> { put("kind", JsonPrimitive("list")); put("element", type(type.element)) }
        is FieldType.MapOf -> { put("kind", JsonPrimitive("map")); put("value", type(type.value)) }
        is FieldType.ClassRef -> { put("kind", JsonPrimitive("class")); put("schema", schema(type.clazz)) }
    } }

    private fun value(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> error("Unsupported schema default value type: ${value::class.qualifiedName}")
    }
    private fun schemaInt(value: SchemaIntValue?): JsonElement = when (value) {
        null -> JsonNull
        is SchemaIntValue.Literal -> JsonPrimitive(value.value)
        SchemaIntValue.MaxInlineWasmBytes -> JsonPrimitive("maxInlineWasmBytes")
        SchemaIntValue.MaxInlineWasmBase64Length -> JsonPrimitive("maxInlineWasmBase64Length")
        SchemaIntValue.MaxInlineBinaryBytes -> JsonPrimitive("maxInlineBinaryBytes")
        SchemaIntValue.MaxInlineBinaryBase64Length -> JsonPrimitive("maxInlineBinaryBase64Length")
    }
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun pattern(pattern: ToolInputPattern): String = when (pattern) {
        ToolInputPattern.WasmOnly -> "wasmOnly"
        ToolInputPattern.WasmPathLabel -> "wasmPathLabel"
        ToolInputPattern.WasmWithBaseline -> "wasmWithBaseline"
        ToolInputPattern.WasmWithBinding -> "wasmWithBinding"
        ToolInputPattern.FunctionPage -> "functionPage"
        ToolInputPattern.Funcidx -> "funcidx"
        ToolInputPattern.FunctionNameSearch -> "functionNameSearch"
        ToolInputPattern.ExportNameSearch -> "exportNameSearch"
        ToolInputPattern.Indices -> "indices"
        ToolInputPattern.SourceMappedIndices -> "sourceMappedIndices"
        ToolInputPattern.WasmFrames -> "wasmFrames"
        ToolInputPattern.SourceLocations -> "sourceLocations"
        is ToolInputPattern.ClosedRange -> "closedRange(parameter=${pattern.parameterName})"
        ToolInputPattern.DataSegments -> "dataSegments"
        ToolInputPattern.DataPayloadPolicyAudit -> "dataPayloadPolicyAudit"
        ToolInputPattern.LocalStackBudgetAudit -> "localStackBudgetAudit"
        ToolInputPattern.ArtifactQuery -> "artifactQuery"
        ToolInputPattern.PolicyWithBaselineAndLog -> "policyWithBaselineAndLog"
        ToolInputPattern.GlobalStatePolicyAudit -> "globalStatePolicyAudit"
        ToolInputPattern.RuntimeCompatibility -> "runtimeCompatibility"
        ToolInputPattern.ComponentSurface -> "componentSurface"
        ToolInputPattern.ComponentDependencies -> "componentDependencies"
        ToolInputPattern.ComponentCompatibility -> "componentCompatibility"
        ToolInputPattern.BinaryOnly -> "binaryOnly"
        ToolInputPattern.BoundedBinaryQuery -> "boundedBinaryQuery"
        ToolInputPattern.BinaryFunctionPage -> "binaryFunctionPage"
        ToolInputPattern.BinaryFunctionSearch -> "binaryFunctionSearch"
    }

    private fun StringBuilder.renderText(element: JsonElement, indent: Int) {
        val prefix = "  ".repeat(indent)
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (value is JsonObject || value is JsonArray) {
                    appendLine("$prefix$key:")
                    renderText(value, indent + 1)
                } else appendLine("$prefix$key: ${textValue(value)}")
            }
            is JsonArray -> element.forEach { value ->
                if (value is JsonObject || value is JsonArray) {
                    appendLine("$prefix-")
                    renderText(value, indent + 1)
                } else appendLine("$prefix- ${textValue(value)}")
            }
            else -> appendLine("$prefix${textValue(element)}")
        }
    }

    private fun textValue(element: JsonElement): String = when (element) {
        JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) JSON.encodeToString(JsonPrimitive.serializer(), element) else element.content
        else -> error("Text rendering expects a scalar, got $element")
    }

    private data class View(val name: String, val selected: Boolean, val details: JsonObject?, val provenance: String) {
        fun json(): JsonObject = buildJsonObject {
            put("selected", JsonPrimitive(selected)); put("provenance", JsonPrimitive(provenance)); put("details", details ?: JsonNull)
        }
    }

    companion object {
        internal fun requireNonBlankProvenance(toolName: String, viewName: String, selected: Boolean, provenance: String) {
            require(provenance.isNotBlank()) {
                "Tool '$toolName' view '$viewName' has blank ${if (selected) "selection" else "exclusion"} provenance."
            }
        }

        private val JSON = Json { prettyPrint = true }
    }
}
