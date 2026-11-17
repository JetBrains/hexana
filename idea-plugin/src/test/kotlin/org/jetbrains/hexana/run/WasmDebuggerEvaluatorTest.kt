package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.parseLldbExpressionResult

class WasmDebuggerEvaluatorTest : UsefulTestCase() {

    fun testParseSimpleInt() {
        val result = parseLldbExpressionResult("(int) \$0 = 42")
        assertEquals("int", result.typeName)
        assertEquals("42", result.displayValue)
    }

    fun testParsePointerWithString() {
        val result = parseLldbExpressionResult(
            """(const char *) ${'$'}1 = 0x00007ff "hello"""",
        )
        assertEquals("const char *", result.typeName)
        assertEquals("""0x00007ff "hello"""", result.displayValue)
    }

    fun testParseFloat() {
        val result = parseLldbExpressionResult("(double) \$2 = 3.14")
        assertEquals("double", result.typeName)
        assertEquals("3.14", result.displayValue)
    }

    fun testParseLong() {
        val result = parseLldbExpressionResult("(long) \$3 = 9999999999")
        assertEquals("long", result.typeName)
        assertEquals("9999999999", result.displayValue)
    }

    fun testParseStruct() {
        val result = parseLldbExpressionResult(
            "(MyStruct) \$4 = (x = 1, y = 2)",
        )
        assertEquals("MyStruct", result.typeName)
        assertEquals("(x = 1, y = 2)", result.displayValue)
    }

    fun testParseHighScratchVariable() {
        val result = parseLldbExpressionResult("(int) \$123 = -1")
        assertEquals("int", result.typeName)
        assertEquals("-1", result.displayValue)
    }

    fun testParseBool() {
        val result = parseLldbExpressionResult("(bool) \$5 = true")
        assertEquals("bool", result.typeName)
        assertEquals("true", result.displayValue)
    }

    fun testFallbackForUnrecognizedFormat() {
        val raw = "some unexpected output"
        val result = parseLldbExpressionResult(raw)
        assertNull(result.typeName)
        assertEquals(raw, result.displayValue)
    }

    fun testParseRustI32() {
        val result = parseLldbExpressionResult("(i32) \$0 = 100")
        assertEquals("i32", result.typeName)
        assertEquals("100", result.displayValue)
    }

    fun testParseMultilineValue() {
        val text = "(MyStruct) \$0 = {\n  x = 1\n  y = 2\n}"
        val result = parseLldbExpressionResult(text)
        assertEquals("MyStruct", result.typeName)
        assertEquals("{\n  x = 1\n  y = 2\n}", result.displayValue)
    }
}
