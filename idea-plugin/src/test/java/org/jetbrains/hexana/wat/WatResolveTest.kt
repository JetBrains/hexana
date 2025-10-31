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
            resolveResult!!.textRange.startOffset)
    }

    fun testCallRef() {
        doTest($$"""
            (module
              (func (;TARGET;)$f (param $x i64) (result i64)
                (call <caret>$f (local.get $x))))
        """)
    }
}
