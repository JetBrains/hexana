package org.jetbrains.hexana.wit.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class WitGateInspectionTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return Path.of("src/test/data/wit/inspections/gates").toAbsolutePath().toString()
    }

    fun testGates() {
        myFixture.enableInspections(WitGateInspection())
        myFixture.testHighlighting(true, false, false, "test.wit")
    }
}
