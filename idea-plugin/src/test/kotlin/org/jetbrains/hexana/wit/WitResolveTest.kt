package org.jetbrains.hexana.wit

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.wit.psi.WitInterfaceDefinition
import org.jetbrains.hexana.wit.psi.WitTypeDeclaration

class WitResolveTest : BasePlatformTestCase() {

    fun testResolveInterfacePath() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
            }
            
            world my-world {
                use my-inte<caret>r.{my-type};
            }
        """.trimIndent())
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue("Resolved to wrong element: ${resolved?.javaClass?.simpleName}", resolved is WitInterfaceDefinition)
        assertEquals("my-inter", (resolved as WitInterfaceDefinition).name)
    }

    fun testResolveTypeInUse() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
            }
            
            world my-world {
                use my-inter.{my-ty<caret>pe};
            }
        """.trimIndent())
        
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue("Resolved to wrong element: ${resolved?.javaClass?.simpleName}", resolved is WitTypeDeclaration)
        assertEquals("my-type", (resolved as WitTypeDeclaration).name)
    }

    fun testResolveTypeReference() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
                type other-type = my-ty<caret>pe;
            }
        """.trimIndent())
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue("Resolved to wrong element: ${resolved?.javaClass?.simpleName}", resolved is WitTypeDeclaration)
        assertEquals("my-type", (resolved as WitTypeDeclaration).name)
    }

    fun testResolveIncludePath() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
            }
            
            world my-world {
                include my-inte<caret>r;
            }
        """.trimIndent())
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue("Resolved to wrong element: ${resolved?.javaClass?.simpleName}", resolved is WitInterfaceDefinition)
        assertEquals("my-inter", (resolved as WitInterfaceDefinition).name)
    }

    fun testResolveIncludeWith() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
            }
            
            world my-world {
                include my-inter with { my-ty<caret>pe as x };
            }
        """.trimIndent())
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue("Resolved to wrong element: ${resolved?.javaClass?.simpleName}", resolved is WitTypeDeclaration)
        assertEquals("my-type", (resolved as WitTypeDeclaration).name)
    }

    fun testResolvePackagePath() {
        myFixture.addFileToProject("pkg/other.wit", """
            package pkg:other;
            
            interface other-inter {
                type other-type = u32;
            }
        """.trimIndent())
        
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            world my-world {
                use pkg:other/other-inte<caret>r;
            }
        """.trimIndent())
        
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue("Resolved to wrong element: ${resolved?.javaClass?.simpleName}", resolved is WitInterfaceDefinition)
        assertEquals("other-inter", (resolved as WitInterfaceDefinition).name)
    }

    fun testResolvePackagePathWithVersion() {
        myFixture.addFileToProject("pkg/other.wit", """
            package pkg:other@0.1.0;
            
            interface other-inter {
                type other-type = u32;
            }
        """.trimIndent())

        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            world my-world {
                use pkg:other/other-inte<caret>r@0.1.0;
            }
        """.trimIndent())

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue("Resolved to wrong element: ${resolved?.javaClass?.simpleName}", resolved is WitInterfaceDefinition)
        assertEquals("other-inter", (resolved as WitInterfaceDefinition).name)
    }

    fun testResolveFuncInUse() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                my-func: func();
            }
            
            world my-world {
                use my-inter.{my-fu<caret>nc};
            }
        """.trimIndent())

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        // FuncItem is probably the class name
        assertEquals("my-func", (resolved as org.jetbrains.hexana.wit.psi.WitFuncItem).identifier.text)
    }

    fun testResolveWasiPath() {
        myFixture.addFileToProject("wasi/clocks.wit", """
            package wasi:clocks@0.2.0;
            
            interface wall-clock {
                now: func() -> u64;
            }
        """.trimIndent())

        myFixture.configureByText("test.wit", """
            package my:pkg;
            
            world my-world {
                import wasi:clocks/wall-cl<caret>ock@0.2.0;
            }
        """.trimIndent())

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertEquals("wall-clock", (resolved as WitInterfaceDefinition).name)
    }

    fun testResolveUseFromWorld() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
            }
            
            world my-world {
                use my-inter.{my-type};
                
                export do-something: func(arg: my-ty<caret>pe);
            }
        """.trimIndent())

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        // If it's null, we'll see it in the test failure
        assertNotNull("Could not resolve reference", resolved)
        assertTrue(resolved is WitTypeDeclaration)
        assertEquals("my-type", (resolved as WitTypeDeclaration).name)
    }

    fun testResolveTypeReferenceInInterface() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter {
                type my-type = u32;
                type other-type = my-ty<caret>pe;
            }
        """.trimIndent())
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue(resolved is WitTypeDeclaration)
        assertEquals("my-type", (resolved as WitTypeDeclaration).name)
    }

    fun testResolveTypeReferenceBetweenInterfaces() {
        myFixture.configureByText("test.wit", """
            package pkg:name;
            
            interface my-inter1 {
                type my-type = u32;
            }
            
            interface my-inter2 {
                use my-inter1.{my-type};
                type other-type = my-ty<caret>pe;
            }
        """.trimIndent())
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("Reference not found at caret", reference)
        val resolved = reference!!.resolve()
        assertNotNull("Could not resolve reference", resolved)
        assertTrue(resolved is WitTypeDeclaration)
        assertEquals("my-type", (resolved as WitTypeDeclaration).name)
    }
}
