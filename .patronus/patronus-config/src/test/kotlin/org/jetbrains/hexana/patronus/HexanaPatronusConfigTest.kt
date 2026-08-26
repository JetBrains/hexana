package org.jetbrains.hexana.patronus

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HexanaPatronusConfigTest {
    @Test
    fun `generated config matches the checked-in YAML`() {
        val repositoryRoot = Path.of(System.getProperty("hexana.repositoryRoot"))
        assertEquals(
            repositoryRoot.resolve(".patronus/config.yaml").readText(),
            HexanaPatronusConfigGenerator.expectedYaml(),
        )
    }

    @Test
    fun `generated config preserves the Patronus routing contract`() {
        val yaml = HexanaPatronusConfigGenerator.expectedYaml()
        val configurationIds = Regex("configurationId: \\\"([^\\\"]+)\\\"").findAll(yaml).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "InnoHub_HexFlow_SafeMergeTests",
                "InnoHub_HexFlow_UiTestsPatronus",
                "InnoHub_HexFlow_IntellijPluginTestsPatronus",
                "InnoHub_HexFlow_ServerCliTestsPatronus",
                "InnoHub_HexFlow_VscodePluginTestsPatronus",
                "InnoHub_HexFlow_WebappTestsPatronus",
                "InnoHub_HexFlow_CoreTestsPatronus",
            ),
            configurationIds,
        )
        assertContains(yaml, "id: \"hexana-product-tests\"")
        assertContains(yaml, "isFallback: true")
        assertContains(yaml, "- \"/vscode-plugin/**\"")
        assertContains(yaml, "- \"/webapp/**\"")
        assertEquals(2, Regex("- \"/web-ui/\\*\\*\"").findAll(yaml).count())
        assertContains(yaml, "- \"!/mcp/core/**\"")
        assertContains(yaml, "- \"!/mcp/scripting/**\"")
        assertContains(yaml, "- \"!/mcp/tools-generator/**\"")
    }
}
