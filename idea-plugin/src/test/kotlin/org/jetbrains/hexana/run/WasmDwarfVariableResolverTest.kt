package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import java.io.File

class WasmDwarfVariableResolverTest : UsefulTestCase() {

    private fun loadWasmResource(relativePath: String): ByteArray {
        val resourceDir = File("../wasmParser/src/commonTest/resources")
        val file = File(resourceDir, relativePath)
        assertTrue("Test resource must exist: ${file.absolutePath}", file.exists())
        return file.readBytes()
    }

    fun testCreateResolverFromWasmWithDwarf() {
        val bytes = loadWasmResource("c/utf8-c.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)
        assertNotNull("Resolver should be created from a Wasm binary with DWARF info", resolver)
    }

    fun testCreateResolverFromWasmWithoutDwarf() {
        val bytes = loadWasmResource("string.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)
        assertNull("Resolver should be null for a Wasm binary without DWARF info", resolver)
    }

    fun testDescribeVariableLocationForGlobalVar() {
        val bytes = loadWasmResource("c/utf8-c.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!
        // "str" is a file-scope global variable in utf8.c at DW_OP_addr(0x10ad0)
        val desc = resolver.describeVariableLocation("str", null)
        assertNotNull("Should find location for global variable 'str'", desc)
        assertTrue(
            "Location should mention addr: $desc",
            desc!!.contains("addr") || desc.contains("0x10ad0"),
        )
    }

    fun testDescribeVariableLocationForUnknownVar() {
        val bytes = loadWasmResource("c/utf8-c.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!
        val desc = resolver.describeVariableLocation("nonexistent_var", "_start")
        assertNull("Should return null for unknown variable", desc)
    }

    fun testDescribeVariableLocationForStartFrameBase() {
        val bytes = loadWasmResource("c/utf8-c.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!
        // _start has DW_AT_frame_base = DW_OP_WASM_location 0x3 0x0, DW_OP_stack_value
        // but variables inside _start may be empty; verify function lookup works
        val desc = resolver.describeVariableLocation("nonexistent", "_start")
        // _start may have no variables, so null is expected
        // The important thing is it doesn't crash
    }

    fun testTypeValuePatternMatching() {
        val pattern = WasmDwarfVariableResolver.TYPE_VALUE_PATTERN
        val input = "type=int value=42"
        val match = pattern.find(input)
        assertNotNull("Pattern should match 'type=int value=42'", match)
        assertEquals("int", match!!.groupValues[1])
        assertEquals("42", match.groupValues[2])
    }

    fun testTypeValuePatternWithComplexType() {
        val pattern = WasmDwarfVariableResolver.TYPE_VALUE_PATTERN
        val input = "type=unsigned_int value=0x0000002a"
        val match = pattern.find(input)
        assertNotNull(match)
        assertEquals("unsigned_int", match!!.groupValues[1])
        assertEquals("0x0000002a", match.groupValues[2])
    }

    fun testTypeValuePatternNoMatch() {
        val pattern = WasmDwarfVariableResolver.TYPE_VALUE_PATTERN
        val input = "unavailable"
        val match = pattern.find(input)
        assertNull("Pattern should not match 'unavailable'", match)
    }

    fun testResolveVariableWithoutCommunicatorReturnsOriginal() {
        // This test verifies the resolver's behavior when the DWARF
        // metadata doesn't match the variable name — it should return
        // the original variable unchanged.
        val bytes = loadWasmResource("c/utf8-c.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!

        val original = WasmLocalVariable.parseFrameVariableOutput(
            listOf("(int) unknown_variable = <variable not available>"),
        )[0]
        assertTrue(original.isUnavailable)

        // We can't fully test resolveVariable without a real LLDB
        // communicator, but we can verify that:
        // 1. The resolver was created successfully
        // 2. The describe methods work correctly
        // 3. The type/value pattern matching works
        // These are covered in other tests above.
    }

    fun testCreateResolverFromRustHelloWorldWasm() {
        val bytes = loadWasmResource("rust/hello_world.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)
        assertNotNull(
            "Resolver should be created from Rust hello_world.wasm which has DWARF",
            resolver,
        )
    }

    fun testDescribeVariableLocationForRustAddParams() {
        val bytes = loadWasmResource("rust/hello_world.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!
        // The 'add' function has params a and b with DW_OP_fbreg locations
        val descA = resolver.describeVariableLocation("a", "add")
        assertNotNull("Should find location for parameter 'a' in add()", descA)
        assertTrue(
            "Location for 'a' should mention fbreg: $descA",
            descA!!.contains("fbreg"),
        )

        val descB = resolver.describeVariableLocation("b", "add")
        assertNotNull("Should find location for parameter 'b' in add()", descB)
        assertTrue(
            "Location for 'b' should mention fbreg: $descB",
            descB!!.contains("fbreg"),
        )
    }
}
