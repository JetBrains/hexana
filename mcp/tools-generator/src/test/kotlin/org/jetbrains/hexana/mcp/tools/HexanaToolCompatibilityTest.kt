package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertEquals

class HexanaToolCompatibilityTest {
    private val baseline = CompatibilityAuthorities()

    @Test
    fun `semantic contract fingerprint is reviewed independently`() {
        assertFingerprint("semantic", semanticSnapshot(baseline.semanticContracts), EXPECTED_SEMANTIC)
    }

    @Test
    fun `IDEA authority fingerprint is reviewed independently`() {
        assertFingerprint("IDEA", ideaSnapshot(baseline.ideaTools), EXPECTED_IDEA)
    }

    @Test
    fun `standalone authority fingerprint is reviewed independently`() {
        assertFingerprint("standalone", standaloneSnapshot(baseline.standaloneAuthority), EXPECTED_STANDALONE)
    }

    @Test
    fun `MCPB fingerprint follows standalone default membership`() {
        assertFingerprint("MCPB", mcpbSnapshot(baseline.standaloneAuthority.defaultToolIds), EXPECTED_MCPB)
    }

    @Test
    fun `CLI registry fingerprint is reviewed independently`() {
        assertFingerprint("CLI", cliSnapshot(baseline.cliTools), EXPECTED_CLI)
    }

    @Test
    fun `per-product fingerprints are reviewed independently`() {
        assertFingerprint("hexana-cli", productSnapshot(CliReleaseProduct.HexanaCli, baseline.cliTools), EXPECTED_HEXANA_CLI)
        assertFingerprint(
            "hexana-cli-script",
            productSnapshot(CliReleaseProduct.HexanaCliScript, baseline.cliTools),
            EXPECTED_HEXANA_CLI_SCRIPT,
        )
    }

    @Test
    fun `CLI membership-only mutation affects only CLI and product snapshots`() {
        val mutated = baseline.cliTools.map { tool ->
            if (tool.name == "summarize_binary") {
                tool.copy(
                    binding = tool.binding.copy(
                        releaseProducts = tool.binding.releaseProducts + CliReleaseProduct.HexanaCliScript,
                    )
                )
            } else {
                tool
            }
        }

        assertOnlySnapshotsChanged(
            baseline.copy(cliTools = mutated),
            "CLI",
            CliReleaseProduct.HexanaCliScript.productId,
        )
    }

    @Test
    fun `IDEA membership-only mutation affects only IDEA snapshot`() {
        assertOnlySnapshotsChanged(
            baseline.copy(ideaTools = baseline.ideaTools.dropLast(1)),
            "IDEA",
        )
    }

    @Test
    fun `standalone default membership mutation also affects MCPB snapshot`() {
        val mutated = baseline.standaloneAuthority.copy(
            defaultToolIds = baseline.standaloneAuthority.defaultToolIds.dropLast(1),
        )

        assertOnlySnapshotsChanged(
            baseline.copy(standaloneAuthority = mutated),
            "standalone",
            "MCPB",
        )
    }

    @Test
    fun `semantic summary mutation affects only semantic snapshot`() {
        val mutated = baseline.semanticContracts.map { contract ->
            if (contract.name == "summarize_binary") {
                contract.copy(summary = contract.summary + " Reviewed semantic change.")
            } else {
                contract
            }
        }

        assertOnlySnapshotsChanged(
            baseline.copy(semanticContracts = mutated),
            "semantic",
        )
    }

    private data class CompatibilityAuthorities(
        val semanticContracts: List<ToolSpec> = HexanaTools,
        val ideaTools: List<IdeaTool> = HexanaIdeaTools,
        val standaloneAuthority: StandaloneToolAuthority = HexanaStandaloneAuthority,
        val cliTools: List<CliTool> = HexanaCliTools,
    )

    private fun assertOnlySnapshotsChanged(
        mutated: CompatibilityAuthorities,
        vararg expectedChangedSnapshots: String,
    ) {
        val baselineHashes = snapshotHashes(baseline)
        val mutatedHashes = snapshotHashes(mutated)
        val changedSnapshots = baselineHashes.keys.filterTo(linkedSetOf()) { key ->
            baselineHashes.getValue(key) != mutatedHashes.getValue(key)
        }
        assertEquals(expectedChangedSnapshots.toSet(), changedSnapshots)
    }

    private fun snapshotHashes(authorities: CompatibilityAuthorities): Map<String, String> = buildMap {
        put("semantic", semanticSnapshot(authorities.semanticContracts).sha256())
        put("IDEA", ideaSnapshot(authorities.ideaTools).sha256())
        put("standalone", standaloneSnapshot(authorities.standaloneAuthority).sha256())
        put("MCPB", mcpbSnapshot(authorities.standaloneAuthority.defaultToolIds).sha256())
        put("CLI", cliSnapshot(authorities.cliTools).sha256())
        CliReleaseProduct.entries.forEach { product ->
            put(product.productId, productSnapshot(product, authorities.cliTools).sha256())
        }
    }

    private fun semanticSnapshot(contracts: List<ToolSpec>): String = buildString {
        appendLine("schemaVersion=${HexanaToolSchemaCompatibility.schemaVersion}")
        appendLine("policy=${HexanaToolSchemaCompatibility.policy.compatibilityText()}")
        contracts.forEachIndexed { index, tool ->
            val prefix = "contract[$index]"
            appendLine("$prefix.id=${tool.id.value}")
            appendLine("$prefix.summary=${tool.summary.compatibilityText()}")
            appendLine("$prefix.guidance=${tool.guidance.orEmpty().compatibilityText()}")
            appendClass("$prefix.input", tool.input)
            appendClass("$prefix.output", tool.output)
        }
    }

    private fun ideaSnapshot(tools: List<IdeaTool>): String = buildString {
        tools.forEachIndexed { index, tool ->
            appendLine("idea[$index].id=${tool.id.value}")
            appendLine("idea[$index].pattern=${tool.inputPattern.compatibilityText()}")
            appendLine("idea[$index].handler=${tool.handlerFqn}")
        }
    }

    private fun standaloneSnapshot(authority: StandaloneToolAuthority): String = buildString {
        appendLine("default=${authority.defaultToolIds.joinToString { it.value }}")
        appendLine("advanced=${authority.advancedToolIds.joinToString { it.value }}")
        appendLine("scripting=${authority.scriptingToolIds.joinToString { it.value }}")
        authority.registrations.forEachIndexed { index, tool ->
            appendLine("standalone[$index].id=${tool.id.value}")
            appendLine("standalone[$index].handler=${tool.standaloneHandler.methodName}")
        }
    }

    private fun mcpbSnapshot(ids: List<ToolId>): String = buildString {
        ids.forEachIndexed { index, id ->
            appendLine("mcpb[$index].id=${id.value}")
        }
    }

    private fun cliSnapshot(tools: List<CliTool>): String = buildString {
        tools.forEachIndexed { index, tool ->
            val prefix = "cli[$index]"
            val binding = tool.binding
            appendLine("$prefix.id=${tool.id.value}")
            appendLine("$prefix.command=${binding.commandName}")
            appendLine("$prefix.groups=${binding.groups.joinToString { it.name }}")
            appendLine("$prefix.outputPolicy=${binding.outputPolicy.name}")
            appendLine("$prefix.execution=${binding.executionStatus.name}")
            appendLine("$prefix.outputOverride=${binding.outputOverride?.name.orEmpty()}")
            appendLine("$prefix.defaultOutput=${binding.defaultOutput.name}")
            appendLine("$prefix.products=${binding.releaseProducts.joinToString { it.name }}")
            appendInputModel("$prefix.input", binding.inputModel)
        }
    }

    private fun productSnapshot(product: CliReleaseProduct, tools: List<CliTool>): String = buildString {
        appendLine("product=${product.name}")
        appendLine("productId=${product.productId}")
        appendLine("executableName=${product.executableName}")
        tools.filter { product in it.binding.releaseProducts }.forEachIndexed { index, tool ->
            appendLine("selection[$index].id=${tool.id.value}")
            appendLine("selection[$index].command=${tool.binding.commandName}")
            appendLine("selection[$index].output=${tool.binding.outputPolicy.name}")
            appendLine("selection[$index].default=${tool.binding.defaultOutput.name}")
        }
    }

    private fun StringBuilder.appendInputModel(prefix: String, model: ToolInputModel?) {
        if (model == null) {
            appendLine("$prefix=none")
            return
        }
        model.sources.forEachIndexed { index, source ->
            appendLine("$prefix.source[$index].name=${source.name}")
            appendLine("$prefix.source[$index].kind=${source.kind.name}")
            appendLine("$prefix.source[$index].target=${source.target.compatibilityText()}")
            appendLine("$prefix.source[$index].required=${source.required}")
            appendLine("$prefix.source[$index].positional=${source.cli.positionalPathName.orEmpty()}")
            appendLine("$prefix.source[$index].path=${source.cli.pathOption.orEmpty()}")
            appendLine("$prefix.source[$index].stdin=${source.cli.stdinOption.orEmpty()}")
            appendLine("$prefix.source[$index].base64=${source.cli.base64Option.orEmpty()}")
            appendLine("$prefix.source[$index].bytes=${source.cli.bytesOption.orEmpty()}")
            appendLine("$prefix.source[$index].autoStdin=${source.cli.autoStdin}")
        }
        model.params.forEachIndexed { index, param ->
            appendLine("$prefix.param[$index].name=${param.name}")
            appendLine("$prefix.param[$index].kind=${param.kind.name}")
            appendLine("$prefix.param[$index].target=${param.targetField}")
            appendLine("$prefix.param[$index].option=${param.cliOption.orEmpty()}")
        }
    }

    private fun StringBuilder.appendClass(prefix: String, spec: ClassSpec) {
        appendLine("$prefix.name=${spec.name}")
        appendLine("$prefix.kotlinFqn=${spec.kotlinFqn.orEmpty()}")
        appendLine("$prefix.oneOf=${spec.oneOf.orEmpty().joinToString()}")
        appendLine("$prefix.inputCapabilities=${spec.inputCapabilities.joinToString { it.name }}")
        appendLine("$prefix.outputCapabilities=${spec.outputCapabilities.joinToString { it.name }}")
        spec.fields.forEachIndexed { index, field -> appendField("$prefix.field[$index]", field) }
    }

    private fun StringBuilder.appendField(prefix: String, field: FieldSpec) {
        appendLine("$prefix.name=${field.name}")
        appendLine("$prefix.schemaKey=${field.schemaKey}")
        appendLine("$prefix.type=${field.type.compatibilityText()}")
        appendLine("$prefix.nullable=${field.nullable}")
        appendLine("$prefix.default=${field.default.compatibilityValueText()}")
        appendLine("$prefix.description=${field.describe.orEmpty().compatibilityText()}")
        appendLine("$prefix.serializer=${field.serializerFqn.orEmpty()}")
        appendLine("$prefix.minimum=${field.minimum.compatibilityValueText()}")
        appendLine("$prefix.maximum=${field.maximum.compatibilityValueText()}")
        appendLine("$prefix.maxItems=${field.maxItems.compatibilityValueText()}")
        appendLine("$prefix.maxLength=${field.maxLength.compatibilityValueText()}")
        appendLine("$prefix.itemMinimum=${field.itemMinimum.compatibilityValueText()}")
        appendLine("$prefix.itemMaximum=${field.itemMaximum.compatibilityValueText()}")
        when (val type = field.type) {
            is FieldType.ClassRef -> appendClass("$prefix.class", type.clazz)
            is FieldType.ListOf -> appendNestedType("$prefix.items", type.element)
            is FieldType.MapOf -> appendNestedType("$prefix.values", type.value)
            else -> Unit
        }
    }

    private fun StringBuilder.appendNestedType(prefix: String, type: FieldType) {
        when (type) {
            is FieldType.ClassRef -> appendClass("$prefix.class", type.clazz)
            is FieldType.ListOf -> appendNestedType("$prefix.items", type.element)
            is FieldType.MapOf -> appendNestedType("$prefix.values", type.value)
            else -> Unit
        }
    }

    private fun ToolInputPattern.compatibilityText(): String =
        if (this is ToolInputPattern.ClosedRange) "ClosedRange($parameterName)" else requireNotNull(this::class.simpleName)

    private fun SourceTargetSpec.compatibilityText(): String = when (this) {
        SourceTargetSpec.RootInput -> "root"
        is SourceTargetSpec.Field -> "field:$name"
    }

    private fun FieldType.compatibilityText(): String = when (this) {
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

    private fun Any?.compatibilityValueText(): String = when (this) {
        null -> ""
        is ByteArray -> contentToString()
        is SchemaIntValue.Literal -> "Literal($value)"
        SchemaIntValue.MaxInlineBinaryBase64Length -> "MaxInlineBinaryBase64Length"
        SchemaIntValue.MaxInlineBinaryBytes -> "MaxInlineBinaryBytes"
        SchemaIntValue.MaxInlineWasmBase64Length -> "MaxInlineWasmBase64Length"
        SchemaIntValue.MaxInlineWasmBytes -> "MaxInlineWasmBytes"
        else -> toString().compatibilityText()
    }

    private fun String.compatibilityText(): String = replace("\r\n", "\n").replace("\\", "\\\\").replace("\n", "\\n")

    private fun assertFingerprint(name: String, snapshot: String, expected: String) {
        val actual = snapshot.sha256()
        assertEquals(expected, actual, "$name compatibility changed; reviewed fingerprint: $actual\n${snapshot.take(12_000)}")
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXPECTED_SEMANTIC = "16273f8db54ad9c7ff45f4eb780ca1ae705b6da77f3ea4288f7b57d33a733459"
        const val EXPECTED_IDEA = "b3d01605c58323500af1b4e1eeabfa8b6ff2e4fb659bfccac35c9869c379d378"
        const val EXPECTED_STANDALONE = "0395327fb451b08908345ce4e59bd1cf97bb05dd4bb1cb2165679a2744dd8b44"
        const val EXPECTED_MCPB = "d530ca73ea6bdcd8a8244ab0960e10c348d37932c02ff7bee2053a72760abe84"
        const val EXPECTED_CLI = "0855459ff37cb4c4df85220c5a9c6c461af1154b13e32ae4d6f3f98c94714e9a"
        const val EXPECTED_HEXANA_CLI = "35ec773de2d485a7b285a37240d517bbc9c789a37be2725f66d355c7bb9bc6b7"
        const val EXPECTED_HEXANA_CLI_SCRIPT = "3b97abef096e419f500f3113464664719f08b9554efe50685f7605f785cdb7f1"
    }
}
