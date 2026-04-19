package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import java.io.File

class WamrImportStubFilesTest : UsefulTestCase() {

    fun testNoStubsForModuleWithoutImports() {
        val wasm = wat2wasm("""
            (module
              (func (export "main") (result i32)
                i32.const 42
              )
            )
        """.trimIndent())

        val stubDir = WamrCommandLineState.generateImportStubFiles(wasm)
        assertNull("No stub directory should be created for a module without imports", stubDir)
    }

    fun testNullBytesReturnsNull() {
        val stubDir = WamrCommandLineState.generateImportStubFiles(null)
        assertNull("Null bytes should return null", stubDir)
    }

    fun testStubFilesCreatedForWasmBindgenPlaceholders() {
        val wasm = wat2wasm("""
            (module
              (import "__wbindgen_placeholder__" "__wbindgen_describe" (func (param i32)))
              (import "__wbindgen_placeholder__" "__wbg___wbindgen_throw_6b64449b9b9ed33c" (func (param i32 i32)))
              (import "__wbindgen_externref_xform__" "__wbindgen_externref_table_grow" (func (param i32) (result i32)))
              (import "__wbindgen_externref_xform__" "__wbindgen_externref_table_set_null" (func (param i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubDir = WamrCommandLineState.generateImportStubFiles(wasm)
        assertNotNull("Stub directory should be created", stubDir)
        assertTrue("Stub directory should exist", stubDir!!.exists())
        assertTrue("Stub directory should be a directory", stubDir.isDirectory)

        val placeholderStub = File(stubDir, "__wbindgen_placeholder__.wasm")
        assertTrue("Placeholder stub file should exist", placeholderStub.exists())
        assertTrue("Placeholder stub should be non-empty", placeholderStub.length() > 0)

        val externrefStub = File(stubDir, "__wbindgen_externref_xform__.wasm")
        assertTrue("Externref stub file should exist", externrefStub.exists())
        assertTrue("Externref stub should be non-empty", externrefStub.length() > 0)

        // Verify stubs are valid Wasm modules
        assertValidWasmModule(placeholderStub.readBytes())
        assertValidWasmModule(externrefStub.readBytes())

        // Verify placeholder stub exports match imports
        val placeholderProvider = WasmBinaryProvider(placeholderStub.readBytes().toCommonByteBuffer())
        val placeholderExports = placeholderProvider.exports
        assertEquals(2, placeholderExports.size)
        assertEquals("__wbindgen_describe", placeholderExports[0].name)
        assertEquals("__wbg___wbindgen_throw_6b64449b9b9ed33c", placeholderExports[1].name)

        // Verify externref stub exports match imports
        val externrefProvider = WasmBinaryProvider(externrefStub.readBytes().toCommonByteBuffer())
        val externrefExports = externrefProvider.exports
        assertEquals(2, externrefExports.size)
        assertEquals("__wbindgen_externref_table_grow", externrefExports[0].name)
        assertEquals("__wbindgen_externref_table_set_null", externrefExports[1].name)
    }

    fun testStubFilesForMixedImports() {
        val wasm = wat2wasm("""
            (module
              (import "env" "memory" (memory 1))
              (import "env" "log" (func (param i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubDir = WamrCommandLineState.generateImportStubFiles(wasm)
        assertNotNull("Stub directory should be created", stubDir)

        val envStub = File(stubDir!!, "env.wasm")
        assertTrue("env stub file should exist", envStub.exists())
        assertValidWasmModule(envStub.readBytes())

        val envProvider = WasmBinaryProvider(envStub.readBytes().toCommonByteBuffer())
        val envExports = envProvider.exports
        assertEquals(2, envExports.size)
    }

    fun testMultipleImportModulesCreateSeparateFiles() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (import "math" "sin" (func (param f64) (result f64)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubDir = WamrCommandLineState.generateImportStubFiles(wasm)
        assertNotNull("Stub directory should be created", stubDir)

        val envStub = File(stubDir!!, "env.wasm")
        assertTrue("env stub file should exist", envStub.exists())
        assertValidWasmModule(envStub.readBytes())

        val mathStub = File(stubDir, "math.wasm")
        assertTrue("math stub file should exist", mathStub.exists())
        assertValidWasmModule(mathStub.readBytes())
    }

    private fun assertValidWasmModule(bytes: ByteArray) {
        val buffer = bytes.toCommonByteBuffer()
        val result = WasmBinaryProvider(buffer)
        assertTrue("Stub should be a valid Wasm module", result.canProvideWasmModule())
    }
}
