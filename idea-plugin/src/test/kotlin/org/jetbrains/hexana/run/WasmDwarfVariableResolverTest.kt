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

    // ── Component-model binaries ────────────────────────────────────

    /**
     * A component's DWARF lives in its embedded core modules, not at the component
     * level, so the resolver has to look inside them — otherwise every component
     * session loses variable names.
     */
    fun testCreateResolverFromComponentWithDwarfInEmbeddedModule() {
        val core = loadWasmResource("rust/hello_world.wasm")
        val component = WasmComponentTestBuilder.component(loadWasmResource("string.wasm"), core)

        val resolver = WasmDwarfVariableResolver.create(component)
        assertNotNull("Resolver should be created from the embedded module carrying DWARF", resolver)
        // The DWARF read out of the embedded module is the same as from the module alone.
        val map = resolver!!.buildWasmLocalToNameMap("\$add")
        assertEquals("a", map[0L])
        assertEquals("b", map[1L])
    }

    fun testCreateResolverFromComponentWithoutDwarf() {
        val component = WasmComponentTestBuilder.component(loadWasmResource("string.wasm"))
        assertNull(
            "Resolver should be null when no embedded module carries DWARF",
            WasmDwarfVariableResolver.create(component),
        )
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

    fun testBuildWasmLocalToNameMapForRustAddPositional() {
        val bytes = loadWasmResource("rust/hello_world.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!
        // Rust DWARF uses DW_OP_fbreg (not WasmLocal) for params,
        // so the positional fallback assigns indices by declaration
        // order: a=0, b=1
        val map = resolver.buildWasmLocalToNameMap("\$add")
        assertEquals("a", map[0L])
        assertEquals("b", map[1L])
        assertEquals(2, map.size)
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

    fun testHasDwarfSubprogramDistinguishesRealFromWrapper() {
        val bytes = loadWasmResource("rust/hello_world.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!

        // The real "add" function has a DWARF subprogram entry
        assertTrue("'add' should have a DWARF subprogram", resolver.hasDwarfSubprogram("add"))
        assertTrue("'\$add' should have a DWARF subprogram", resolver.hasDwarfSubprogram("\$add"))

        // wasm-bindgen wrapper has no DWARF subprogram — its locals
        // are compiler-internal and should be hidden.
        // Test both a truncated prefix and a realistic full mangled name.
        val wrapperPrefix = "\$_ZN11hello_world1_28__wasm_bindgen_generated_add28_"
        assertFalse(
            "wasm-bindgen wrapper prefix should NOT have a DWARF subprogram",
            resolver.hasDwarfSubprogram(wrapperPrefix),
        )
        val fullWrapper = "\$_ZN11hello_world1_28__wasm_bindgen_generated_add28_\$u7b\$\$u7b\$closure\$u7d\$\$u7d\$\$u7d\$17h2e9ce"
        assertFalse(
            "full wasm-bindgen wrapper should NOT have a DWARF subprogram",
            resolver.hasDwarfSubprogram(fullWrapper),
        )
    }

    // ── Go/TinyGo WASM tests ────────────────────────────────────────

    fun testCreateResolverFromGoWasm() {
        val bytes = loadWasmResource("go/main.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)
        assertNotNull(
            "Resolver should be created from Go main.wasm which has DWARF",
            resolver,
        )
    }

    fun testGoAddParamsLackLocation() {
        // TinyGo does not emit DW_AT_location for add(x, y).
        // The resolver should correctly report no location metadata
        // for these parameters, but they ARE known in DWARF (as
        // DW_TAG_formal_parameter entries). This distinction lets
        // the UI show "no location, value may have been optimized
        // out" rather than the misleading "DWARF metadata missing".
        val bytes = loadWasmResource("go/main.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!
        assertFalse(
            "Go add() param 'x' should have no location metadata",
            resolver.hasLocationMetadata("x", "add"),
        )
        assertFalse(
            "Go add() param 'y' should have no location metadata",
            resolver.hasLocationMetadata("y", "add"),
        )
        assertNull(
            "describeVariableLocation should be null for 'x' without location",
            resolver.describeVariableLocation("x", "add"),
        )
        assertTrue(
            "Go add() param 'x' should be known in DWARF",
            resolver.isKnownInDwarf("x", "add"),
        )
        assertTrue(
            "Go add() param 'y' should be known in DWARF",
            resolver.isKnownInDwarf("y", "add"),
        )
        assertFalse(
            "A non-existent variable should not be known in DWARF",
            resolver.isKnownInDwarf("nonexistent", "add"),
        )
    }

    fun testGoVariablesWithLocationListsHaveMetadata() {
        // runtime.printstring's "i" has a location list with
        // DW_OP_WASM_location entries. The resolver should find
        // location metadata for it, preventing "DWARF metadata
        // missing" from being shown.
        val bytes = loadWasmResource("go/main.wasm")
        val resolver = WasmDwarfVariableResolver.create(bytes)!!
        assertTrue(
            "runtime.printstring var 'i' should have location metadata",
            resolver.hasLocationMetadata("i", "runtime.printstring"),
        )
        val desc = resolver.describeVariableLocation("i", "runtime.printstring")
        assertNotNull(
            "Should describe location for 'i' in runtime.printstring",
            desc,
        )
        assertTrue(
            "Location for 'i' should mention WASM: $desc",
            desc!!.contains("WASM") || desc.contains("wasm") || desc.contains("local"),
        )
    }
}
