package org.jetbrains.hexana.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.model.artifacts.*
import org.jetbrains.hexana.mcp.model.binaries.*
import org.jetbrains.hexana.mcp.model.components.*
import org.jetbrains.hexana.mcp.model.converters.*
import org.jetbrains.hexana.mcp.model.data.*
import org.jetbrains.hexana.mcp.model.elements.*
import org.jetbrains.hexana.mcp.model.entrypoints.*
import org.jetbrains.hexana.mcp.model.exports.*
import org.jetbrains.hexana.mcp.model.features.*
import org.jetbrains.hexana.mcp.model.functions.*
import org.jetbrains.hexana.mcp.model.globals.*
import org.jetbrains.hexana.mcp.model.imports.*
import org.jetbrains.hexana.mcp.model.memory.*
import org.jetbrains.hexana.mcp.model.runtime.*
import org.jetbrains.hexana.mcp.model.scripting.*
import org.jetbrains.hexana.mcp.model.sections.*
import org.jetbrains.hexana.mcp.model.summary.*
import org.jetbrains.hexana.mcp.model.structural.*
import org.jetbrains.hexana.mcp.model.types.*
import org.jetbrains.hexana.mcp.tools.ClassSpec
import org.jetbrains.hexana.mcp.tools.FieldPresence
import org.jetbrains.hexana.mcp.tools.FieldSpec
import org.jetbrains.hexana.mcp.tools.FieldType
import org.jetbrains.hexana.mcp.tools.HexanaToolCatalog

/** Keeps the canonical DSL mirror and serializer-owned JSON contracts in lockstep. */
class OutputSchemaConformanceTest {
    @Test
    fun `catalog output declarations match Kotlin serializers`() {
        val catalog = HexanaToolCatalog.contracts.filter { it.output.kotlinFqn != null }
        val expected = catalog.map { it.output.kotlinFqn ?: error("${it.name} output has no Kotlin FQN") }.toSet()
        val duplicates = serializerEntries.groupBy { it.first }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "explicit serializer registry contains duplicate root serializers: $duplicates")
        val serializers = serializerEntries.toMap()
        assertEquals(expected, serializers.keys, "explicit serializer registry must exactly cover catalog roots")
        catalog.forEach { tool ->
            val output = tool.output
            compareClass(output, serializers.getValue(output.kotlinFqn ?: error("${tool.name} output has no Kotlin FQN")), "${tool.name}.result")
        }
    }

    @Test
    fun `custom sealed and opaque encodings are pinned`() {
        assertEquals("\"AAEC\"", HexanaJson.compact.encodeToString(Base64ByteArraySerializer, byteArrayOf(0, 1, 2)))
        assertEquals("SEALED", JsonElement.serializer().descriptor.kind.toString())
        assertEquals(
            "[7,null,{\"kind\":\"object\"}]",
            HexanaJson.compact.encodeToString(ListSerializer(JsonElement.serializer()), listOf(JsonPrimitive(7), JsonNull, JsonObject(mapOf("kind" to JsonPrimitive("object"))))),
        )
        val polymorphic = HexanaJson.compact.encodeToString(
            StructuralParseResult.serializer(),
            UnidentifiedStructuralParseResult(StructuralParseFailure(StructuralParseFailureKind.MALFORMED_MAGIC)),
        )
        assertEquals("{\"type\":\"unidentified\",\"failure\":{\"kind\":\"malformed_magic\"}}", polymorphic)

        val requiredOpaque = DataPayloadPolicyReportMatchResult(
            policyEntryId = "policy", forbiddenString = "needle", severity = "warning", segmentIndex = 0, mode = "passive",
            memory = JsonNull, offsetExpr = JsonNull, byteLength = 6, rawBytesConfirmed = true,
        )
        val nullJson = HexanaJson.compact.encodeToString(requiredOpaque)
        assertTrue("\"memory\":null" in nullJson, "audit_data_payload_policy.result.recommendedReport.matches[].memory must remain present when null")
        assertTrue("\"offset_expr\":null" in nullJson, "audit_data_payload_policy.result.recommendedReport.matches[].offset_expr must remain present when null")
        val primitiveJson = HexanaJson.compact.encodeToString(requiredOpaque.copy(memory = JsonPrimitive(3), offsetExpr = JsonPrimitive("i32.const 0")))
        assertTrue("\"memory\":3" in primitiveJson, "opaque memory must encode as an integer")
        assertTrue("\"offset_expr\":\"i32.const 0\"" in primitiveJson, "opaque offset_expr must encode as a string")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `HexanaJson policy determines effective output requiredness`() {
        assertTrue(HexanaJson.compact.configuration.encodeDefaults, "HexanaJson must encode defaults for effective requiredness")
        assertEquals(false, HexanaJson.compact.configuration.explicitNulls, "HexanaJson must omit ordinary explicit nulls")

        val nullable = HexanaJson.compact.encodeToString(DataEntryResult(kind = 0, index = 1, offset = 2, data = byteArrayOf()))
        assertTrue("\"memoryIndex\"" !in nullable, "list_data.result.returned[].memoryIndex must be omitted when null")
        assertTrue("\"expression\"" !in nullable, "list_data.result.returned[].expression must be omitted when null")

        val defaulted = HexanaJson.compact.encodeToString(
            ImportFunctionResult(funcidx = 0, module = "env", name = "f", locationDisplay = "import env.f", signature = null),
        )
        assertTrue("\"locationKind\":\"import\"" in defaulted, "list_imports.result.imports[].locationKind must remain present at its default")
    }

    private fun compareClass(expected: ClassSpec, serializer: KSerializer<*>, path: String) = compareClass(expected, serializer.descriptor, path)

    private fun compareClass(expected: ClassSpec, actual: SerialDescriptor, path: String) {
        assertEquals(StructureKind.CLASS, actual.kind, "$path must use a class serializer, got ${actual.kind}/${actual.serialName}")
        val actualNames = (0 until actual.elementsCount).map(actual::getElementName).toSet()
        val expectedNames = expected.fields.map(FieldSpec::schemaKey).toSet()
        assertEquals(expectedNames, actualNames, "$path wire fields")
        expected.fields.forEach { field ->
            val index = actual.getElementIndex(field.schemaKey)
            assertTrue(index >= 0, "$path.${field.schemaKey} is missing")
            val descriptor = actual.getElementDescriptor(index)
            val fieldPath = "$path.${field.schemaKey}"
            assertEquals(field.presence !is FieldPresence.Optional, !descriptor.isNullable, "$fieldPath effective requiredness with HexanaJson encodeDefaults=true and explicitNulls=false")
            compareField(field, descriptor, fieldPath)
        }
    }

    private fun compareField(field: FieldSpec, actual: SerialDescriptor, path: String) {
        if (path in opaqueRequiredNullablePaths) {
            assertTrue(field.nullable && field.presence is FieldPresence.Required, "$path must be required-nullable in the DSL")
            assertEquals("kotlinx.serialization.json.JsonElement", actual.serialName, "$path opaque serializer")
            return
        }
        assertEquals(field.nullable, actual.isNullable, "$path nullability")
        field.serializerFqn?.let { serializer ->
            assertEquals("org.jetbrains.hexana.mcp.model.data.Base64ByteArraySerializer", serializer, "$path unsupported withSerializer owner")
            assertEquals("Base64ByteArray", actual.serialName, "$path custom serializer descriptor owner")
            assertEquals(PrimitiveKind.STRING, actual.kind, "$path custom serializer wire shape")
            return
        }
        compareType(field.type, actual, path)
    }

    private fun compareType(type: FieldType, actual: SerialDescriptor, path: String) {
        when (type) {
        FieldType.Int32 -> scalar(PrimitiveKind.INT, actual, path)
        FieldType.Int64 -> scalar(PrimitiveKind.LONG, actual, path)
        FieldType.Float64 -> scalar(PrimitiveKind.DOUBLE, actual, path)
        FieldType.UInt32 -> assertEquals("kotlin.UInt", actual.serialName, "$path unsigned shape")
        FieldType.ByteT -> scalar(PrimitiveKind.BYTE, actual, path)
        FieldType.BooleanT -> scalar(PrimitiveKind.BOOLEAN, actual, path)
        FieldType.StringT -> scalar(PrimitiveKind.STRING, actual, path)
        FieldType.ByteArrayT -> error("$path has ByteArray without verified withSerializer metadata")
        FieldType.JsonObjectT -> assertTrue(actual.serialName.contains("Json"), "$path unsupported opaque JSON serializer ${actual.serialName}")
        is FieldType.ListOf -> {
            assertEquals(StructureKind.LIST, actual.kind, "$path list shape")
            compareType(type.element, actual.getElementDescriptor(0), "$path[]")
        }
        is FieldType.MapOf -> {
            assertEquals(StructureKind.MAP, actual.kind, "$path map shape")
            scalar(PrimitiveKind.STRING, actual.getElementDescriptor(0), "$path{key}")
            compareType(type.value, actual.getElementDescriptor(1), "$path{}")
        }
        is FieldType.ClassRef -> compareClass(type.clazz, actual, path)
        }
    }

    private fun scalar(expected: PrimitiveKind, actual: SerialDescriptor, path: String) =
        assertEquals(expected, actual.kind, "$path scalar shape (${actual.serialName})")

    private val opaqueRequiredNullablePaths = setOf(
        "audit_data_payload_policy.result.recommendedReport.matches[].memory",
        "audit_data_payload_policy.result.recommendedReport.matches[].offset_expr",
    )

    private val serializerEntries: List<Pair<String, KSerializer<*>>> = listOf(
        out(PlanAbiBindingRegenerationResult.serializer()), out(QueryArtifactsResult.serializer()), out(BinaryFunctionsResult.serializer()), out(BinarySummaryResult.serializer()), out(QueryBinaryResult.serializer()),
        out(CheckComponentCompatibilityResult.serializer()), out(ListComponentSurfaceResult.serializer()), out(ResolveComponentDependenciesResult.serializer()), out(WasmToWatResult.serializer()),
        out(AuditDataPayloadPolicyResult.serializer()), out(ListDataSectionsResult.serializer()), out(ListDataSegmentsResult.serializer()), out(ListElementSegmentsResult.serializer()), out(EntrypointPolicyResult.serializer()),
        out(ListExportedFunctionsResult.serializer()), out(ListExportsResult.serializer()), out(SearchExportsResult.serializer()), out(AuditLocalStackBudgetResult.serializer()), out(DisassembleFunctionResult.serializer()), out(FunctionsResult.serializer()),
        out(GetFunctionCallGraphResult.serializer()), out(GetInstructionsForFunctionsResult.serializer()), out(GetLocalsForFunctionsResult.serializer()), out(ListCodeSectionOffsetsResult.serializer()), out(ResolveFunctionSignatureResult.serializer()), out(ResolveFunctionSymbolsResult.serializer()),
        out(ResolveSourceLocationsResult.serializer()), out(ResolveWasmFramesResult.serializer()), out(SearchFunctionsByNameResult.serializer()), out(AuditGlobalStatePolicyResult.serializer()), out(ListGlobalsResult.serializer()), out(ListImportsResult.serializer()),
        out(CheckMemoryContractResult.serializer()), out(ListMemoryResult.serializer()), out(ListCustomSectionsResult.serializer()), out(ListEnabledFeaturesResult.serializer()), out(SummarizeModuleResult.serializer()), out(CheckRuntimeCompatibilityResult.serializer()),
        out(DiffRequiredProposalsResult.serializer()), out(ListRequiredProposalsResult.serializer()), out(RunBinaryScriptResult.serializer()), out(RunScriptSuiteResult.serializer()), out(ListReferenceTypesResult.serializer()), out(ListValueTypesResult.serializer()), out(TypesResult.serializer()),
    )

    private fun out(serializer: KSerializer<*>): Pair<String, KSerializer<*>> = serializer.descriptor.serialName to serializer
}
