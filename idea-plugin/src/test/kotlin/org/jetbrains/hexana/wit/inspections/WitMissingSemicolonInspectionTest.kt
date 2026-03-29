package org.jetbrains.hexana.wit.inspections

import com.intellij.idea.IgnoreJUnit3
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Ignore
import java.nio.file.Path

class WitMissingSemicolonInspectionTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return Path.of("src/test/data/wit/inspections/missingSemicolon").toAbsolutePath().toString()
    }

    fun testMissingSemicolons() {
        myFixture.enableInspections(WitMissingSemicolonInspection())
        myFixture.testHighlighting(true, false, false, "test.wit")
    }

    /*@IgnoreJUnit3(reason = "TODO investigate teamcity failures")
    fun testQuickFix() {
        myFixture.enableInspections(WitMissingSemicolonInspection())
        myFixture.configureByFile("before.wit")
        val action = myFixture.findSingleIntention("Add semicolon")
        myFixture.launchAction(action)
        myFixture.checkResultByFile("after.wit", true)
    }*/
}
