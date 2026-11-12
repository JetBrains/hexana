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
    laneSeeds: Map<String, Iterable<String>> = emptyMap(),
) {
    val nodes: List<ProjectNode> = nodes.distinct().sorted()
    val dependencies: List<DeclaredProjectDependency> = dependencies.distinct().sorted()
    val laneSeeds: Map<String, List<String>> = laneSeeds.toSortedMap().mapValues { (_, paths) -> paths.distinct().sorted() }

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
        val unknownLaneSeeds = this.laneSeeds.values.flatten().filterNot(nodesByPath::containsKey).distinct().sorted()
        require(unknownLaneSeeds.isEmpty()) { "Lane seeds reference unknown projects: ${unknownLaneSeeds.joinToString()}" }
        require(this.laneSeeds.keys.all(TEAMCITY_CONFIGURATION_ID::matches)) {
            "Lane IDs must be non-empty ASCII TeamCity configuration IDs without separators or control characters"
        }
        require(this.laneSeeds.values.none(List<String>::isEmpty)) { "Every product lane must have at least one seed project" }
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
private val TEAMCITY_CONFIGURATION_ID = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

private fun String.isCanonicalRepositoryRelativeDirectory(): Boolean {
    if (isEmpty() || any { it == '\t' || it == '\r' || it == '\n' || it.code < 0x20 || it == '\u007f' }) return false
    if (this == ".") return true
    if (startsWith('/') || endsWith('/') || '\\' in this) return false
    return split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}

enum class RoutingPolicyKind {
    GLOBAL_GRADLE_INPUT,
    NON_GRADLE_ROOT,
    TASK_ONLY_LINK,
    UI_EXEMPTION,
    BENCHMARK_POLICY,
    INFRASTRUCTURE_EXEMPTION,
}

data class ProjectRoutingPolicy(
    val projectPath: String,
    val kind: RoutingPolicyKind,
    val forcedLanes: Set<String> = emptySet(),
    val suppressProductRouting: Boolean = false,
    val reason: String,
)

data class PathRoutingPolicy(
    val pattern: String,
    val kind: RoutingPolicyKind,
    val directLanes: Set<String> = emptySet(),
    val impactProjects: Set<String> = emptySet(),
    val reason: String,
)

object HexanaRoutingPolicies {
    val projectPolicies = listOf(
        ProjectRoutingPolicy(
            ":jvm-jit-jmh",
            RoutingPolicyKind.BENCHMARK_POLICY,
            forcedLanes = setOf(INTELLIJ_LANE),
            reason = "JMH source changes run IntelliJ verification, but the immediate lane has no benchmark task.",
        ),
        ProjectRoutingPolicy(
            ":patronus-config",
            RoutingPolicyKind.INFRASTRUCTURE_EXEMPTION,
            suppressProductRouting = true,
            reason = "Patronus generator changes are covered only by the always-on freshness check.",
        ),
        ProjectRoutingPolicy(
            ":ui-driver-tests",
            RoutingPolicyKind.UI_EXEMPTION,
            suppressProductRouting = true,
            reason = "UI-driver changes are owned by the existing UI Patronus check.",
        ),
    ).associateBy(ProjectRoutingPolicy::projectPath)

    val pathPolicies = listOf(
        "/build.gradle.kts", "/settings.gradle.kts", "/gradle.properties", "/gradle/**", "/gradlew", "/gradlew.bat",
    ).map {
        PathRoutingPolicy(
            it,
            RoutingPolicyKind.GLOBAL_GRADLE_INPUT,
            directLanes = PRODUCT_LANES,
            reason = "Root Gradle inputs can change every immediate product build.",
        )
    } + listOf(
        PathRoutingPolicy(
            "/karma.config.d/**",
            RoutingPolicyKind.NON_GRADLE_ROOT,
            impactProjects = setOf(":wasmParser"),
            reason = "Shared browser runner configuration follows the Wasm parser impact closure.",
        ),
        PathRoutingPolicy(
            "/wasm-modules/**",
            RoutingPolicyKind.TASK_ONLY_LINK,
            impactProjects = setOf(":graalwasm:wasmprinter", ":graalwasm:wat2wasm"),
            reason = "Rust converter sources feed the embedded GraalWasm wrappers outside Gradle project dependencies.",
        ),
        PathRoutingPolicy(
            "/mcp/tools-generator/**",
            RoutingPolicyKind.TASK_ONLY_LINK,
            directLanes = setOf(INTELLIJ_LANE),
            reason = "IntelliJ toolset freshness executes the tools-generator classes task.",
        ),
        PathRoutingPolicy(
            "/examples/scripting/**",
            RoutingPolicyKind.TASK_ONLY_LINK,
            directLanes = setOf(INTELLIJ_LANE),
            reason = "IntelliJ resource processing consumes the scripting examples.",
        ),
        PathRoutingPolicy(
            "/parsing/native-fixtures/**",
            RoutingPolicyKind.TASK_ONLY_LINK,
            directLanes = setOf(WEBAPP_LANE),
            reason = "The immediate Webapp aggregate consumes native parser fixtures.",
        ),
        PathRoutingPolicy(
            "/graalwasm/wasmprinter/**",
            RoutingPolicyKind.TASK_ONLY_LINK,
            directLanes = setOf(WEBAPP_LANE),
            reason = "The immediate Webapp aggregate consumes the embedded Wasm printer.",
        ),
        PathRoutingPolicy(
            "/license/**",
            RoutingPolicyKind.TASK_ONLY_LINK,
            directLanes = setOf(INTELLIJ_LANE, VSCODE_LANE, WEBAPP_LANE, SERVER_CLI_LANE),
            reason = "Licence and notice texts are packaged into the IntelliJ plugin, the VSIX, the webapp and the MCP/CLI bundles.",
        ),
        PathRoutingPolicy(
            "/vscode-plugin/media/editor.css",
            RoutingPolicyKind.TASK_ONLY_LINK,
            directLanes = setOf(WEBAPP_LANE),
            reason = "The immediate Webapp aggregate consumes the shared editor stylesheet.",
        ),
    )
}

class PatronusRouting(
    private val graph: ProjectImpactGraph,
    private val projectPolicies: Map<String, ProjectRoutingPolicy> = HexanaRoutingPolicies.projectPolicies,
    private val pathPolicies: List<PathRoutingPolicy> = HexanaRoutingPolicies.pathPolicies,
) {
    init {
        require(graph.laneSeeds.keys == PRODUCT_LANES) {
            "Snapshot product lanes must be ${PRODUCT_LANES.joinToString()}, but were ${graph.laneSeeds.keys.joinToString()}"
        }
        val unknownPolicyProjects = projectPolicies.keys - graph.nodes.map(ProjectNode::path).toSet()
        require(unknownPolicyProjects.isEmpty()) { "Routing policies reference unknown projects: ${unknownPolicyProjects.sorted()}" }
        val unknownImpactProjects = pathPolicies.flatMap { it.impactProjects } - graph.nodes.map(ProjectNode::path).toSet()
        require(unknownImpactProjects.isEmpty()) { "Path policies reference unknown projects: ${unknownImpactProjects.sorted()}" }
        require(
            (projectPolicies.values.map { it.reason } + pathPolicies.map { it.reason }).none(String::isBlank),
        ) { "Every explicit routing policy must explain why it exists" }
    }

    fun lanesForProject(projectPath: String): List<String> {
        val policy = projectPolicies[projectPath]
        if (policy?.suppressProductRouting == true) return emptyList()
        return graph.reverseImpactClosure(listOf(projectPath)).flatMap { ownLanes(it.path) }.distinct().sorted()
    }

    private fun ownLanes(projectPath: String): Set<String> {
        val policy = projectPolicies[projectPath]
        if (policy?.suppressProductRouting == true) return emptySet()
        val selected = graph.laneSeeds.filterValues { seeds ->
            seeds.any { seed -> seed == projectPath || seed.startsWith("$projectPath:") }
        }.keys.toMutableSet()
        if (projectPath != ":" && selected.isEmpty() && policy?.forcedLanes.isNullOrEmpty()) selected += CORE_LANE
        selected += policy?.forcedLanes.orEmpty()
        return selected
    }

    fun patternsByLane(): Map<String, List<String>> {
        val patterns = PRODUCT_LANES.associateWith { linkedSetOf<String>() }.toMutableMap()
        graph.nodes.filterNot { it.path == ":" }.forEach { project ->
            lanesForProject(project.path).forEach { patterns.getValue(it) += "/${project.relativeDirectory}/**" }
        }
        pathPolicies.forEach { policy ->
            val lanes = policy.directLanes + policy.impactProjects.flatMap(::lanesForProject)
            lanes.forEach { patterns.getValue(it) += policy.pattern }
        }
        return patterns.mapValues { (_, values) ->
            values.sortedWith(compareBy<String>({ it.count { character -> character == '/' } }, { it })).fold(
                mutableListOf<String>(),
            ) { selected, pattern ->
                if (selected.none { it.endsWith("/**") && pattern.startsWith(it.removeSuffix("**")) }) selected += pattern
                selected
            }.sorted()
        }
    }

    fun lanesForPath(path: String): List<String> = patternsByLane().filterValues { patterns ->
        patterns.any { pattern ->
            if (pattern.endsWith("/**")) path.startsWith(pattern.removeSuffix("**")) else path == pattern
        }
    }.keys.sorted()
}

const val CORE_LANE = "InnoHub_HexFlow_CoreTestsPatronus"
const val INTELLIJ_LANE = "InnoHub_HexFlow_IntellijPluginTestsPatronus"
const val VSCODE_LANE = "InnoHub_HexFlow_VscodePluginTestsPatronus"
const val WEBAPP_LANE = "InnoHub_HexFlow_WebappTestsPatronus"
const val SERVER_CLI_LANE = "InnoHub_HexFlow_ServerCliTestsPatronus"
val PRODUCT_LANES = sortedSetOf(CORE_LANE, INTELLIJ_LANE, VSCODE_LANE, WEBAPP_LANE, SERVER_CLI_LANE)

object ProjectImpactSnapshot {
    const val HEADER = "hexana-project-impact-graph\t2"

    fun parse(text: String): ProjectImpactGraph {
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == HEADER) { "Unsupported project-impact snapshot header: ${lines.firstOrNull()}" }
        val nodes = mutableListOf<ProjectNode>()
        val dependencies = mutableListOf<DeclaredProjectDependency>()
        val laneSeeds = mutableMapOf<String, MutableList<String>>()
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
                "lane-seed" -> {
                    require(fields.size == 3) { "Invalid lane-seed record at line ${index + 2}: $line" }
                    laneSeeds.getOrPut(fields[1], ::mutableListOf) += fields[2]
                }
                else -> error("Unknown project-impact record at line ${index + 2}: $line")
            }
        }
        return ProjectImpactGraph(nodes, dependencies, laneSeeds)
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
        graph.laneSeeds.forEach { (lane, seeds) -> seeds.forEach { appendLine("lane-seed\t$lane\t$it") } }
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
