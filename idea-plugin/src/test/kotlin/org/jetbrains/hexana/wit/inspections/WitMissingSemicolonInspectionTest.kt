package org.jetbrains.hexana.wit.inspections

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class WitMissingSemicolonInspectionTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return Path.of("src/test/data/wit/inspections/missingSemicolon").toAbsolutePath().toString()
    }

    fun testMissingSemicolons() {
        myFixture.enableInspections(WitMissingSemicolonInspection())
        myFixture.testHighlighting(true, false, false, "test.wit")
    }

    fun testQuickFix() {
        myFixture.enableInspections(WitMissingSemicolonInspection())
        myFixture.configureByFile("before.wit")
        val action = myFixture.findSingleIntention("Add semicolon")
        myFixture.launchAction(action)
        myFixture.checkResultByFile("after.wit", true)
    }
}
