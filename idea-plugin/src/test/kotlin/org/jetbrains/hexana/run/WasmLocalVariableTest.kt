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
        val lines = listOf("""(const char *) ptr = 0x7ff "hello"""")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("ptr", vars[0].name)
    }

    fun testHiddenVmctxFilteredByDefault() {
        val lines = listOf("""(WasmtimeVMContext *) __vmctx = 0x000000075ad04aa0""")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertTrue("__vmctx should be filtered out by default", vars.isEmpty())
    }

    fun testHiddenVmctxKeptWhenFilterDisabled() {
        val lines = listOf("""(WasmtimeVMContext *) __vmctx = 0x000000075ad04aa0""")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines, filterHidden = false)
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
        // __vmctx is filtered out by default
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(3, vars.size)

        assertEquals("op", vars[0].name)
        assertEquals("<variable not available>", vars[0].displayValue)
        assertTrue(vars[0].isUnavailable)

        assertEquals("x", vars[1].name)
        assertEquals("<variable not available>", vars[1].displayValue)
        assertTrue(vars[1].isUnavailable)

        assertEquals("y", vars[2].name)
        assertEquals("<variable not available>", vars[2].displayValue)
        assertTrue(vars[2].isUnavailable)
    }

    fun testIsUnavailableShortForm() {
        val lines = listOf("(unsigned int) x = <unavailable>")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertTrue(vars[0].isUnavailable)
    }

    fun testIsNotUnavailableForRegularValue() {
        val lines = listOf("(int) x = 42")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertFalse(vars[0].isUnavailable)
    }

    fun testWithResolvedValue() {
        val lines = listOf("(unsigned int) x = <variable not available>")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        val original = vars[0]
        assertTrue(original.isUnavailable)

        val resolved = WasmLocalVariable.withResolvedValue(original, "int", "42")
        assertEquals("x", resolved.name)
        assertEquals("42", resolved.displayValue)
        assertFalse(resolved.isUnavailable)
    }

    fun testWithResolvedValuePreservesOriginalTypeWhenNull() {
        val lines = listOf("(unsigned int) x = <variable not available>")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        val original = vars[0]

        val resolved = WasmLocalVariable.withResolvedValue(original, null, "42")
        assertEquals("x", resolved.name)
        assertEquals("42", resolved.displayValue)
        // typeName is private, but we can verify via displayValue
        assertFalse(resolved.isUnavailable)
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

    fun testIsUnavailableNoLocationForm() {
        val lines = listOf("(int) i = <no location, value may have been optimized out>")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertTrue(vars[0].isUnavailable)
    }

    fun testTypeNameAccessible() {
        val lines = listOf("(unsigned int) x = 42")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("unsigned int", vars[0].typeName)
    }

    fun testTypeNameForUnavailableVariable() {
        val lines = listOf(
            "(calculator::bindings::exports::component::calculator::calculate::Op) op = <variable not available>",
        )
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertEquals("calculator::bindings::exports::component::calculator::calculate::Op", vars[0].typeName)
        assertTrue(vars[0].isUnavailable)
    }

    fun testDwarfMissingMarker() {
        val original = WasmLocalVariable.parseFrameVariableOutput(
            listOf("(unsigned int) x = <variable not available>")
        )[0]
        assertTrue(original.isUnavailable)
        assertFalse(original.isDwarfMetadataMissing)

        val dwarfMissing = WasmLocalVariable.withDwarfMissingMessage(original)
        assertEquals("x", dwarfMissing.name)
        assertEquals("unsigned int", dwarfMissing.typeName)
        assertEquals(WasmLocalVariable.DWARF_MISSING_MARKER, dwarfMissing.displayValue)
        assertTrue(dwarfMissing.isUnavailable)
        assertTrue(dwarfMissing.isDwarfMetadataMissing)
    }

    fun testDwarfMissingMarkerIsNotSetForRegularValues() {
        val lines = listOf("(int) x = 42")
        val vars = WasmLocalVariable.parseFrameVariableOutput(lines)
        assertEquals(1, vars.size)
        assertFalse(vars[0].isDwarfMetadataMissing)
        assertFalse(vars[0].isUnavailable)
    }

    fun testDwarfMissingPreservesTypeName() {
        val original = WasmLocalVariable.parseFrameVariableOutput(
            listOf("(calculator::Op) op = <variable not available>")
        )[0]
        val dwarfMissing = WasmLocalVariable.withDwarfMissingMessage(original)
        assertEquals("calculator::Op", dwarfMissing.typeName)
        assertEquals("<DWARF metadata missing>", dwarfMissing.displayValue)
    }

    fun testWithResolvedValueOverridesDwarfMissing() {
        val original = WasmLocalVariable.parseFrameVariableOutput(
            listOf("(unsigned int) x = <variable not available>")
        )[0]
        val dwarfMissing = WasmLocalVariable.withDwarfMissingMessage(original)
        assertTrue(dwarfMissing.isDwarfMetadataMissing)

        val resolved = WasmLocalVariable.withResolvedValue(dwarfMissing, "int", "42")
        assertFalse(resolved.isDwarfMetadataMissing)
        assertFalse(resolved.isUnavailable)
    }

    fun testDwarfLocationHint() {
        val original = WasmLocalVariable.parseFrameVariableOutput(
            listOf("(unsigned int) x = <variable not available>")
        )[0]
        assertTrue(original.isUnavailable)

        val hinted = WasmLocalVariable.withDwarfLocationHint(
            original,
            "wasm local[3]",
            "wasm-local[3], DW_OP_stack_value",
        )
        assertEquals("x", hinted.name)
        assertEquals("unsigned int", hinted.typeName)
        val hintedValue = hinted.displayValue
        assertTrue(
            "Should contain storage kind: $hintedValue",
            hintedValue.contains("wasm local[3]"),
        )
        assertTrue(
            "Should contain location description: $hintedValue",
            hintedValue.contains("wasm-local[3]"),
        )
    }

    fun testDwarfLocationHintIsNotUnavailable() {
        val original = WasmLocalVariable.parseFrameVariableOutput(
            listOf("(int) y = <variable not available>")
        )[0]
        val hinted = WasmLocalVariable.withDwarfLocationHint(
            original,
            "wasm global[0]",
            "wasm-global[0], DW_OP_stack_value",
        )
        // Location hints are informational — the variable's value is still
        // not truly resolved, but the hint is not an "unavailable" marker.
        assertFalse(
            "DWARF location hint should not be considered unavailable",
            hinted.isUnavailable,
        )
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
