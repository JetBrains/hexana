package org.jetbrains.hexana.wit

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WitRenameTest : BasePlatformTestCase() {

    fun testRenameInterface() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-in<caret>ter {
                type my-type = u32;
            }
            
            world my-world {
                use my-inter.{my-type};
                include my-inter with {my-type as x};
            }
        """.trimIndent())
        myFixture.renameElementAtCaret("new-inter")
        myFixture.checkResult("""
            package pkg:name;
            
            interface new-inter {
                type my-type = u32;
            }
            
            world my-world {
                use new-inter.{my-type};
                include new-inter with {my-type as x};
            }
        """.trimIndent())
    }

    fun testRenameTypeDeclaration() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-ty<caret>pe = u32;
            }
            
            world my-world {
                use my-inter.{my-type};
                include my-inter with {my-type as x};
            }
        """.trimIndent())
        myFixture.renameElementAtCaret("new-type")
        myFixture.checkResult("""
            package pkg:name;
            
            interface my-inter {
                type new-type = u32;
            }
            
            world my-world {
                use my-inter.{new-type};
                include my-inter with {new-type as x};
            }
        """.trimIndent())
    }
    
    fun testRenameEnumCase() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                enum my-enum {
                    my-ca<caret>se
                }
            }
        """.trimIndent())
        myFixture.renameElementAtCaret("new-case")
        myFixture.checkResult("""
            package pkg:name;
            
            interface my-inter {
                enum my-enum {
                  new-case
                }
            }
        """.trimIndent())
    }

    
    fun testRenameVariantCase() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                variant my-var {
                    my-ca<caret>se(u32)
                }
            }
        """.trimIndent())
        myFixture.renameElementAtCaret("new-case")
        myFixture.checkResult("""
            package pkg:name;
            
            interface my-inter {
                variant my-var {
                    new-case(u32)
                }
            }
        """.trimIndent())
    }

    
    fun testRenameRecordField() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                record my-rec {
                    my-fi<caret>eld: u32
                }
            }
        """.trimIndent())
        myFixture.renameElementAtCaret("new-field")
        myFixture.checkResult("""
            package pkg:name;
            
            interface my-inter {
                record my-rec {
                    new-field: u32
                }
            }
        """.trimIndent())
    }

    fun testRenameFuncItem() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                my-fu<caret>nc: func();
            }
            
            world my-world {
                use my-inter.{my-func};
                include my-inter with {my-func as x};
            }
        """.trimIndent())
        myFixture.renameElementAtCaret("new-func")
        myFixture.checkResult("""
            package pkg:name;
            
            interface my-inter {
                new-func: func();
            }
            
            world my-world {
                use my-inter.{new-func};
                include my-inter with {new-func as x};
            }
        """.trimIndent())
    }

    fun testRenameStaticFuncItem() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                resource my-res {
                    static my-sta<caret>tic: func();
                }
            }
        """.trimIndent())
        myFixture.renameElementAtCaret("new-static")
        myFixture.checkResult("""
            package pkg:name;
            
            interface my-inter {
                resource my-res {
                    static new-static: func();
                }
            }
        """.trimIndent())
    }
}
