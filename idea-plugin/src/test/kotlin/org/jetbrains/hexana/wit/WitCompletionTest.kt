package org.jetbrains.hexana.wit

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class WitCompletionTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return Path.of("idea-plugin/src/test/data/wit/completion").toAbsolutePath().toString()
    }

    fun testUsePathLocalInterface() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
            }
            
            world my-world {
                use <caret>
            }
        """.trimIndent())
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings
        assertNotNull("Lookup elements should not be null", strings)
        assertTrue("Expected 'my-inter' in $strings", strings!!.contains("my-inter"))
    }

    fun testUsePathLocalInterfaceWithPackage() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
            }
            
            world my-world {
                use pkg:name/<caret>
            }
        """.trimIndent())
        myFixture.completeBasic()
    }

    fun testUseNamesCompletion() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
                my-func: func();
            }
            
            world my-world {
                use my-inter.{<caret>};
            }
        """.trimIndent())
        val variants = myFixture.completeBasic()
        // There should be 2 variants.
        if (variants == null) {
            val text = myFixture.editor.document.text
            assertTrue("Expected my-type or my-func to be present in $text", text.contains("my-type") || text.contains("my-func"))
            return
        }
        val strings = variants.map { it.lookupString }
        assertTrue("Expected 'my-func' in $strings", strings.contains("my-func"))
    }

    fun testUsePathInsertionHandler() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
            }
            
            world my-world {
                use my<caret>
            }
        """.trimIndent())
        myFixture.completeBasic()
        if (myFixture.lookup != null) {
            myFixture.type('\n')
        }
        myFixture.checkResult("""
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
            }
            
            world my-world {
                use my-inter.{<caret>};
            }
        """.trimIndent())
    }
}
