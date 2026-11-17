package org.jetbrains.hexana.wit.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.wit.inspections.WitEmptyDefinitionInspection
import java.nio.file.Path

class WitEmptyDefinitionInspectionTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return Path.of("src/test/data/wit/inspections/emptyDefinition").toAbsolutePath().toString()
    }

    fun testEmptyDefinitions() {
        myFixture.enableInspections(WitEmptyDefinitionInspection())
        myFixture.testHighlighting(true, false, false, "test.wit")
    }
}
