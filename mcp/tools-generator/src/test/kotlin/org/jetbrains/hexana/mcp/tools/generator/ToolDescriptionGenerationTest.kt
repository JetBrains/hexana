package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.ClassSpec
import org.jetbrains.hexana.mcp.tools.CliProductRegistry
import org.jetbrains.hexana.mcp.tools.CliProductSpec
import org.jetbrains.hexana.mcp.tools.FieldDefaultValue
import org.jetbrains.hexana.mcp.tools.FieldPresence
import org.jetbrains.hexana.mcp.tools.FieldSpec
import org.jetbrains.hexana.mcp.tools.FieldType
import org.jetbrains.hexana.mcp.tools.HexanaCliTools
import org.jetbrains.hexana.mcp.tools.HexanaCliProductRegistry
import org.jetbrains.hexana.mcp.tools.HexanaIdeaTools
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneAuthority
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneTools
import org.jetbrains.hexana.mcp.tools.HexanaTools
import org.jetbrains.hexana.mcp.tools.IdeaTool
import org.jetbrains.hexana.mcp.tools.IdeaToolBinding
import org.jetbrains.hexana.mcp.tools.IdeaTypedInvocationSpec
import org.jetbrains.hexana.mcp.tools.McpProductRegistry
import org.jetbrains.hexana.mcp.tools.McpProductSpec
import org.jetbrains.hexana.mcp.tools.McpProfileSpec
import org.jetbrains.hexana.mcp.tools.McpRuntimeBinding
import org.jetbrains.hexana.mcp.tools.ProfileId
import org.jetbrains.hexana.mcp.tools.ProductId
import org.jetbrains.hexana.mcp.tools.StandaloneInvocationSpec
import org.jetbrains.hexana.mcp.tools.StandaloneTool
import org.jetbrains.hexana.mcp.tools.StandaloneToolBinding
import org.jetbrains.hexana.mcp.tools.StandaloneToolAuthority
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.ToolInputPattern
import org.jetbrains.hexana.mcp.tools.UnknownFieldPolicy
import org.jetbrains.hexana.mcp.tools.WasmInputClass
import org.jetbrains.hexana.mcp.tools.compileSchemaPlan
import org.jetbrains.hexana.mcp.tools.class_
import org.jetbrains.hexana.mcp.tools.pathSource
import org.jetbrains.hexana.mcp.tools.base64Source
import org.jetbrains.hexana.mcp.tools.bytesSource
import org.jetbrains.hexana.mcp.tools.withExactOneSourceGroup
import org.jetbrains.hexana.mcp.tools.mcpDescription
import org.jetbrains.hexana.mcp.tools.id
import org.jetbrains.hexana.mcp.tools.name
import org.jetbrains.hexana.mcp.tools.SchemaIntValue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolDescriptionGenerationTest {
    private fun ideaSource(tools: List<IdeaTool> = HexanaIdeaTools): String = buildToolsetFile(tools).toString()

    private fun dispatcherSource(): String = buildDispatcherFile(HexanaStandaloneAuthority).toString()
    private fun metadataSource(): String = buildCliMetadataToolsetFile(HexanaCliTools).toString()

    @Test
    fun `resolved IDEA and standalone generators preserve descriptions and surface isolation`() {
        val idea = ideaSource()
        val standalone = dispatcherSource()
        val representative = HexanaStandaloneTools.single { it.name == "query_artifacts" }

        assertFalse(idea.contains(representative.mcpDescription.lines().first()))
        assertTrue(standalone.contains(representative.mcpDescription.lines().first()))
        profile("default").toolIds.forEach { defaultTool ->
            assertTrue(idea.contains("name = \"${defaultTool.value}\""))
        }
        assertEquals(29, Regex("fullyQualifiedName =").findAll(idea).count())
        assertFalse(idea.contains("name = \"query_binary\""))
        assertFalse(idea.contains("name = \"list_binary_functions\""))
        assertFalse(idea.contains("name = \"query_artifacts\""))
        assertFalse(idea.contains("run_binary_script"))
        assertFalse(idea.contains("wasm_to_wat"))
        assertTrue(standalone.contains("handlers.runBinaryScript(request.arguments)"))
        assertFalse(standalone.contains("wasm_to_wat"))
    }

    @Test
    fun `IDEA descriptions reference only registered IDEA tools`() {
        val knownToolNames = HexanaTools.mapTo(mutableSetOf()) { it.name }
        val ideaToolNames = HexanaIdeaTools.mapTo(mutableSetOf()) { it.name }
        val toolReference = Regex("`([a-z][a-z0-9_]*)`")

        HexanaIdeaTools.forEach { tool ->
            val unavailableReferences = toolReference.findAll(tool.mcpDescription)
                .map { it.groupValues[1] }
                .filter { it in knownToolNames && it !in ideaToolNames }
                .toSet()
            assertTrue(
                unavailableReferences.isEmpty(),
                "IDEA tool '${tool.name}' references unavailable tools: $unavailableReferences",
            )
        }
    }

    @Test
    fun `IDEA binary schemas and bodies preserve generic binary behavior`() {
        val idea = ideaSource()
        assertTrue(idea.contains("name = \"summarize_binary\""))
        assertTrue(idea.contains("\"bytesBase64\""))
        assertTrue(idea.contains("\"bytes\""))
        assertTrue(idea.contains("requireBinaryProviderInput"))
        assertTrue(idea.contains("val provider = input.provider"))
    }

    @Test
    fun `standalone schemas preserve binary and JSON object shapes`() {
        val dispatcher = dispatcherSource()
        assertTrue(dispatcher.contains("\"bytesBase64\""))
        assertTrue(dispatcher.contains("\"bytes\""))
        assertTrue(dispatcher.contains("buildJsonObject"))
        assertTrue(dispatcher.contains("maxItems"))
    }

    @Test
    fun `IDEA and standalone projections render normalized schema facets with explicit target differences`() {
        val nested = ClassSpec("Nested", listOf(
            FieldSpec("count", FieldType.Int32, serialName = "nestedCount", describe = "Nested count.", minimum = 0, maximum = 99),
            FieldSpec(
                "samples", FieldType.ListOf(FieldType.Int32), describe = "Nested samples.",
                maxItems = SchemaIntValue.MaxInlineWasmBytes, itemMinimum = 1, itemMaximum = 9,
            ),
            FieldSpec("label", FieldType.StringT, nullable = true, describe = "Nested label.", maxLength = SchemaIntValue.Literal(12)),
        ))
        val input = ClassSpec("ProjectionInput", listOf(
            FieldSpec(
                "frames", FieldType.MapOf(FieldType.ClassRef(nested)), serialName = "wireRange",
                describe = "Selected ranges.",
            ),
            FieldSpec(
                "crashReportPath", FieldType.BooleanT, serialName = "wireDefault", default = true,
                describe = "Whether exports are included.", presence = FieldPresence.Defaulted(FieldDefaultValue.BooleanValue(true)),
            ),
            FieldSpec(
                "sourceMapPath", FieldType.StringT, serialName = "wireNull", nullable = true,
                describe = "Optional globals mode.",
                presence = FieldPresence.Defaulted(FieldDefaultValue.Null),
            ),
            FieldSpec(
                "unselected", FieldType.StringT, serialName = "standaloneOnly", default = "hidden",
                presence = FieldPresence.Defaulted(FieldDefaultValue.StringValue("hidden")),
            ),
        ))
        val contract = ToolSpec(org.jetbrains.hexana.mcp.tools.ToolId("schema_plan_fixture"), "Schema fixture.", null, input, nested)
        val standaloneTool = StandaloneTool(
            contract,
            StandaloneToolBinding(
                contract.id,
                StandaloneInvocationSpec.JsonArguments("schemaPlanFixture"),
                UnknownFieldPolicy.Ignore,
            ),
        )
        val ideaTool = IdeaTool(
            contract,
            IdeaToolBinding(contract.id, ToolInputPattern.WasmFrames, "org.jetbrains.hexana.mcp.query.schemaPlanFixture", UnknownFieldPolicy.Ignore),
        )
        val standalone = buildDispatcherFile(standaloneAuthority(listOf(standaloneTool), emptyList(), emptyList(), emptyList())).toString()
        val idea = ideaSource(listOf(ideaTool))

        assertEquals(
            FieldDefaultValue.Null,
            compileSchemaPlan(input).root.fields.single { it.name == "sourceMapPath" }.defaultValue,
        )
        listOf(standalone, idea).forEach { source ->
            assertTrue(source.contains("put(\"wireRange\""), source)
            assertTrue(source.contains("put(\"nestedCount\""), source)
            assertTrue(source.contains("Nested samples."), source)
            assertTrue(source.contains("\"maxItems\", JsonPrimitive(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES)"), source)
            assertTrue(source.contains("\"maxLength\", JsonPrimitive(12)"), source)
            assertTrue(source.contains("\"minimum\", JsonPrimitive(0)"), source)
            assertTrue(source.contains("\"maximum\", JsonPrimitive(99)"), source)
            assertTrue(source.contains("\"minimum\", JsonPrimitive(1)"), source)
            assertTrue(source.contains("\"maximum\", JsonPrimitive(9)"), source)
            assertTrue(source.contains("put(\"default\", JsonPrimitive(true))"), source)
            assertFalse(source.contains("put(\"default\", JsonNull)"), source)
            assertTrue(source.contains("put(\"additionalProperties\""), source)
        }
        assertTrue(Regex("required = listOf\\(\\s*\\\"wireRange\\\"").containsMatchIn(standalone), standalone)
        assertTrue(idea.contains("McpToolSchema.ofPropertiesSchema("), idea)
        assertTrue(Regex("requiredProperties = setOf\\(\\\"path\\\", \\\"wireRange\\\"\\)").containsMatchIn(idea), idea)
        assertTrue(idea.contains("definitions = emptyMap<String, JsonElement>()"), idea)
        assertTrue(idea.contains("put(\"path\""), idea)
        assertTrue(idea.contains("put(\"projectPath\""), idea)
        assertTrue(idea.contains("\"wireRange\""), idea)
        assertFalse(idea.contains("standaloneOnly"), idea)
        assertTrue(standalone.contains("standaloneOnly"), standalone)
        assertFalse(idea.contains("oneOf"), idea)
        assertFalse(idea.contains("\"\$ref\""), idea)
    }

    @Test
    fun `IDEA and standalone output schemas require nullable scalar and class fields`() {
        val nested = ClassSpec("RequiredNullableNested", listOf(FieldSpec("value", FieldType.StringT)))
        val output = class_("RequiredNullableOutput") {
            requiredNullable("requiredNullableInt", FieldType.Int32)
            requiredNullable("requiredNullableNested", nested)
            nullable("optionalNullable", FieldType.StringT)
        }
        val fixtureId = HexanaStandaloneTools.first().contract.id
        val contract = ToolSpec(
            fixtureId,
            "Required nullable schema fixture.",
            null,
            HexanaIdeaTools.first().contract.input,
            output,
        )
        val ideaTool = HexanaIdeaTools.first().copy(
            contract = contract,
            binding = HexanaIdeaTools.first().binding.copy(id = contract.id),
        )
        val standaloneTool = StandaloneTool(
            contract,
            HexanaStandaloneTools.first().binding.copy(id = contract.id),
        )

        assertRequiredNullableOutput(
            buildToolsetFile(listOf(ideaTool)).toString(),
            "requiredProperties = setOf",
        )
        assertRequiredNullableOutput(
            buildDispatcherFile(standaloneAuthority(listOf(standaloneTool), listOf(contract.id), emptyList(), emptyList())).toString(),
            "required = listOf",
        )
    }

    @Test
    fun `non-public typed fixture derives its neutral request decoder from the schema plan`() {
        val input = WasmInputClass.with("TypedDecoderFixture") {
            int("offset", default = 0).minimum(0)
            int("limit", default = 200).minimum(1)
        }
        val tool = StandaloneTool(
            ToolSpec(org.jetbrains.hexana.mcp.tools.ListFunctionsToolId, "Typed decoder fixture.", null, input, input),
            StandaloneToolBinding(
                org.jetbrains.hexana.mcp.tools.ListFunctionsToolId,
                StandaloneInvocationSpec.TypedRequest(
                    "typedDecoderFixture",
                    "TypedDecoderFixtureRequest",
                    "typedDecoderFixtureInputFailure",
                ),
                org.jetbrains.hexana.mcp.tools.UnknownFieldPolicy.Ignore,
            ),
        )

        val source = buildDispatcherFile(
            standaloneAuthority(listOf(tool), listOf(tool.id), emptyList(), emptyList()),
        ).toString()

        assertTrue(source.contains("internal data class TypedDecoderFixtureRequest"), source)
        assertTrue(source.contains("internal fun decodeTypedDecoderFixtureRequest"), source)
        assertTrue(source.contains("internal data class TypedDecoderFixtureRequestScalars"), source)
        assertTrue(source.contains("internal fun decodeTypedDecoderFixtureRequestScalars"), source)
        assertTrue(source.contains("handlers.typedDecoderFixture(decodeTypedDecoderFixtureRequest(request.arguments))"), source)
        assertTrue(source.contains("handlers.typedDecoderFixtureInputFailure(error)"), source)
        assertTrue(source.contains("requireTypedDecoderFixtureRequestSourceGroup0"), source)
        assertTrue(source.contains("sourceGroup0Count"), source)
        assertTrue(source.contains("Exactly one of `path`, `bytesBase64`, or `bytes` is required"), source)
        assertTrue(source.contains("Provide exactly one of `path`, `bytesBase64`, or `bytes`"), source)
        assertTrue(source.contains("`offset` must be >= 0"), source)
        assertTrue(source.contains("`limit` must be >= 1"), source)
        assertTrue(source.contains("offset: JsonElement?"), source)
        assertTrue(source.contains("limit: JsonElement?"), source)
        assertTrue(source.contains("offset: Int?"), source)
        assertTrue(source.contains("limit: Int?"), source)
        assertTrue(source.contains("offsetElement.jsonPrimitive.intOrNull"), source)
        assertTrue(source.contains("limitElement.jsonPrimitive.intOrNull"), source)
        assertFalse(source.contains("HexanaResourceLimits"), source)
    }

    @Test
    fun `IDEA typed fixture derives source and ordinary fields without a tool-specific branch`() {
        val input = WasmInputClass.with("IdeaTypedFixtureInput") {
            int("offset", default = 0).minimum(0)
            int("limit", default = 200).minimum(1)
            int("pageSize", default = 7).serialName("page_size")
            int("when", default = 3)
            requiredNullable("nullableInt", FieldType.Int32)
            requiredNullable("nullableString", FieldType.StringT)
            requiredNullable("nullableBoolean", FieldType.BooleanT)
        }
        val contract = ToolSpec(
            org.jetbrains.hexana.mcp.tools.ToolId("idea_typed_fixture"), "IDEA typed fixture.", null,
            input, ClassSpec("IdeaTypedFixtureOutput", emptyList()),
        )
        val invocation = IdeaTypedInvocationSpec(
            "IdeaTypedFixtureRequest",
            pathSource(input.fieldRef("path")),
            listOf(
                input.fieldRef("offset"), input.fieldRef("limit"), input.fieldRef("pageSize"), input.fieldRef("when"),
                input.fieldRef("nullableInt"), input.fieldRef("nullableString"), input.fieldRef("nullableBoolean"),
            ),
        )
        val tool = IdeaTool(
            contract,
            IdeaToolBinding(contract.id, null, "org.jetbrains.hexana.mcp.query.readFunctions", UnknownFieldPolicy.Ignore, invocation),
        )

        val source = ideaSource(listOf(tool))
        assertTrue(source.contains("private data class IdeaTypedFixtureRequest"), source)
        assertTrue(source.contains("val pageSize = args.optionalIntArgument(\"page_size\") ?: 7"), source)
        assertTrue(source.contains("provider.readFunctions(request.offset, request.limit, request.pageSize, request.`when`, request.nullableInt, request.nullableString, request.nullableBoolean)"), source)
        assertTrue(source.contains("public val `when`: Int,"), source)
        assertTrue(source.contains("val `when` = args.optionalIntArgument(\"when\") ?: 3"), source)
        assertTrue(source.contains("IdeaTypedFixtureRequest(path, offset, limit, pageSize, `when`, nullableInt"), source)
        assertTrue(source.contains("put(\"page_size\""), source)
        assertTrue(source.contains("requiredProperties = setOf(\"path\", \"nullableInt\", \"nullableString\", \"nullableBoolean\")"), source)
        assertTrue(source.contains("public val nullableInt: Int?"), source)
        assertTrue(source.contains("public val nullableString: String?"), source)
        assertTrue(source.contains("public val nullableBoolean: Boolean?"), source)
        assertEquals(3, Regex("JsonNull -> null").findAll(source).count())
        assertTrue(source.contains("Missing required argument `nullableInt`"), source)
        assertTrue(source.contains("Missing required argument `nullableString`"), source)
        assertTrue(source.contains("Missing required argument `nullableBoolean`"), source)
        assertFalse(source.contains("put(\"bytesBase64\""), source)
        assertFalse(source.contains("put(\"bytes\""), source)

        val invalid = tool.copy(binding = tool.binding.copy(
            typedInvocation = invocation.copy(source = pathSource(WasmInputClass.fieldRef("path"))),
        ))
        val error = assertFailsWith<IllegalArgumentException> { ideaSource(listOf(invalid)) }
        assertTrue(error.message.orEmpty().contains("must select a source"), error.message)

        listOf("HexanaToolset", "HexanaMcpToolCompatibility").forEach { reservedName ->
            val colliding = tool.copy(binding = tool.binding.copy(
                typedInvocation = invocation.copy(requestTypeName = reservedName),
            ))
            val collision = assertFailsWith<IllegalArgumentException> { ideaSource(listOf(colliding)) }
            assertTrue(collision.message.orEmpty().contains("top-level type declarations collide: $reservedName"), collision.message)
        }
    }

    @Test
    fun `typed source-group helpers use stable indexed identifiers for legal group names`() {
        val sourceInput = class_("IndexedSourceGroupFixture") {
            nullable("path", of = FieldType.StringT)
            nullable("bytesBase64", of = FieldType.StringT)
            nullable("bytes", of = FieldType.ListOf(FieldType.Int32))
        }
        val input = sourceInput.withExactOneSourceGroup(
            "1 input",
            pathSource(sourceInput.fieldRef("path")),
            base64Source(sourceInput.fieldRef("bytesBase64")),
            bytesSource(sourceInput.fieldRef("bytes")),
        )
        val tool = StandaloneTool(
            ToolSpec(org.jetbrains.hexana.mcp.tools.ListFunctionsToolId, "Indexed source fixture.", null, input, input),
            StandaloneToolBinding(
                org.jetbrains.hexana.mcp.tools.ListFunctionsToolId,
                StandaloneInvocationSpec.TypedRequest("indexedSourceFixture", "IndexedSourceFixtureRequest", "indexedSourceFixtureInputFailure"),
                org.jetbrains.hexana.mcp.tools.UnknownFieldPolicy.Ignore,
            ),
        )

        val source = buildDispatcherFile(standaloneAuthority(listOf(tool), listOf(tool.id), emptyList(), emptyList())).toString()

        assertTrue(source.contains("requireIndexedSourceFixtureRequestSourceGroup0"), source)
        assertTrue(source.contains("sourceGroup0Count"), source)
        assertFalse(source.contains("1InputCount"), source)
    }

    @Test
    fun `typed adapters reject duplicate and cross-derived top-level declarations`() {
        fun typedTool(base: StandaloneTool, requestTypeName: String): StandaloneTool {
            val input = class_("${requestTypeName}Input") { int("offset", default = 0).minimum(0) }
            return StandaloneTool(
                base.contract.copy(input = input, output = input),
                base.binding.copy(
                    invocation = StandaloneInvocationSpec.TypedRequest(
                        methodName = "${base.name}Handler",
                        requestTypeName = requestTypeName,
                        invalidInputMethodName = "${base.name}InputFailure",
                    ),
                ),
            )
        }

        val firstBase = HexanaStandaloneTools[0]
        val secondBase = HexanaStandaloneTools[1]
        listOf(
            "SharedRequest" to "SharedRequest",
            "SharedRequest" to "SharedRequestScalars",
        ).forEach { (firstRequestType, secondRequestType) ->
            val first = typedTool(firstBase, firstRequestType)
            val second = typedTool(secondBase, secondRequestType)
            val error = assertFailsWith<IllegalArgumentException> {
                buildDispatcherFile(
                    standaloneAuthority(listOf(first, second), listOf(first.id, second.id), emptyList(), emptyList()),
                )
            }

            assertTrue(error.message.orEmpty().contains("colliding top-level type declarations"), error.message)
            assertTrue(error.message.orEmpty().contains("`SharedRequestScalars`"), error.message)
        }
    }

    @Test
    fun `source alternatives preserve human-readable conjunctions`() {
        assertEquals("", formatBacktickedAlternatives(emptyList()))
        assertEquals("`path`", formatBacktickedAlternatives(listOf("path")))
        assertEquals("`path` or `bytes`", formatBacktickedAlternatives(listOf("path", "bytes")))
        assertEquals(
            "`path`, `bytesBase64`, or `bytes`",
            formatBacktickedAlternatives(listOf("path", "bytesBase64", "bytes")),
        )
    }

    @Test
    fun `standalone profiles preserve explicit disjoint membership`() {
        val source = dispatcherSource()
        assertTrue(source.contains("DEFAULT_TOOL_NAMES"))
        assertTrue(source.contains("ADVANCED_TOOL_NAMES"))
        assertTrue(source.contains("SCRIPTING_TOOL_NAMES"))
        profile("default").toolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
        profile("advanced").toolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
        profile("scripting").toolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
    }

    @Test
    fun `standalone generated metadata follows declared aliases and default profile`() {
        val tool = HexanaStandaloneTools.first()
        val product = McpProductSpec(
            ProductId("fixture-mcp"),
            listOf(
                McpProfileSpec(ProfileId("default"), emptyList()),
                McpProfileSpec(ProfileId("advanced"), listOf(tool.id), setOf("advanced", "all-tools")),
                McpProfileSpec(ProfileId("scripting"), emptyList()),
            ),
            ProfileId("advanced"), true,
        )
        val source = buildDispatcherFile(
            StandaloneToolAuthority(listOf(tool), McpProductRegistry(listOf(product)), McpRuntimeBinding(product.id)),
        ).toString()

        assertTrue(source.contains("DEFAULT_PROFILE_ID: String = \"advanced\""))
        assertTrue(source.contains("\"all-tools\" to \"advanced\""))
        assertTrue(source.contains("\"ADVANCED\" to \"advanced\""))
        assertTrue(source.contains("profile: HexanaMcpToolProfile = HexanaMcpToolProfile.defaultProfile()"))
        assertFalse(source.contains("profile: HexanaMcpToolProfile = HexanaMcpToolProfile.DEFAULT"))
        assertTrue(source.contains("\"${tool.name}\""))
    }

    @Test
    fun `CLI metadata preserves executable and TODO separation`() {
        val metadata = metadataSource()

        assertTrue(metadata.contains("executionStatus = HexanaCliExecutionStatus.TODO"))
        assertEquals(5, HexanaCliTools.count { it.binding.executionStatus == CliExecutionStatus.Executable })
    }

    @Test
    fun `standalone dispatcher uses the explicit handler binding`() {
        val original = HexanaStandaloneTools.single { it.name == "summarize_module" }
        val rebound = original.copy(
            binding = original.binding.copy(invocation = StandaloneInvocationSpec.JsonArguments("customSummaryHandler")),
        )

        val source = buildDispatcherFile(
            standaloneAuthority(listOf(rebound), listOf(rebound.contract.id), emptyList(), emptyList()),
        ).toString()

        assertTrue(source.contains("handlers.customSummaryHandler(request.arguments)"))
        assertFalse(source.contains("handlers.summarizeModule(request.arguments)"))
    }

    @Test
    fun `standalone generator uses only validated authority profile registrations`() {
        val registered = HexanaStandaloneTools.first()
        val unregistered = HexanaStandaloneTools.last()
        val minimal = standaloneAuthority(listOf(registered), listOf(registered.contract.id), emptyList(), emptyList())

        val source = buildDispatcherFile(minimal).toString()

        assertTrue(source.contains("\"${registered.name}\""))
        assertFalse(source.contains("\"${unregistered.name}\""))

        val error = assertFailsWith<IllegalArgumentException> {
            standaloneAuthority(listOf(registered), listOf(unregistered.contract.id), emptyList(), emptyList())
        }

        assertTrue(error.message.orEmpty().contains("profile 'default' references unregistered tool IDs"))
        assertTrue(error.message.orEmpty().contains(unregistered.name))
    }

    @Test
    fun `executable CLI bindings require an explicit input model`() {
        val binding = HexanaCliTools.single { it.name == "summarize_binary" }.binding

        val inputError = assertFailsWith<IllegalArgumentException> {
            binding.copy(inputModel = null)
        }

        assertTrue(inputError.message.orEmpty().contains("must declare an explicit input model"))
    }

    @Test
    fun `standalone generator emits server and metadata artifacts separately`() {
        val serverFiles = buildStandaloneServerFiles(HexanaStandaloneAuthority)
        val metadataFiles = buildStandaloneCliMetadataFiles(HexanaCliTools)
        assertEquals(listOf("HexanaToolDispatcher"), serverFiles.map { it.name })
        assertEquals(setOf("HexanaCliToolset", "HexanaCliProducts"), metadataFiles.map { it.name }.toSet())
        assertTrue(serverFiles.single { it.name == "HexanaToolDispatcher" }.toString().contains("registerHexanaTools"))
        assertFalse(metadataFiles.joinToString().contains("registerHexanaTools"))
    }

    @Test
    fun `CLI metadata emits declared fixture products without a closed enum`() {
        val fixture = CliProductSpec(ProductId("fixture-cli"), "fixture-cli", "Non-published fixture product.")
        val products = CliProductRegistry(HexanaCliProductRegistry.products + fixture)
        val target = HexanaCliTools.first { it.binding.executionStatus == CliExecutionStatus.Executable }
        val tools = HexanaCliTools.map { tool ->
            if (tool.id == target.id) tool.copy(binding = tool.binding.copy(products = setOf(fixture.id))) else tool
        }

        val source = buildCliProductSelectionFile(tools, products).toString()

        assertTrue(source.contains("FIXTURE_CLI: HexanaCliProductDescriptor"))
        assertTrue(source.contains("\"fixture-cli\" to listOf("))
        assertFalse(source.contains("enum class HexanaCliReleaseProduct"))
        assertFalse(source.contains("when ("))
    }

    private fun profile(id: String) = HexanaStandaloneAuthority.profile(ProfileId(id))

    private fun assertRequiredNullableOutput(source: String, requiredMarker: String) {
        val output = source.substringAfter("outputSchema =")
        val required = output.substringAfter(requiredMarker).substringBefore(")")

        assertTrue(required.contains("\"requiredNullableInt\""), "output required keys: $required")
        assertTrue(required.contains("\"requiredNullableNested\""), "output required keys: $required")
        assertFalse(required.contains("\"optionalNullable\""), "output required keys: $required")
        assertTrue(output.substringAfter("put(\"requiredNullableInt\"").take(240).contains("\"integer\""), output)
        assertTrue(output.substringAfter("put(\"requiredNullableInt\"").take(240).contains("\"null\""), output)
        assertTrue(output.substringAfter("put(\"requiredNullableNested\"").take(240).contains("\"object\""), output)
        assertTrue(output.substringAfter("put(\"requiredNullableNested\"").take(240).contains("\"null\""), output)
    }

    private fun standaloneAuthority(
        registrations: List<org.jetbrains.hexana.mcp.tools.StandaloneTool>, default: List<org.jetbrains.hexana.mcp.tools.ToolId>,
        advanced: List<org.jetbrains.hexana.mcp.tools.ToolId>, scripting: List<org.jetbrains.hexana.mcp.tools.ToolId>,
    ): StandaloneToolAuthority {
        val product = McpProductSpec(
            ProductId("fixture-mcp"),
            listOf(McpProfileSpec(ProfileId("default"), default), McpProfileSpec(ProfileId("advanced"), advanced), McpProfileSpec(ProfileId("scripting"), scripting)),
            ProfileId("default"), true,
        )
        return StandaloneToolAuthority(registrations, McpProductRegistry(listOf(product)), McpRuntimeBinding(product.id))
    }

}
