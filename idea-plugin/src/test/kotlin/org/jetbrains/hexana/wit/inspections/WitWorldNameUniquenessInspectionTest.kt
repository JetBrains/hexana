package org.jetbrains.hexana.wit.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class WitWorldNameUniquenessInspectionTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return Path.of("src/test/data/wit/inspections/worldNameUniqueness").toAbsolutePath().toString()
    }

    fun testWorldNameUniqueness() {
        myFixture.enableInspections(WitWorldNameUniquenessInspection())
        myFixture.testHighlighting(true, false, false, "test.wit")
    }
}
