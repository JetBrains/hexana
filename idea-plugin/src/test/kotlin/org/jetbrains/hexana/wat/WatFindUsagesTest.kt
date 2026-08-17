package org.jetbrains.hexana.wat

import com.intellij.lang.cacheBuilder.WordOccurrence
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.hexana.wat.psi.*

/**
 * Find-usages has to work through each declaration's own use scope, which is what the platform
 * passes when the action runs. It used to come back empty for everything but function-locals: the
 * search fell through to the word index, which stores `f1` rather than the `$f1` it was asked for.
 */
class WatFindUsagesTest : BasePlatformTestCase() {

    private fun usages(element: PsiNamedElement): Int =
        ReferencesSearch.search(element, element.useScope).findAll().size

    private inline fun <reified T : PsiNamedElement> declaration(file: PsiElement, name: String): T =
        SyntaxTraverser.psiTraverser(file).filter(T::class.java).first { it.name == name }
            ?: error("no ${T::class.simpleName} named $name")

    fun testModuleLevelDeclarationsFindTheirUsages() {
        val file = myFixture.configureByText(
            WatFileType.INSTANCE,
            $$"""
                (module
                  (type $t0 (func))
                  (global $g0 (mut i32) (i32.const 0))
                  (func $f1 (type $t0) (param $x i32)
                    (local $y i32)
                    (global.set $g0 (local.get $x))
                    (local.set $y (global.get $g0))
                    (call $f1)
                  )
                  (func $f2 (call $f1) (call $f1))
                )
            """.trimIndent(),
        )

        assertEquals("three (call \$f1)", 3, usages(declaration<WatFuncElement>(file, "\$f1")))
        assertEquals("one (type \$t0) use", 1, usages(declaration<WatTypeElement>(file, "\$t0")))
        assertEquals("global.set + global.get", 2, usages(declaration<WatGlobalElement>(file, "\$g0")))
        assertEquals("one local.get", 1, usages(declaration<WatParamElement>(file, "\$x")))
        assertEquals("one local.set", 1, usages(declaration<WatLocalElement>(file, "\$y")))
    }

    /** Component-model ids are scoped, so a usage search must not stray into a sibling scope. */
    fun testNestedScopesDoNotShareUsages() {
        val file = myFixture.configureByText(
            WatFileType.INSTANCE,
            $$"""
                (component $app
                  (type $#type0 (list string))
                  (type $#type1 (own $#type0))
                  (type $#type2 (own $#type0))
                  (core module $#module0
                    (type $#type0 (func (param i32)))
                    (func $#func0 (type $#type0) (nop))
                  )
                )
            """.trimIndent(),
        )

        val componentType = SyntaxTraverser.psiTraverser(file)
            .filter(WatTypeElement::class.java)
            .first { it.name == "\$#type0" && it.parent is WatComponentElement }!!
        val coreType = SyntaxTraverser.psiTraverser(file)
            .filter(WatTypeElement::class.java)
            .first { it.name == "\$#type0" && it.parent is WatModuleElement }!!

        assertEquals("both (own \$#type0) uses", 2, usages(componentType))
        assertEquals("only the core module's own use", 1, usages(coreType))
    }

    /**
     * A module too large for one document is split across a [WatFileset], so a declaration in one
     * chunk is used from another. Those files are in-memory and never indexed, which is the second
     * reason the search has to stay out of the word index.
     */
    fun testUsagesAreFoundAcrossFilesetChunks() {
        val origin = myFixture.configureByText(WatFileType.INSTANCE, "(module)").virtualFile
        val declaringText = "(module (;chunk 1;)\n(func \$f1)\n)\n"
        val declaring = LightWatFile(origin, "chunk_0.wat", declaringText)
        val using = LightWatFile(origin, "chunk_1.wat", "(module (;chunk 2;)\n(func \$f2 (call \$f1))\n)\n")
        // The chunk index is what lets a reference resolve into another chunk without parsing it,
        // so a fileset without it would fail on resolve rather than on the search scope.
        val watIndex = WatIndex().apply {
            add(0, declaringText.indexOf("(func"), WatElementType.FUNC, "\$f1")
        }
        WatFileset(files = listOf(declaring, using), watIndex = watIndex, onFileChange = {}).install()

        val declaringPsi = PsiManager.getInstance(project).findFile(declaring)!!
        assertEquals(
            "the call in the sibling chunk",
            1,
            usages(declaration<WatFuncElement>(declaringPsi, "\$f1")),
        )
    }

    /**
     * The restriction has to reach the search itself. `PsiElement.getUseScope()` does not apply
     * scope optimizers -- [ReferencesSearch] does, when it computes the effective scope -- so
     * asserting on the use scope alone would prove nothing.
     */
    fun testDeclarationsAreSearchedFileLocally() {
        val file = myFixture.configureByText(WatFileType.INSTANCE, "(module (func \$f1) (func \$f2 (call \$f1)))")
        val func = declaration<WatFuncElement>(file, "\$f1")
        assertInstanceOf(WatScopeOptimizer().getRestrictedUseScope(func), LocalSearchScope::class.java)
        val parameters = ReferencesSearch.SearchParameters(func, func.useScope, false)
        assertInstanceOf(parameters.effectiveSearchScope, LocalSearchScope::class.java)
    }

    /** An id must be indexed whole: the default scanner drops the `$`, so `$f1` never matched. */
    fun testWordsScannerKeepsTheDollarSign() {
        val words = mutableListOf<String>()
        val text = "(module (func \$f1 (call \$f1)))"
        WatFindUsagesProvider().wordsScanner.processWords(text) { occurrence: WordOccurrence ->
            words.add(occurrence.baseText.subSequence(occurrence.start, occurrence.end).toString())
            true
        }
        assertTrue("expected \$f1 among $words", words.contains("\$f1"))
    }

    fun testUsageViewNamesDeclarations() {
        val file = myFixture.configureByText(WatFileType.INSTANCE, "(module (func \$f1))")
        val provider = WatFindUsagesProvider()
        val func = declaration<WatFuncElement>(file, "\$f1")
        assertTrue(provider.canFindUsagesFor(func))
        assertEquals("function", provider.getType(func))
        assertEquals("function \$f1", provider.getNodeText(func, true))
    }
}
