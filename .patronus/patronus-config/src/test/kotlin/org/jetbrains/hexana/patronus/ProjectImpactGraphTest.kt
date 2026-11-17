package org.jetbrains.hexana.patronus

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectImpactGraphTest {
    @Test
    fun `provider changes impact transitive consumers`() {
        val graph = graph("B" to "A", "C" to "B")

        assertEquals(listOf(":A", ":B", ":C"), graph.reverseImpactClosure(listOf(":A")).map(ProjectNode::path))
        assertEquals(listOf(":B", ":C"), graph.reverseImpactClosure(listOf(":B")).map(ProjectNode::path))
    }

    @Test
    fun `diamond dependencies are de-duplicated`() {
        val graph = graph("B" to "A", "C" to "A", "D" to "B", "D" to "C")

        assertEquals(listOf(":A", ":B", ":C", ":D"), graph.reverseImpactClosure(listOf(":A")).map(ProjectNode::path))
    }

    @Test
    fun `cycles terminate and include every impacted project once`() {
        val graph = graph("B" to "A", "C" to "B", "A" to "C")

        assertEquals(listOf(":A", ":B", ":C"), graph.reverseImpactClosure(listOf(":A")).map(ProjectNode::path))
    }

    @Test
    fun `snapshot is deterministic for shuffled input`() {
        val nodes = listOf(node("A"), node("B"), node("C"))
        val dependencies = listOf(
            dependency("B", "A", "compileOnly"),
            dependency("C", "A", "jvmTestImplementation"),
            dependency("C", "B", "integrationTestRuntimeOnly"),
            dependency("B", "A", "wasmJsMainImplementation"),
        )
        val laneSeeds = mapOf(CORE_LANE to listOf(":B", ":A"), INTELLIJ_LANE to listOf(":C"))
        val expected = ProjectImpactSnapshot.serialize(ProjectImpactGraph(nodes, dependencies, laneSeeds))

        repeat(10) { seed ->
            val random = Random(seed)
            val shuffled = ProjectImpactGraph(
                nodes.shuffled(random),
                dependencies.shuffled(random),
                laneSeeds.mapValues { (_, paths) -> paths.shuffled(random) },
            )
            assertEquals(expected, ProjectImpactSnapshot.serialize(shuffled))
        }
        val parsed = ProjectImpactSnapshot.parse(expected)
        assertEquals(expected, ProjectImpactSnapshot.serialize(parsed))
        assertEquals(
            listOf("compileOnly", "wasmJsMainImplementation", "jvmTestImplementation", "integrationTestRuntimeOnly"),
            parsed.dependencies.map(DeclaredProjectDependency::configuration),
        )
        assertEquals(laneSeeds.mapValues { it.value.sorted() }.toSortedMap(), parsed.laneSeeds)
    }

    @Test
    fun `declaring configuration metadata is retained for every configuration kind`() {
        val configurationNames = listOf(
            "compileOnly",
            "implementation",
            "runtimeOnly",
            "testImplementation",
            "jvmMainImplementation",
            "integrationTestImplementation",
        )
        val graph = ProjectImpactGraph(
            nodes = listOf(node("A"), node("B")),
            dependencies = configurationNames.map { dependency("B", "A", it) },
        )

        assertEquals(configurationNames.sorted(), graph.dependencies.map(DeclaredProjectDependency::configuration))
        assertEquals(listOf(":A", ":B"), graph.reverseImpactClosure(listOf(":A")).map(ProjectNode::path))
    }

    @Test
    fun `adding an edge changes the reverse closure`() {
        val baseline = graph("B" to "A")
        val withNewEdge = graph("B" to "A", "C" to "B")

        assertEquals(listOf(":A", ":B"), baseline.reverseImpactClosure(listOf(":A")).map(ProjectNode::path))
        assertEquals(listOf(":A", ":B", ":C"), withNewEdge.reverseImpactClosure(listOf(":A")).map(ProjectNode::path))
    }

    @Test
    fun `explicit policies cover every exceptional relationship with reasons`() {
        assertEquals(
            RoutingPolicyKind.entries.toSet(),
            (HexanaRoutingPolicies.projectPolicies.values.map { it.kind } +
                HexanaRoutingPolicies.pathPolicies.map { it.kind }).toSet(),
        )
        assertEquals(setOf(INTELLIJ_LANE), HexanaRoutingPolicies.projectPolicies.getValue(":jvm-jit-jmh").forcedLanes)
        assertEquals(true, HexanaRoutingPolicies.projectPolicies.getValue(":patronus-config").suppressProductRouting)
        assertEquals(true, HexanaRoutingPolicies.projectPolicies.getValue(":ui-driver-tests").suppressProductRouting)
        assertEquals(
            setOf(":graalwasm:wasmprinter", ":graalwasm:wat2wasm"),
            HexanaRoutingPolicies.pathPolicies.single { it.pattern == "/wasm-modules/**" }.impactProjects,
        )
        assertEquals(
            emptyList(),
            (HexanaRoutingPolicies.projectPolicies.values.map { it.reason } + HexanaRoutingPolicies.pathPolicies.map { it.reason })
                .filter(String::isBlank),
        )
    }

    @Test
    fun `dependency fan-out selects owning and transitive consumer lanes`() {
        val nodes = listOf("A", "B", "C", "D", "E").map(::node)
        val graph = ProjectImpactGraph(
            nodes,
            listOf(dependency("B", "A", "implementation"), dependency("C", "B", "testRuntimeOnly")),
            mapOf(
                CORE_LANE to listOf(":A"),
                INTELLIJ_LANE to listOf(":B"),
                SERVER_CLI_LANE to listOf(":C"),
                VSCODE_LANE to listOf(":D"),
                WEBAPP_LANE to listOf(":E"),
            ),
        )
        val routing = PatronusRouting(graph, emptyMap(), emptyList())

        assertEquals(listOf(CORE_LANE, INTELLIJ_LANE, SERVER_CLI_LANE).sorted(), routing.lanesForProject(":A"))
        assertEquals(listOf(INTELLIJ_LANE, SERVER_CLI_LANE).sorted(), routing.lanesForProject(":B"))
        assertEquals(listOf(SERVER_CLI_LANE), routing.lanesForProject(":C"))
    }

    @Test
    fun `adding a dependency changes generated lane patterns`() {
        val nodes = listOf("A", "B", "C", "D", "E").map(::node)
        val seeds = mapOf(
            CORE_LANE to listOf(":A"), INTELLIJ_LANE to listOf(":B"), SERVER_CLI_LANE to listOf(":C"),
            VSCODE_LANE to listOf(":D"), WEBAPP_LANE to listOf(":E"),
        )
        val baseline = PatronusRouting(ProjectImpactGraph(nodes, emptyList(), seeds), emptyMap(), emptyList()).patternsByLane()
        val withDependency = PatronusRouting(
            ProjectImpactGraph(nodes, listOf(dependency("B", "A", "customIntegrationImplementation")), seeds),
            emptyMap(),
            emptyList(),
        ).patternsByLane()

        assertEquals(false, "/a/**" in baseline.getValue(INTELLIJ_LANE))
        assertEquals(true, "/a/**" in withDependency.getValue(INTELLIJ_LANE))
    }

    @Test
    fun `nested project directories generate canonical path patterns`() {
        val policyNodes = listOf(
            ProjectNode(":A", "nested/a"), node("B"), node("C"), node("D"), node("E"),
        )
        val routing = PatronusRouting(
            ProjectImpactGraph(
                policyNodes,
                emptyList(),
                mapOf(
                    CORE_LANE to listOf(":A"), INTELLIJ_LANE to listOf(":B"), SERVER_CLI_LANE to listOf(":C"),
                    VSCODE_LANE to listOf(":D"), WEBAPP_LANE to listOf(":E"),
                ),
            ),
            emptyMap(),
            emptyList(),
        )

        assertContains(routing.patternsByLane().getValue(CORE_LANE), "/nested/a/**")
    }

    @Test
    fun `project directories reject traversal external and non-normalized forms`() {
        listOf(
            "",
            "../external",
            "module/../../external",
            "/absolute/module",
            "module/./nested",
            "module//nested",
            "module/",
            "module\\nested",
        ).forEach { directory ->
            val failure = assertFailsWith<IllegalArgumentException> { ProjectNode(":module", directory) }
            assertContains(failure.message.orEmpty(), "canonical and repository-relative")
        }
    }

    @Test
    fun `TSV control characters and malformed Gradle names are rejected`() {
        listOf("module\tnested", "module\rnested", "module\nnested").forEach { directory ->
            assertFailsWith<IllegalArgumentException> { ProjectNode(":module", directory) }
        }
        listOf("module", "::module", ":module:", ":module/child", ":module\tchild").forEach { path ->
            val failure = assertFailsWith<IllegalArgumentException> { ProjectNode(path, "module") }
            assertContains(failure.message.orEmpty(), "colon-separated ASCII Gradle name segments")
        }
        listOf("", "test integration", "test:integration", "test\tintegration", "test\rintegration", "test\nintegration")
            .forEach { configuration ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    DeclaredProjectDependency(node("B"), node("A"), configuration)
                }
                assertContains(failure.message.orEmpty(), "non-empty ASCII Gradle name")
            }
        listOf("", "lane id", "lane:id", "lane\tid").forEach { laneId ->
            assertFailsWith<IllegalArgumentException> {
                ProjectImpactGraph(listOf(node("A")), emptyList(), mapOf(laneId to listOf(":A")))
            }
        }
    }

    @Test
    fun `dependency endpoints must use canonical node directories`() {
        val snapshot = listOf(
            ProjectImpactSnapshot.HEADER,
            "project\t:A\ta",
            "project\t:B\tb",
            "dependency\t:B\tdifferent-b\t:A\ta\timplementation",
        ).joinToString("\n", postfix = "\n")
        val failure = assertFailsWith<IllegalArgumentException> {
            ProjectImpactSnapshot.parse(snapshot)
        }

        assertContains(failure.message.orEmpty(), "contradict canonical project directories")
    }

    private fun graph(vararg edges: Pair<String, String>): ProjectImpactGraph {
        val nodeNames = edges.flatMap { it.toList() }.distinct()
        return ProjectImpactGraph(
            nodes = nodeNames.map(::node),
            dependencies = edges.map { (consumer, provider) -> dependency(consumer, provider, "implementation") },
        )
    }

    private fun node(name: String) = ProjectNode(":$name", name.lowercase())

    private fun dependency(consumer: String, provider: String, configuration: String) =
        DeclaredProjectDependency(node(consumer), node(provider), configuration)
}
