package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase

class ConditionTranslationTest : UsefulTestCase() {

    // ---- translateCondition ----

    fun testTranslateCondition_emptyMap_returnsOriginal() {
        val result = translateCondition("x > 5", emptyMap())
        assertEquals("x > 5", result)
    }

    fun testTranslateCondition_simpleSubstitution() {
        val nameToVar = mapOf("x" to "\$var0", "y" to "\$var1")
        val result = translateCondition("x > 5", nameToVar)
        assertEquals("\$var0 > 5", result)
    }

    fun testTranslateCondition_multipleVariables() {
        val nameToVar = mapOf("x" to "\$var0", "y" to "\$var1")
        val result = translateCondition("x + y > 10", nameToVar)
        assertEquals("\$var0 + \$var1 > 10", result)
    }

    fun testTranslateCondition_wordBoundaryPreventsPartialMatch() {
        // "px" must NOT be replaced when only "x" is in the map
        val nameToVar = mapOf("x" to "\$var0")
        val result = translateCondition("px > x", nameToVar)
        assertEquals("px > \$var0", result)
    }

    fun testTranslateCondition_wordBoundaryAtStart() {
        val nameToVar = mapOf("x" to "\$var0")
        val result = translateCondition("x == 0", nameToVar)
        assertEquals("\$var0 == 0", result)
    }

    fun testTranslateCondition_wordBoundaryAtEnd() {
        val nameToVar = mapOf("x" to "\$var0")
        val result = translateCondition("getX() == x", nameToVar)
        // "getX" should not be substituted; "x" at end should
        assertEquals("getX() == \$var0", result)
    }

    fun testTranslateCondition_noMatch_returnsOriginal() {
        val nameToVar = mapOf("x" to "\$var0")
        val result = translateCondition("y > 0", nameToVar)
        assertEquals("y > 0", result)
    }

    fun testTranslateCondition_multipleOccurrences() {
        val nameToVar = mapOf("x" to "\$var0")
        val result = translateCondition("x > 0 && x < 10", nameToVar)
        assertEquals("\$var0 > 0 && \$var0 < 10", result)
    }

    fun testTranslateCondition_numericLiteralsUnaffected() {
        val nameToVar = mapOf("count" to "\$var2")
        val result = translateCondition("count > 100", nameToVar)
        assertEquals("\$var2 > 100", result)
    }
}
