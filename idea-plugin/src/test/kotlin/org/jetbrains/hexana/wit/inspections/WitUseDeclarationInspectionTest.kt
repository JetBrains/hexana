package org.jetbrains.hexana.wit.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class WitUseDeclarationInspectionTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return Path.of("src/test/data/wit/inspections/useDeclaration").toAbsolutePath().toString()
    }

    fun testUseDeclarationMissingNames() {
        myFixture.enableInspections(WitUseDeclarationInspection())
        myFixture.testHighlighting(true, false, false, "test.wit")
    }
}
