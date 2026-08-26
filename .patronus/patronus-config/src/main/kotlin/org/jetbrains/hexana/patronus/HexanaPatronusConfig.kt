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
        val verify = args.firstOrNull() == "--verify"
        val path = Path.of(args.lastOrNull() ?: error("Expected config path"))
        val expected = expectedYaml()
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

    fun expectedYaml(): String = HexanaPatronusConfig.toModel().toYaml()

    private fun firstDifference(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val lineIndex = expectedLines.indices.firstOrNull { expectedLines[it] != actualLines.getOrNull(it) }
            ?: minOf(expectedLines.size, actualLines.size)
        return "First difference at line ${lineIndex + 1}: expected '${expectedLines.getOrNull(lineIndex)}', " +
            "actual '${actualLines.getOrNull(lineIndex)}'"
    }
}

object HexanaPatronusConfig : BranchProtectionConfig(init = {
    checks {
        check(
            teamCityCheck(
                "InnoHub_HexFlow_SafeMergeTests",
                "*",
                "!/README.md",
            ),
        )
        check(
            teamCityCheck(
                "InnoHub_HexFlow_UiTestsPatronus",
                "/ui-driver-tests/**",
                "/idea-plugin/src/ui-tests-framework/**",
            ),
        )
        check(teamCityCheck("InnoHub_HexFlow_PatronusConfigCheckPatronus", "*"))
        checksGroup("hexana-product-tests") {
            primaryCheck(teamCityCheck("InnoHub_HexFlow_IntellijPluginTestsPatronus", "/idea-plugin/**"))
            fallbackCheck(
                teamCityCheck(
                    "InnoHub_HexFlow_CoreTestsPatronus",
                    "/build.gradle.kts",
                    "/settings.gradle.kts",
                    "/gradle.properties",
                    "/gradle/**",
                    "/gradlew",
                    "/gradlew.bat",
                    "/karma.config.d/**",
                    "/analytics/**",
                    "/binaryProvider/**",
                    "/debugInfo/**",
                    "/disassembly/**",
                    "/encdec/**",
                    "/hexanaDocument/**",
                    "/jvm-jit/**",
                    "/parsing/**",
                    "/plugins-shared/**",
                    "/wasmParser/**",
                    "/graalwasm/**",
                    "/wasm-modules/**",
                    "/mcp/core/**",
                    "/mcp/scripting/**",
                    "/mcp/tools-generator/**",
                ),
            )
            primaryCheck(teamCityCheck("InnoHub_HexFlow_VscodePluginTestsPatronus", "/vscode-plugin/**", "/web-ui/**"))
            primaryCheck(teamCityCheck("InnoHub_HexFlow_WebappTestsPatronus", "/webapp/**", "/web-ui/**"))
            primaryCheck(
                teamCityCheck(
                    "InnoHub_HexFlow_ServerCliTestsPatronus",
                    "/cli/**",
                    "/mcp/**",
                    "!/mcp/core/**",
                    "!/mcp/scripting/**",
                    "!/mcp/tools-generator/**",
                ),
            )
        }
    }
})

private fun teamCityCheck(configurationId: String, vararg executeOnChanges: String): TeamCityCheckConfig =
    TeamCityCheckConfig {
        this.configurationId = configurationId
        this.executeOnChanges = executeOnChanges.toList()
    }
