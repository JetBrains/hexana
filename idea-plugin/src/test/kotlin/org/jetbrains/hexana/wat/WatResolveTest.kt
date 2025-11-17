package org.jetbrains.hexana.wat

import com.intellij.psi.PsiComment
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class WatResolveTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        val relativePath = "src/test/data/resolve"
        return Path.of(relativePath).toAbsolutePath().toString()
    }

    fun doTest(watText: String) {
        val file = myFixture.configureByText(WatFileType.INSTANCE, watText.trimIndent())
        val target = SyntaxTraverser.psiTraverser(file)
            .filter { it is PsiComment }
            .find { it.text == "(;TARGET;)" }
        assertNotNull("(;TARGET;) comment is missing", target)
        val expectedResolveOffset = target!!.textRange.endOffset

        val referenceAtCaret = file.findReferenceAt(myFixture.editor.caretModel.offset)
        assertNotNull("No reference at caret", referenceAtCaret)

        val resolveResult = referenceAtCaret!!.resolve()
        assertNotNull("Resolve failed", resolveResult)

        assertEquals("Unexpected resolve result offset",
            expectedResolveOffset,
            resolveResult!!.textOffset)
    }

    fun testCallRef() {
        doTest($$"""
            (module
              (func $f0)
              (func (;TARGET;)$f1 (param $x i64) (result i64)
                (call <caret>$f1 (local.get $x)))
              (func $f2))
        """)
    }

    fun testCallRefByIndex() {
        doTest($$"""
            (module
              (import "env" "f0" (func $f0))
              (func (;TARGET;)$f1 (param $x i64) (result i64)
                (call <caret>1 (local.get $x)))
              (func $f2))
        """)
    }

    fun testCallRefToImport() {
        doTest($$"""
            (module
              (import "env" "f0" (func $f0))
              (import "env" "f1" (func (;TARGET;)$f1))
              (import "env" "f2" (func $f2))
              (func $4
                (call <caret>$f1)))
        """)
    }

    fun testCallRefToImportByIndex() {
        doTest($$"""
            (module
              (import "env" "f0" (func $f0))
              (func) 
              (import "env" "f1" (func (;TARGET;)$f1))
              (import "env" "f2" (func $f2))
              (func 
                (call <caret>2)))
        """)
    }

    fun testExportFunc() {
        doTest($$"""
            (module
              (export "f" (func <caret>$1))
              (func $0)
              (func (;TARGET;)$1)
              (func $2))
        """)
    }

    fun testExportFuncByIndex() {
        doTest($$"""
            (module
              (export "f" (func <caret>1))
              (func $0)
              (func (;TARGET;)$f)
              (func $2))
        """)
    }

    fun testTableRef() {
        doTest($$"""
            (module
              (table $t0 1 1 funcref)
              (table (;TARGET;)$t1 1 1 funcref)
              (table $t2 1 1 funcref)
              (elem <caret>$t1 (i32.const 0) $f))
        """)
    }

    fun testTableRefByIndex() {
        doTest($$"""
            (module
              (table $t0 1 1 funcref)
              (table (;TARGET;)$t1 1 1 funcref)
              (table $t2 1 1 funcref)
              (elem <caret>1 (i32.const 0) $f))
        """)
    }

    fun testFuncElem() {
        doTest($$"""
            (module
              (table $t0 3 3 funcref)
              (elem $t0 (i32.const 0) $f0 <caret>$f1 $f2)
              (func $f0)
              (func (;TARGET;)$f1)
              (func $f2))
        """)
    }

    fun testFuncElemByIndex() {
        doTest($$"""
            (module
              (table $t0 3 3 funcref)
              (elem $t0 (i32.const 0) $f0 <caret>1 $f2)
              (func $f0)
              (func (;TARGET;)$f1)
              (func $f2))
        """)
    }

    fun testTypeCallIndirect() {
        doTest($$"""
            (module
              (type (;TARGET;)$1 (func))
              (func $1
                (call_indirect (type <caret>$1))))
        """)
    }

    fun testTypeCallIndirectByIndex() {
        doTest($$"""
            (module
              (type (;TARGET;)$t1 0 (func))
              (func $1
                (call_indirect (type <caret>0))))
        """)
    }

    fun testGlobalSet() {
        doTest($$"""
            (module
              (global (;TARGET;)$1 (mut i32) (i32.const 0))
              (func $1
                (global.set <caret>$1 (i32.const 1))))
        """)
    }

    fun testGlobalSetByIndex() {
        doTest($$"""
            (module
              (global (;TARGET;)$g1 (mut i32) (i32.const 0))
              (func $1
                (global.set <caret>0 (i32.const 1))))
        """)
    }

    fun testGlobalGet() {
        doTest($$"""
            (module
              (global (;TARGET;)$1 (mut i32) (i32.const 0))
              (func $1
                (global.get <caret>$1)))
        """)
    }

    fun testGlobalGetByIndex() {
        doTest($$"""
            (module
              (global (;TARGET;)$g1 (mut i32) (i32.const 0))
              (func $1
                (global.get <caret>0)))
        """)
    }

    fun testTagThrow() {
        doTest($$"""
            (module
              (tag (;TARGET;)$1 (type $0) (param i32))
              (func $1
                (throw <caret>$1 (i32.const 0))))
        """)
    }

    fun testTagThrowIndex() {
        doTest($$"""
            (module
              (tag (;TARGET;)$tag1 (type $0) (param i32))
              (func $1
                (throw <caret>0 (i32.const 0))))
        """)
    }

    fun testTagCatch() {
        doTest($$"""
            (module
              (tag (;TARGET;)$1 (type $0) (param i32))
              (func $1
                (catch <caret>$1)))
        """)
    }

    fun testTagCatchByIndex() {
        doTest($$"""
            (module
              (tag (;TARGET;)$tag1 (type $0) (param i32))
              (func $1
                (catch <caret>0)))
        """)
    }

    fun testLocal() {
        doTest($$"""
            (module
              (func $1 
                (local $0 i32)
                (local (;TARGET;)$1 i32)
                (local.set <caret>$1 (i32.const 10)))
        """)
    }

    fun testLocalByIndex() {
        doTest($$"""
            (module
              (func $1 (param $0 i32)
                (local $l0 i32)
                (local (;TARGET;)$l1 i32)
                (local.set <caret>2 (i32.const 10)))
        """)
    }

    fun testParam() {
        doTest($$"""
            (module
              (func $1 (param $0 i32) (param (;TARGET;)$1 i32)
                (local $2 i32)
                (local.set <caret>$1 (i32.const 10)))
        """)
    }

    fun testParamByIndex() {
        doTest($$"""
            (module
              (func $1 (param $0 i32) (param (;TARGET;)$1 i32)
                (local $2 i32)
                (local.set <caret>1 (i32.const 10)))
        """)
    }
}
