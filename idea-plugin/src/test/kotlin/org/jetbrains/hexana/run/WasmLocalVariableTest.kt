package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase

class WasmLocalVariableTest : UsefulTestCase() {

    fun testParseSimpleInt() {
        val lines = listOf("(int) x = 42")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("x", vars[0].name)
    }

    fun testParsePointerVariable() {
        val lines = listOf("""(WasmtimeVMContext *) __vmctx = 0x000000075ad04aa0""")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("__vmctx", vars[0].name)
    }

    fun testParseVariableNotAvailable() {
        val lines = listOf(
            "(WasmtimeVMContext *) __vmctx = 0x000000075ad04aa0",
            "(calculator::bindings::exports::component::calculator::calculate::Op) op = <variable not available>",
            "",
            "(unsigned int) x = <variable not available>",
            "",
            "(unsigned int) y = <variable not available>",
        )
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(4, vars.size)

        assertEquals("__vmctx", vars[0].name)
        assertEquals("0x000000075ad04aa0", vars[0].displayValue)

        assertEquals("op", vars[1].name)
        assertEquals("<variable not available>", vars[1].displayValue)

        assertEquals("x", vars[2].name)
        assertEquals("<variable not available>", vars[2].displayValue)

        assertEquals("y", vars[3].name)
        assertEquals("<variable not available>", vars[3].displayValue)
    }

    fun testParseMultipleVariables() {
        val lines = listOf(
            "(int) x = 42",
            "(double) y = 3.14",
            "(const char *) name = 0x7ff \"hello\"",
        )
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(3, vars.size)
        assertEquals("x", vars[0].name)
        assertEquals("y", vars[1].name)
        assertEquals("name", vars[2].name)
    }

    fun testParseMultilineStruct() {
        val lines = listOf(
            "(MyStruct) s = {",
            "  field1 = 1",
            "  field2 = 2",
            "}",
        )
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("s", vars[0].name)
    }

    fun testParseEmptyOutput() {
        val vars = WasmLocalVariable.parseFrameVariableOutput(emptyList())
        assertTrue(vars.isEmpty())
    }

    fun testParseInlineTupleStruct() {
        val lines = listOf("(MyStruct) s = (field1 = 1, field2 = 2)")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("s", vars[0].name)
    }

    fun testParseRustQualifiedType() {
        val lines = listOf("(core::option::Option<i32>) opt = None")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("opt", vars[0].name)
    }

    fun testParseBlanksIgnored() {
        val lines = listOf(
            "",
            "(int) x = 42",
            "",
            "",
            "(int) y = 7",
            "",
        )
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(2, vars.size)
        assertEquals("x", vars[0].name)
        assertEquals("y", vars[1].name)
    }

    /** Access the private displayValue for testing via expression evaluation output. */
    private val WasmLocalVariable.displayValue: String
        get() {
            val field = WasmLocalVariable::class.java.getDeclaredField("displayValue")
            field.isAccessible = true
            return field.get(this) as String
        }
}
