package org.jetbrains.hexana.patronus

import org.jetbrains.patronus.config.branch.dsl.BranchProtectionConfig
import org.jetbrains.patronus.config.branch.dsl.TeamCityCheckConfig
import org.jetbrains.patronus.config.branch.dsl.toModel
import org.jetbrains.patronus.config.branch.dsl.toYaml
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

object HexanaPatronusConfigGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val verify = "--verify" in args
        val paths = args.filterNot { it == "--verify" }.map(Path::of)
        require(paths.size == 2) { "Expected config path and project-impact snapshot path" }
        val (path, graphPath) = paths
        val expected = expectedYaml(ProjectImpactSnapshot.parse(graphPath.readText()))
        if (verify) {
            val actual = path.readText()
            check(actual == expected) {
                ".patronus/config.yaml is out of date; run ./gradlew updatePatronusConfig\n" +
                    firstDifference(expected, actual)
            }
        }
        else {
            path.writeText(expected)
        }
    }

    fun expectedYaml(graph: ProjectImpactGraph): String = hexanaPatronusConfig(graph).toModel().toYaml()

    private fun firstDifference(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val lineIndex = expectedLines.indices.firstOrNull { expectedLines[it] != actualLines.getOrNull(it) }
            ?: minOf(expectedLines.size, actualLines.size)
        return "First difference at line ${lineIndex + 1}: expected '${expectedLines.getOrNull(lineIndex)}', " +
            "actual '${actualLines.getOrNull(lineIndex)}'"
    }
}

fun hexanaPatronusConfig(graph: ProjectImpactGraph): BranchProtectionConfig {
    val patterns = PatronusRouting(graph).patternsByLane()
    return BranchProtectionConfig(init = {
        checks {
            check(
                teamCityCheck(
                    "InnoHub_HexFlow_UiTestsPatronus",
                    "/ui-driver-tests/**",
                    "/idea-plugin/src/ui-tests-framework/**",
                ),
            )
            check(teamCityCheck("InnoHub_HexFlow_PatronusConfigCheckPatronus", "*"))
            check(teamCityCheck("InnoHub_HexFlow_LeakGuardPatronus", "*"))
            check(teamCityCheck(INTELLIJ_LANE, *patterns.getValue(INTELLIJ_LANE).toTypedArray()))
            check(teamCityCheck(CORE_LANE, *patterns.getValue(CORE_LANE).toTypedArray()))
            check(teamCityCheck(VSCODE_LANE, *patterns.getValue(VSCODE_LANE).toTypedArray()))
            check(teamCityCheck(WEBAPP_LANE, *patterns.getValue(WEBAPP_LANE).toTypedArray()))
            check(teamCityCheck(SERVER_CLI_LANE, *patterns.getValue(SERVER_CLI_LANE).toTypedArray()))
        }
    })
}

private fun teamCityCheck(configurationId: String, vararg executeOnChanges: String): TeamCityCheckConfig =
    TeamCityCheckConfig {
        this.configurationId = configurationId
        this.executeOnChanges = executeOnChanges.toList()
    }
