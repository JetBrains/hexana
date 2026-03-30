package org.jetbrains.hexana.wit

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WitGateCompletionTest : BasePlatformTestCase() {
    fun testUnstableCompletion() {
        myFixture.configureByText("test.wit", "@<caret>")
        myFixture.completeBasic()
        myFixture.type("unstable\n")
        myFixture.checkResult("@unstable(feature = <caret>)")
    }

    fun testSinceCompletion() {
        myFixture.configureByText("test.wit", "@<caret>")
        myFixture.completeBasic()
        myFixture.type("since\n")
        myFixture.checkResult("@since(version = <caret>)")
    }

    fun testDeprecatedCompletion() {
        myFixture.configureByText("test.wit", "@<caret>")
        myFixture.completeBasic()
        myFixture.type("deprecated\n")
        myFixture.checkResult("@deprecated(version = <caret>)")
    }

    fun testAutoPopup() {
        myFixture.configureByText("test.wit", "<caret>")
        myFixture.type("@")
        myFixture.completeBasic()
        val lookup = myFixture.lookup
        assertNotNull("Lookup should be shown after typing '@'", lookup)
        assertTrue("Expected 'unstable' in lookup", lookup!!.items.any { it.lookupString == "unstable" })
    }

    fun testNoCompletionInUsePath() {
        myFixture.configureByText("test.wit", "use pkg:name@<caret>")
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings
        if (strings != null) {
            assertFalse("Should NOT offer 'unstable' in use path", strings.contains("unstable"))
        }
    }

    fun testNoCompletionInPackageDeclaration() {
        myFixture.configureByText("test.wit", "package pkg:name@<caret>")
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings
        if (strings != null) {
            assertFalse("Should NOT offer 'unstable' in package declaration", strings.contains("unstable"))
        }
    }
}
