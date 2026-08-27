package org.jetbrains.hexana.patronus

import java.nio.file.Path
import kotlin.io.path.readText

data class ProjectNode(
    val path: String,
    val relativeDirectory: String,
) : Comparable<ProjectNode> {
    init {
        require(GRADLE_PROJECT_PATH.matches(path)) {
            "Project path must be ':' or colon-separated ASCII Gradle name segments: $path"
        }
        require(relativeDirectory.isCanonicalRepositoryRelativeDirectory()) {
            "Project directory must be canonical and repository-relative: $relativeDirectory"
        }
    }

    override fun compareTo(other: ProjectNode): Int = compareValuesBy(this, other, ProjectNode::path, ProjectNode::relativeDirectory)
}

data class DeclaredProjectDependency(
    val consumer: ProjectNode,
    val provider: ProjectNode,
    val configuration: String,
) : Comparable<DeclaredProjectDependency> {
    init {
        require(GRADLE_CONFIGURATION_NAME.matches(configuration)) {
            "Declaring configuration must be a non-empty ASCII Gradle name without separators or control characters: $configuration"
        }
    }

    override fun compareTo(other: DeclaredProjectDependency): Int =
        compareValuesBy(
            this,
            other,
            { it.consumer.path },
            { it.provider.path },
            DeclaredProjectDependency::configuration,
            { it.consumer.relativeDirectory },
            { it.provider.relativeDirectory },
        )
}

class ProjectImpactGraph(
    nodes: Iterable<ProjectNode>,
    dependencies: Iterable<DeclaredProjectDependency>,
) {
    val nodes: List<ProjectNode> = nodes.distinct().sorted()
    val dependencies: List<DeclaredProjectDependency> = dependencies.distinct().sorted()

    private val nodesByPath = this.nodes.associateBy(ProjectNode::path)
    private val consumersByProvider = this.dependencies
        .groupBy { it.provider.path }
        .mapValues { (_, edges) -> edges.map { it.consumer.path }.distinct().sorted() }

    init {
        require(nodesByPath.size == this.nodes.size) { "Project paths must map to exactly one directory" }
        val unknownPaths = this.dependencies
            .flatMap { listOf(it.consumer.path, it.provider.path) }
            .filterNot(nodesByPath::containsKey)
            .distinct()
            .sorted()
        require(unknownPaths.isEmpty()) { "Dependencies reference unknown projects: ${unknownPaths.joinToString()}" }
        val contradictoryEndpoints = this.dependencies
            .flatMap { listOf(it.consumer, it.provider) }
            .filter { nodesByPath.getValue(it.path) != it }
            .distinct()
            .sorted()
        require(contradictoryEndpoints.isEmpty()) {
            "Dependency endpoints contradict canonical project directories: ${contradictoryEndpoints.joinToString()}"
        }
    }

    fun reverseImpactClosure(changedProjectPaths: Iterable<String>): List<ProjectNode> {
        val pending = ArrayDeque(changedProjectPaths.toSet().sorted())
        val impactedPaths = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            require(nodesByPath.containsKey(path)) { "Unknown changed project: $path" }
            if (!impactedPaths.add(path)) continue
            consumersByProvider[path].orEmpty().forEach(pending::addLast)
        }
        return impactedPaths.map(nodesByPath::getValue).sorted()
    }
}

private val GRADLE_PROJECT_PATH = Regex(""":|(?::[A-Za-z0-9][A-Za-z0-9._-]*)+""")
private val GRADLE_CONFIGURATION_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

private fun String.isCanonicalRepositoryRelativeDirectory(): Boolean {
    if (isEmpty() || any { it == '\t' || it == '\r' || it == '\n' || it.code < 0x20 || it == '\u007f' }) return false
    if (this == ".") return true
    if (startsWith('/') || endsWith('/') || '\\' in this) return false
    return split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}

enum class ShadowImpactPolicy {
    INFRASTRUCTURE_ONLY,
    INTELLIJ_IMMEDIATE,
}

object HexanaShadowImpactPolicies {
    val byProjectPath: Map<String, ShadowImpactPolicy> = sortedMapOf(
        ":jvm-jit-jmh" to ShadowImpactPolicy.INTELLIJ_IMMEDIATE,
        ":patronus-config" to ShadowImpactPolicy.INFRASTRUCTURE_ONLY,
    )
}

object ProjectImpactSnapshot {
    const val HEADER = "hexana-project-impact-graph\t1"

    fun parse(text: String): ProjectImpactGraph {
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == HEADER) { "Unsupported project-impact snapshot header: ${lines.firstOrNull()}" }
        val nodes = mutableListOf<ProjectNode>()
        val dependencies = mutableListOf<DeclaredProjectDependency>()
        lines.drop(1).forEachIndexed { index, line ->
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "project" -> {
                    require(fields.size == 3) { "Invalid project record at line ${index + 2}: $line" }
                    nodes += ProjectNode(fields[1], fields[2])
                }
                "dependency" -> {
                    require(fields.size == 6) { "Invalid dependency record at line ${index + 2}: $line" }
                    dependencies += DeclaredProjectDependency(
                        consumer = ProjectNode(fields[1], fields[2]),
                        provider = ProjectNode(fields[3], fields[4]),
                        configuration = fields[5],
                    )
                }
                else -> error("Unknown project-impact record at line ${index + 2}: $line")
            }
        }
        return ProjectImpactGraph(nodes, dependencies)
    }

    fun serialize(graph: ProjectImpactGraph): String = buildString {
        appendLine(HEADER)
        graph.nodes.forEach { appendLine("project\t${it.path}\t${it.relativeDirectory}") }
        graph.dependencies.forEach {
            appendLine(
                "dependency\t${it.consumer.path}\t${it.consumer.relativeDirectory}\t" +
                    "${it.provider.path}\t${it.provider.relativeDirectory}\t${it.configuration}",
            )
        }
    }
}

object ProjectImpactSnapshotVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = Path.of(args.singleOrNull() ?: error("Expected project-impact snapshot path"))
        val actual = path.readText()
        check(actual == ProjectImpactSnapshot.serialize(ProjectImpactSnapshot.parse(actual))) {
            "$path is not a canonical project-impact snapshot"
        }
    }
}
