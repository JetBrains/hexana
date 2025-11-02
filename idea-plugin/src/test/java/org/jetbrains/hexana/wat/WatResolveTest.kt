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

    fun testExportFunc() {
        doTest($$"""
            (module
              (export "f" (func <caret>$1))
              (func $0)
              (func (;TARGET;)$1)
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
}
