package org.jetbrains.hexana.mcp.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCompatibilityDiffTest {
    private val baseline = ResolvedToolCatalogReport()

    @Test
    fun `CLI product membership mutation affects only CLI product membership`() {
        val target = HexanaCliTools.first { HexanaCliProductSpec.id in it.binding.products }
        val changed = ResolvedToolCatalogReport(
            cliTools = HexanaCliTools.map { tool ->
                if (tool.id == target.id) tool.copy(binding = tool.binding.copy(products = emptySet())) else tool
            },
        )

        val differences = ToolCompatibilityDiff.between(baseline, changed).differences
        assertEquals(2, differences.size)
        assertDifference(
            differences[0], "cli", "cli", ToolCompatibilityDiff.Category.Membership,
            "tool/${target.id.value}/view/cli/details/products/${HexanaCliProductSpec.id.value}",
        )
        assertDifference(
            differences[1], "cli", "product", ToolCompatibilityDiff.Category.Membership,
            "cliProduct/${HexanaCliProductSpec.id.value}/tool/${target.id.value}",
        )
    }

    @Test
    fun `CLI product descriptor changes have stable binding paths`() {
        val product = HexanaCliProductRegistry.product(HexanaCliProductSpec.id)!!
        val changed = ResolvedToolCatalogReport(
            cliProducts = CliProductRegistry(
                HexanaCliProductRegistry.products.map {
                    if (it.id == product.id) {
                        it.copy(executableName = "hexana-inspect", description = "Changed CLI description.")
                    } else {
                        it
                    }
                },
            ),
        )

        val differences = ToolCompatibilityDiff.between(baseline, changed).differences

        assertEquals(2, differences.size)
        assertDifference(
            differences[0], "cli", "product", ToolCompatibilityDiff.Category.Binding,
            "cliProduct/${product.id.value}/description",
        )
        assertDifference(
            differences[1], "cli", "product", ToolCompatibilityDiff.Category.Binding,
            "cliProduct/${product.id.value}/executableName",
        )
    }

    @Test
    fun `bound MCP product profile move changes standalone MCPB and product projection`() {
        val default = HexanaMcpProductSpec.profile(ProfileId("default"))!!
        val advanced = HexanaMcpProductSpec.profile(ProfileId("advanced"))!!
        val moved = default.toolIds.last()
        val product = HexanaMcpProductSpec.copy(
            profiles = HexanaMcpProductSpec.profiles.map { profile ->
                when (profile.id) {
                    default.id -> profile.copy(toolIds = default.toolIds.dropLast(1))
                    advanced.id -> profile.copy(toolIds = advanced.toolIds + moved)
                    else -> profile
                }
            },
        )
        val products = McpProductRegistry(listOf(product, FixtureMcpProductSpec))
        val authority = StandaloneToolAuthority(
            HexanaStandaloneAuthority.registrations, products, McpRuntimeBinding(product.id),
        )
        val differences = ToolCompatibilityDiff.between(
            baseline,
            ResolvedToolCatalogReport(standaloneAuthority = authority, mcpProducts = products),
        ).differences

        assertTrue(differences.any { it.surface == "standalone" && it.view == "standaloneDefault" })
        assertTrue(differences.any { it.surface == "standalone" && it.view == "standaloneAdvanced" })
        assertTrue(differences.any { it.surface == "mcpb" && it.view == "mcpb" })
        assertTrue(differences.any { it.path.startsWith("mcpProduct/${product.id.value}/profile/") })
        assertTrue(differences.all { it.category == ToolCompatibilityDiff.Category.Membership })
    }

    @Test
    fun `MCP product default binding has a stable compatibility path`() {
        val changedProduct = HexanaMcpProductSpec.copy(defaultProfileId = ProfileId("advanced"))
        val products = McpProductRegistry(listOf(changedProduct, FixtureMcpProductSpec))
        val differences = ToolCompatibilityDiff.between(baseline, ResolvedToolCatalogReport(mcpProducts = products)).differences

        assertTrue(differences.any {
            it.surface == "mcp" && it.category == ToolCompatibilityDiff.Category.Binding &&
                it.path == "mcpProduct/${changedProduct.id.value}/defaultProfile"
        })
    }

    @Test
    fun `MCP product profile aliases have binding compatibility paths`() {
        val changedProduct = HexanaMcpProductSpec.copy(
            profiles = HexanaMcpProductSpec.profiles.map { profile ->
                if (profile.id == ProfileId("advanced")) profile.copy(aliases = profile.aliases + "expert") else profile
            },
        )
        val products = McpProductRegistry(listOf(changedProduct, FixtureMcpProductSpec))

        val difference = ToolCompatibilityDiff.between(baseline, ResolvedToolCatalogReport(mcpProducts = products)).differences.single {
            it.path == "mcpProduct/${changedProduct.id.value}/profile/advanced/aliases/expert"
        }

        assertDifference(
            difference, "mcp", "product", ToolCompatibilityDiff.Category.Binding,
            "mcpProduct/${changedProduct.id.value}/profile/advanced/aliases/expert",
        )
    }

    @Test
    fun `schema default and bound mutations have precise stable paths`() {
        val tool = HexanaTools.first { it.name == "list_functions" }
        val field = tool.input.fields.first { it.name == "limit" }
        val changedTool = tool.copy(input = tool.input.copy(fields = tool.input.fields.map {
            if (it.name == field.name) {
                val changedDefault = (field.default as Int) + 1
                it.copy(
                    nullable = !field.nullable,
                    default = changedDefault,
                    maximum = 500,
                    presence = FieldPresence.Defaulted(FieldDefaultValue.IntValue(changedDefault)),
                )
            } else {
                it
            }
        }))
        val changed = ResolvedToolCatalogReport(semanticContracts = HexanaTools.map { if (it.id == tool.id) changedTool else it })
        val differences = ToolCompatibilityDiff.between(baseline, changed).differences

        assertEquals(3, differences.size)
        assertDifference(differences[0], "semantic", "input", ToolCompatibilityDiff.Category.Defaults, "tool/${tool.id.value}/input/field/${field.schemaKey}/default")
        assertDifference(differences[1], "semantic", "input", ToolCompatibilityDiff.Category.Bounds, "tool/${tool.id.value}/input/field/${field.schemaKey}/maximum")
        assertDifference(differences[2], "semantic", "input", ToolCompatibilityDiff.Category.Schema, "tool/${tool.id.value}/input/field/${field.schemaKey}/nullable")
        assertTrue(ToolCompatibilityDiff.between(baseline, changed).text().contains("defaults tool/${tool.id.value}/input/field/${field.schemaKey}/default"))
    }

    @Test
    fun `binding mutation is isolated to its view`() {
        val target = HexanaIdeaTools.first()
        val changed = ResolvedToolCatalogReport(ideaTools = HexanaIdeaTools.map { tool ->
            if (tool.id == target.id) tool.copy(binding = tool.binding.copy(handlerFqn = "org.jetbrains.hexana.mcp.query.changedHandler")) else tool
        })
        val differences = ToolCompatibilityDiff.between(baseline, changed).differences

        assertEquals(1, differences.size)
        assertDifference(differences.single(), "idea", "idea", ToolCompatibilityDiff.Category.Binding, "tool/${target.id.value}/view/idea/details/handlerFqn")
        assertFalse(differences.single().path.contains('['))
    }

    @Test
    fun `standalone invocation variant and typed metadata have stable binding paths`() {
        val target = HexanaStandaloneTools.single { it.id == ListFunctionsToolId }
        val typedInvocation = target.standaloneInvocation as StandaloneInvocationSpec.TypedRequest
        fun report(invocation: StandaloneInvocationSpec) = ResolvedToolCatalogReport(
            standaloneAuthority = HexanaStandaloneAuthority.copy(
                registrations = HexanaStandaloneAuthority.registrations.map { tool ->
                    if (tool.id == target.id) tool.copy(binding = tool.binding.copy(invocation = invocation)) else tool
                },
            ),
        )
        fun differences(invocation: StandaloneInvocationSpec) =
            ToolCompatibilityDiff.between(baseline, report(invocation)).differences

        val requestTypeDifference = differences(typedInvocation.copy(requestTypeName = "ChangedListFunctionsRequest")).single()
        assertDifference(
            requestTypeDifference, "standalone", "standaloneRegistration", ToolCompatibilityDiff.Category.Binding,
            "tool/${target.id.value}/view/standaloneRegistration/details/requestTypeName",
        )

        val inputFailureDifference = differences(typedInvocation.copy(invalidInputMethodName = "changedListFunctionsInputFailure")).single()
        assertDifference(
            inputFailureDifference, "standalone", "standaloneRegistration", ToolCompatibilityDiff.Category.Binding,
            "tool/${target.id.value}/view/standaloneRegistration/details/invalidInputMethodName",
        )

        val variantDifferences = differences(StandaloneInvocationSpec.JsonArguments(typedInvocation.methodName))
        assertEquals(
            setOf(
                "tool/${target.id.value}/view/standaloneRegistration/details/invocationKind",
                "tool/${target.id.value}/view/standaloneRegistration/details/requestTypeName",
                "tool/${target.id.value}/view/standaloneRegistration/details/invalidInputMethodName",
            ),
            variantDifferences.mapTo(mutableSetOf()) { it.path },
        )
        assertTrue(variantDifferences.all {
            it.surface == "standalone" && it.view == "standaloneRegistration" &&
                it.category == ToolCompatibilityDiff.Category.Binding
        })
    }

    @Test
    fun `nested schemas and CLI arrays use stable semantic paths`() {
        val before = document("""{"tools":[{"id":"example","inputSchema":{"oneOf":["a"],"fields":[{"wireName":"outer","type":{"kind":"list","element":{"kind":"class","schema":{"fields":[{"wireName":"limit","default":1,"maximum":2}]}}}}]},"outputSchema":{},"views":{"cli":{"selected":true,"details":{"sources":[{"name":"wasm","kind":"Wasm"}],"params":[{"name":"offset","cliOption":"--offset"}],"groups":["Module"],"formats":["JSON"],"products":["hexana-cli"]}}}}]}""")
        val after = document("""{"tools":[{"id":"example","inputSchema":{"oneOf":["a","b"],"fields":[{"wireName":"outer","type":{"kind":"list","element":{"kind":"class","schema":{"fields":[{"wireName":"limit","default":3,"maximum":4},{"wireName":"added","type":"string"}]}}}}]},"outputSchema":{},"views":{"cli":{"selected":true,"details":{"sources":[{"name":"wasm","kind":"Binary"}],"params":[{"name":"offset","cliOption":"--from"},{"name":"limit","cliOption":"--limit"}],"groups":["Module","Function"],"formats":["JSON","TABLE"],"products":["hexana-cli-script"]}}}}]}""")
        val differences = ToolCompatibilityDiff.between(before, after).differences.associateBy { it.path }

        assertValues(differences.getValue("tool/example/input/field/outer/type/element/schema/field/limit/default"), ToolCompatibilityDiff.Category.Defaults, JsonPrimitive(1), JsonPrimitive(3))
        assertValues(differences.getValue("tool/example/input/field/outer/type/element/schema/field/limit/maximum"), ToolCompatibilityDiff.Category.Bounds, JsonPrimitive(2), JsonPrimitive(4))
        assertValues(differences.getValue("tool/example/input/field/outer/type/element/schema/field/added"), ToolCompatibilityDiff.Category.Schema, null, document("""{"wireName":"added","type":"string"}"""))
        assertValues(differences.getValue("tool/example/input/oneOf/b"), ToolCompatibilityDiff.Category.Membership, null, JsonPrimitive("b"))
        assertValues(differences.getValue("tool/example/view/cli/details/source/wasm/kind"), ToolCompatibilityDiff.Category.Binding, JsonPrimitive("Wasm"), JsonPrimitive("Binary"))
        assertValues(differences.getValue("tool/example/view/cli/details/param/offset/cliOption"), ToolCompatibilityDiff.Category.Binding, JsonPrimitive("--offset"), JsonPrimitive("--from"))
        assertValues(differences.getValue("tool/example/view/cli/details/param/limit"), ToolCompatibilityDiff.Category.Binding, null, document("""{"name":"limit","cliOption":"--limit"}"""))
        assertValues(differences.getValue("tool/example/view/cli/details/groups/Function"), ToolCompatibilityDiff.Category.Binding, null, JsonPrimitive("Function"))
        assertValues(differences.getValue("tool/example/view/cli/details/formats/TABLE"), ToolCompatibilityDiff.Category.Binding, null, JsonPrimitive("TABLE"))
        assertValues(differences.getValue("tool/example/view/cli/details/products/hexana-cli"), ToolCompatibilityDiff.Category.Membership, JsonPrimitive("hexana-cli"), null)
    }

    @Test
    fun `diff main writes deterministic JSON and text from real reports`() {
        val target = HexanaCliTools.first { HexanaCliProductSpec.id in it.binding.products }
        val before = Files.createTempFile("tool-report-before", ".json")
        val after = Files.createTempFile("tool-report-after", ".json")
        val first = Files.createTempDirectory("tool-diff-first")
        val second = Files.createTempDirectory("tool-diff-second")
        try {
            Files.writeString(before, baseline.json())
            Files.writeString(after, ResolvedToolCatalogReport(cliTools = HexanaCliTools.map {
                if (it.id == target.id) it.copy(binding = it.binding.copy(products = emptySet())) else it
            }).json())
            runDiffMain(before, after, first)
            runDiffMain(before, after, second)
            listOf("tool-compatibility-diff.json", "tool-compatibility-diff.txt").forEach { name ->
                assertEquals(Files.readAllBytes(first.resolve(name)).toList(), Files.readAllBytes(second.resolve(name)).toList())
            }
            val difference = Json.parseToJsonElement(Files.readString(first.resolve("tool-compatibility-diff.json"))).jsonObject
                .getValue("differences").jsonArray.map { it.jsonObject }.single {
                    it.getValue("path").jsonPrimitive.content == "tool/${target.id.value}/view/cli/details/products/${HexanaCliProductSpec.id.value}"
                }
            assertEquals("cli", difference.getValue("surface").jsonPrimitive.content)
            assertEquals("membership", difference.getValue("category").jsonPrimitive.content)
            assertEquals(JsonPrimitive(HexanaCliProductSpec.id.value), difference.getValue("before"))
            assertEquals(JsonNull, difference.getValue("after"))
        } finally {
            listOf(before, after, first, second).forEach { path -> if (Files.isDirectory(path)) deleteRecursively(path) else Files.deleteIfExists(path) }
        }
    }

    private fun assertDifference(
        actual: ToolCompatibilityDiff.Difference, surface: String, view: String,
        category: ToolCompatibilityDiff.Category, path: String,
    ) {
        assertEquals(surface, actual.surface)
        assertEquals(view, actual.view)
        assertEquals(category, actual.category)
        assertEquals(path, actual.path)
    }

    private fun assertValues(actual: ToolCompatibilityDiff.Difference, category: ToolCompatibilityDiff.Category, before: Any?, after: Any?) {
        assertEquals(category, actual.category)
        assertEquals(before, actual.before)
        assertEquals(after, actual.after)
    }

    private fun document(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun runDiffMain(before: Path, after: Path, output: Path) {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
            "org.jetbrains.hexana.mcp.tools.generator.ToolCompatibilityDiffMainKt", before.toString(), after.toString(), output.toString(),
        ).redirectErrorStream(true).start()
        assertEquals(0, process.waitFor(), process.inputStream.bufferedReader().readText())
    }

    private fun deleteRecursively(directory: Path) {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
