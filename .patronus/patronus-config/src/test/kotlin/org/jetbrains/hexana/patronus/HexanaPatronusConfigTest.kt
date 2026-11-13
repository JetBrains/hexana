package org.jetbrains.hexana.patronus

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaPatronusConfigTest {
    private val repositoryRoot = Path.of(System.getProperty("hexana.repositoryRoot"))
    private val graph = ProjectImpactSnapshot.parse(Path.of(System.getProperty("hexana.projectImpactGraph")).readText())
    private val routing = PatronusRouting(graph)

    @Test
    fun `generated config matches the checked-in YAML`() {
        assertEquals(
            repositoryRoot.resolve(".patronus/config.yaml").readText(),
            HexanaPatronusConfigGenerator.expectedYaml(graph),
        )
    }

    @Test
    fun `generated config uses independent dependency-derived product checks`() {
        val yaml = HexanaPatronusConfigGenerator.expectedYaml(graph)
        val configurationIds = Regex("configurationId: \\\"([^\\\"]+)\\\"").findAll(yaml).map { it.groupValues[1] }.toList()
        val infrastructureIds = setOf(
            "InnoHub_HexFlow_PatronusConfigCheckPatronus",
            "InnoHub_HexFlow_LeakGuardPatronus",
            "InnoHub_HexFlow_UiTestsPatronus",
        )
        assertEquals(PRODUCT_LANES + infrastructureIds, configurationIds.toSet())
        assertEquals(8, configurationIds.distinct().size)
        assertFalse("InnoHub_HexFlow_SafeMergeTests" in configurationIds)
        assertEquals(false, "  group:" in yaml)
        assertEquals(false, "isFallback:" in yaml)

        val checks = hexanaPatronusConfig(graph).teamCityChecksConfig.checks
        val freshness = checks.single { it.configurationId == "InnoHub_HexFlow_PatronusConfigCheckPatronus" }
        assertEquals(listOf("*"), freshness.executeOnChanges)
        val leakGuard = checks.single { it.configurationId == "InnoHub_HexFlow_LeakGuardPatronus" }
        assertEquals(listOf("*"), leakGuard.executeOnChanges)
        val ui = checks.single { it.configurationId == "InnoHub_HexFlow_UiTestsPatronus" }
        assertEquals(listOf("/ui-driver-tests/**", "/idea-plugin/src/ui-tests-framework/**"), ui.executeOnChanges)
        PRODUCT_LANES.forEach { lane ->
            val expectedPatterns = routing.patternsByLane().getValue(lane)
            assertEquals(expectedPatterns.distinct(), expectedPatterns)
            assertTrue(expectedPatterns.none { it.startsWith("/.patronus/") })
            assertEquals(expectedPatterns, checks.single { it.configurationId == lane }.executeOnChanges)
        }
    }

    @Test
    fun `representative paths select exactly their immediate checks`() {
        val allProductLanes = PRODUCT_LANES.toSet()
        listOf(
            RouteCase("/wasmParser/src/commonMain/kotlin/Parser.kt", allProductLanes),
            RouteCase("/idea-plugin/src/main/kotlin/Plugin.kt", setOf(INTELLIJ_LANE)),
            RouteCase("/vscode-plugin/extension-lib/src/commonMain/kotlin/Extension.kt", setOf(VSCODE_LANE)),
            RouteCase("/mcp/server/src/main/kotlin/Server.kt", setOf(SERVER_CLI_LANE)),
            RouteCase("/webapp/src/jsMain/kotlin/App.kt", setOf(WEBAPP_LANE)),
            RouteCase("/build.gradle.kts", allProductLanes),
            RouteCase(
                "/license/third_party/components.json",
                setOf(INTELLIJ_LANE, VSCODE_LANE, WEBAPP_LANE, SERVER_CLI_LANE),
            ),
            RouteCase("/ui-driver-tests/src/test/kotlin/UiTest.kt", emptySet(), ui = true),
            RouteCase("/.patronus/patronus-config/build.gradle.kts", emptySet()),
            RouteCase("/README.md", emptySet()),
            RouteCase("/docs/architecture.md", emptySet()),
            RouteCase("/doc/reference.md", emptySet()),
            RouteCase("/tools/check.sh", emptySet()),
            RouteCase("/plugins/example/plugin.xml", emptySet()),
            RouteCase("/.space/automation.kts", emptySet()),
            RouteCase("/CODEOWNERS", emptySet()),
        ).forEach { case ->
            val actual = linkedSetOf("InnoHub_HexFlow_PatronusConfigCheckPatronus", "InnoHub_HexFlow_LeakGuardPatronus")
            actual += routing.lanesForPath(case.path)
            if (case.ui) actual += "InnoHub_HexFlow_UiTestsPatronus"

            assertEquals(
                setOf("InnoHub_HexFlow_PatronusConfigCheckPatronus", "InnoHub_HexFlow_LeakGuardPatronus") + case.productLanes +
                    if (case.ui) setOf("InnoHub_HexFlow_UiTestsPatronus") else emptySet(),
                actual,
                case.path,
            )
            assertFalse("InnoHub_HexFlow_SafeMergeTests" in actual, case.path)
        }
    }

    @Test
    fun `live graph routes providers to every consumer lane`() {
        graph.nodes.filterNot { it.path == ":" }.forEach { project ->
            val policy = HexanaRoutingPolicies.projectPolicies[project.path]
            if (policy?.suppressProductRouting != true) assertTrue(routing.lanesForProject(project.path).isNotEmpty())
        }
        graph.dependencies.forEach { dependency ->
            routing.lanesForProject(dependency.consumer.path).forEach { consumerLane ->
                assertContains(routing.lanesForProject(dependency.provider.path), consumerLane)
            }
        }
        assertEquals(emptyList(), routing.lanesForProject(":patronus-config"))
        assertEquals(listOf(INTELLIJ_LANE), routing.lanesForProject(":jvm-jit-jmh"))
        assertEquals(emptyList(), routing.lanesForProject(":ui-driver-tests"))
    }

    @Test
    fun `overlays remain explicit and effective`() {
        val patterns = routing.patternsByLane()
        PRODUCT_LANES.forEach { lane -> assertContains(patterns.getValue(lane), "/build.gradle.kts") }
        val converterLanes = setOf(":graalwasm:wasmprinter", ":graalwasm:wat2wasm")
            .flatMap(routing::lanesForProject).toSet()
        converterLanes.forEach { lane -> assertContains(patterns.getValue(lane), "/wasm-modules/**") }
        routing.lanesForProject(":wasmParser").forEach { lane ->
            assertContains(patterns.getValue(lane), "/karma.config.d/**")
        }
        mapOf(
            "/mcp/tools-generator/src/main/kotlin/Tool.kt" to setOf(INTELLIJ_LANE, SERVER_CLI_LANE),
            "/examples/scripting/example.js" to setOf(INTELLIJ_LANE),
            "/parsing/native-fixtures/elf/sample.c" to setOf(CORE_LANE, WEBAPP_LANE),
            "/graalwasm/wasmprinter/src/main/kotlin/Printer.kt" to
                setOf(CORE_LANE, INTELLIJ_LANE, SERVER_CLI_LANE, WEBAPP_LANE),
            "/vscode-plugin/media/editor.css" to setOf(VSCODE_LANE, WEBAPP_LANE),
        ).forEach { (path, expectedLanes) ->
            assertEquals(expectedLanes, routing.lanesForPath(path).toSet(), path)
        }
    }

    private data class RouteCase(
        val path: String,
        val productLanes: Set<String>,
        val ui: Boolean = false,
    )
}
