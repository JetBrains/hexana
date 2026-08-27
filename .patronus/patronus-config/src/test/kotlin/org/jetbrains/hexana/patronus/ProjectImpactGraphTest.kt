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
        val expected = ProjectImpactSnapshot.serialize(ProjectImpactGraph(nodes, dependencies))

        repeat(10) { seed ->
            val random = Random(seed)
            val shuffled = ProjectImpactGraph(nodes.shuffled(random), dependencies.shuffled(random))
            assertEquals(expected, ProjectImpactSnapshot.serialize(shuffled))
        }
        val parsed = ProjectImpactSnapshot.parse(expected)
        assertEquals(expected, ProjectImpactSnapshot.serialize(parsed))
        assertEquals(
            listOf("compileOnly", "wasmJsMainImplementation", "jvmTestImplementation", "integrationTestRuntimeOnly"),
            parsed.dependencies.map(DeclaredProjectDependency::configuration),
        )
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
    fun `shadow policies classify infrastructure and benchmark projects`() {
        assertEquals(
            mapOf(
                ":jvm-jit-jmh" to ShadowImpactPolicy.INTELLIJ_IMMEDIATE,
                ":patronus-config" to ShadowImpactPolicy.INFRASTRUCTURE_ONLY,
            ),
            HexanaShadowImpactPolicies.byProjectPath,
        )
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
