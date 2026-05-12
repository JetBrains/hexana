package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.ByteSequence
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser

class WasmImportStubGeneratorTest : UsefulTestCase() {

    fun testNoImports() {
        val wasm = wat2wasm("""
            (module
              (func (export "main") (result i32)
                i32.const 42
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertTrue("No stubs should be generated for a module without imports", stubs.isEmpty())
    }

    fun testFunctionImportStub() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (func (export "main") (result i32)
                i32.const 42
                call 0
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals("Should generate one stub module for 'env'", 1, stubs.size)
        assertEquals("env", stubs[0].moduleName)

        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals(1, exports.size)
        assertEquals("log", exports[0].name)
    }

    fun testMultipleImportsFromSameModule() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log_i32" (func (param i32)))
              (import "env" "log_f64" (func (param f64)))
              (import "env" "get_value" (func (result i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(1, stubs.size)
        assertEquals("env", stubs[0].moduleName)

        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals(3, exports.size)
        assertEquals("log_i32", exports[0].name)
        assertEquals("log_f64", exports[1].name)
        assertEquals("get_value", exports[2].name)
    }

    fun testMultipleImportModules() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (import "math" "sin" (func (param f64) (result f64)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(2, stubs.size)

        val envStub = stubs.first { it.moduleName == "env" }
        val mathStub = stubs.first { it.moduleName == "math" }

        assertValidWasmModule(envStub.wasmBytes)
        assertValidWasmModule(mathStub.wasmBytes)

        val envExports = WasmBinaryProvider(envStub.wasmBytes.toCommonByteBuffer()).exports
        assertEquals(1, envExports.size)
        assertEquals("log", envExports[0].name)

        val mathExports = WasmBinaryProvider(mathStub.wasmBytes.toCommonByteBuffer()).exports
        assertEquals(1, mathExports.size)
        assertEquals("sin", mathExports[0].name)
    }

    fun testMemoryImportStub() {
        val wasm = wat2wasm("""
            (module
              (import "env" "memory" (memory 1))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(1, stubs.size)
        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals(1, exports.size)
        assertEquals("memory", exports[0].name)
    }

    fun testGlobalImportStub() {
        val wasm = wat2wasm("""
            (module
              (import "env" "stack_ptr" (global (mut i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(1, stubs.size)
        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals(1, exports.size)
        assertEquals("stack_ptr", exports[0].name)
    }

    fun testTableImportStub() {
        val wasm = wat2wasm("""
            (module
              (import "env" "table" (table 1 funcref))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(1, stubs.size)
        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals(1, exports.size)
        assertEquals("table", exports[0].name)
    }

    fun testMixedImportTypes() {
        val wasm = wat2wasm("""
            (module
              (import "env" "memory" (memory 1))
              (import "env" "log" (func (param i32)))
              (import "env" "stack_ptr" (global (mut i32)))
              (import "env" "table" (table 1 funcref))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(1, stubs.size)
        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals(4, exports.size)
    }

    fun testStubsWorkWithGraalWasm() {
        val wasm = wat2wasm("""
            (module
              (import "env" "get_value" (func (result i32)))
              (func (export "_start")
                call 0
                drop
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(1, stubs.size)

        val context = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        context.use { ctx ->
            val importObject = WasmImportStubGenerator.instantiateStubs(ctx, stubs)

            val mainSource = Source.newBuilder("wasm", ByteSequence.create(wasm), "test")
                .build()
            val mainModule = ctx.eval(mainSource)
            val mainInstance = mainModule.newInstance(importObject)
            val exports = mainInstance.getMember("exports")

            val startFunc = exports.getMember("_start")
            assertNotNull("_start should be accessible", startFunc)
            assertTrue("_start should be executable", startFunc.canExecute())
            startFunc.execute()
        }
    }

    fun testStubsWithMultiReturnFunction() {
        val wasm = wat2wasm("""
            (module
              (import "env" "compute" (func (param i32 i32) (result i64)))
              (func (export "_start")
                i32.const 1
                i32.const 2
                call 0
                drop
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals(1, stubs.size)

        val context = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        context.use { ctx ->
            val importObject = WasmImportStubGenerator.instantiateStubs(ctx, stubs)

            val mainSource = Source.newBuilder("wasm", ByteSequence.create(wasm), "test")
                .build()
            val mainInstance = ctx.eval(mainSource).newInstance(importObject)
            val exports = mainInstance.getMember("exports")

            val startFunc = exports.getMember("_start")
            assertNotNull(startFunc)
            startFunc.execute()
        }
    }

    fun testStubsWithMemoryAndFunctionImports() {
        val wasm = wat2wasm("""
            (module
              (import "env" "memory" (memory 1))
              (import "env" "log" (func (param i32 i32)))
              (func (export "_start")
                i32.const 0
                i32.const 5
                call 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateStubs(wasm)

        val context = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        context.use { ctx ->
            val importObject = WasmImportStubGenerator.instantiateStubs(ctx, stubs)

            val mainSource = Source.newBuilder("wasm", ByteSequence.create(wasm), "test")
                .build()
            val mainInstance = ctx.eval(mainSource).newInstance(importObject)
            val exports = mainInstance.getMember("exports")

            val startFunc = exports.getMember("_start")
            assertNotNull(startFunc)
            startFunc.execute()
        }
    }

    fun testHasImportsWithNoImports() {
        val wasm = wat2wasm("""
            (module
              (func (export "main") (result i32)
                i32.const 42
              )
            )
        """.trimIndent())

        assertFalse("Module without imports should return false", WasmImportStubGenerator.hasImports(wasm))
    }

    fun testHasImportsWithFunctionImport() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        assertTrue("Module with function import should return true", WasmImportStubGenerator.hasImports(wasm))
    }

    fun testHasImportsWithMemoryImport() {
        val wasm = wat2wasm("""
            (module
              (import "env" "memory" (memory 1))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        assertTrue("Module with memory import should return true", WasmImportStubGenerator.hasImports(wasm))
    }

    fun testHasNonFunctionImportsWithOnlyFunctions() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (import "env" "get" (func (result i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        assertFalse(
            "Module with only function imports should return false",
            WasmImportStubGenerator.hasNonFunctionImports(wasm),
        )
    }

    fun testHasNonFunctionImportsWithMemory() {
        val wasm = wat2wasm("""
            (module
              (import "env" "memory" (memory 1))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        assertTrue(
            "Module with memory import should return true",
            WasmImportStubGenerator.hasNonFunctionImports(wasm),
        )
    }

    fun testHasNonFunctionImportsWithGlobal() {
        val wasm = wat2wasm("""
            (module
              (import "env" "base" (global i32))
              (func (export "main") (result i32)
                global.get 0
              )
            )
        """.trimIndent())

        assertTrue(
            "Module with global import should return true",
            WasmImportStubGenerator.hasNonFunctionImports(wasm),
        )
    }

    fun testHasNonFunctionImportsWithMixed() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (import "env" "memory" (memory 1))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        assertTrue(
            "Module with mixed imports should return true for non-function imports",
            WasmImportStubGenerator.hasNonFunctionImports(wasm),
        )
    }

    fun testGenerateNonFunctionStubsIncludesFunctionsFromSameModule() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (import "env" "memory" (memory 1))
              (import "env" "base" (global i32))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasm)
        assertEquals("Should generate one stub module for 'env'", 1, stubs.size)
        assertEquals("env", stubs[0].moduleName)

        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals("Should export all imports including functions from the same module", 3, exports.size)
        assertEquals("log", exports[0].name)
        assertEquals("memory", exports[1].name)
        assertEquals("base", exports[2].name)
    }

    fun testGenerateNonFunctionStubsReturnsEmptyForFunctionOnlyImports() {
        val wasm = wat2wasm("""
            (module
              (import "env" "log" (func (param i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasm)
        assertTrue("Should generate no stubs for function-only imports", stubs.isEmpty())
    }

    fun testGenerateNonFunctionStubsMultipleModules() {
        val wasm = wat2wasm("""
            (module
              (import "env" "memory" (memory 1))
              (import "host" "table" (table 1 funcref))
              (import "env" "log" (func (param i32)))
              (func (export "main") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasm)
        assertEquals("Should generate two stub modules", 2, stubs.size)

        val envStub = stubs.first { it.moduleName == "env" }
        val hostStub = stubs.first { it.moduleName == "host" }

        assertValidWasmModule(envStub.wasmBytes)
        assertValidWasmModule(hostStub.wasmBytes)

        val envExports = WasmBinaryProvider(envStub.wasmBytes.toCommonByteBuffer()).exports
        assertEquals("env stub should export memory and function", 2, envExports.size)
        assertEquals("memory", envExports[0].name)
        assertEquals("log", envExports[1].name)

        val hostExports = WasmBinaryProvider(hostStub.wasmBytes.toCommonByteBuffer()).exports
        assertEquals(1, hostExports.size)
        assertEquals("table", hostExports[0].name)
    }

    fun testNonFunctionStubsDoNotConflictWithUnknownImportsDefault() {
        // Reproduces the wasmtime "import of `foo::bar` defined twice" error.
        // When a module imports a memory from "foo" and functions from other
        // modules, the generated stub for "foo" must include ALL imports from
        // "foo" (even though there are no function imports from "foo") so that
        // wasmtime's --preload fully owns the "foo" namespace and
        // -W unknown-imports-default does not try to redefine "foo::bar".
        val wasm = wat2wasm("""
            (module
              (memory (export "memory") (import "foo" "bar") 1 1 shared)
              (func (import "wasi" "thread-spawn") (param i32) (result i32))
              (func (import "wasi_snapshot_preview1" "proc_exit") (param i32))
              (func (export "_start")
                unreachable
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasm)
        assertEquals("Should generate one stub for 'foo' (the only module with non-function imports)", 1, stubs.size)
        assertEquals("foo", stubs[0].moduleName)

        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals("Stub should export the memory 'bar'", 1, exports.size)
        assertEquals("bar", exports[0].name)

        // Verify that no stub is generated for "wasi" or "wasi_snapshot_preview1"
        // — those are function-only modules handled by -W unknown-imports-default.
        assertTrue(stubs.none { it.moduleName == "wasi" })
        assertTrue(stubs.none { it.moduleName == "wasi_snapshot_preview1" })
    }

    fun testNonFunctionStubsIncludeFunctionsFromMixedModule() {
        // When a module has both function AND non-function imports from the
        // same module, the stub must include function stubs too, otherwise
        // wasmtime would try to resolve those functions via
        // -W unknown-imports-default and fail with "defined twice".
        val wasm = wat2wasm("""
            (module
              (memory (import "foo" "mem") 1 1 shared)
              (func (import "foo" "do_something") (param i32))
              (func (import "other" "log") (param i32))
              (func (export "_start")
                unreachable
              )
            )
        """.trimIndent())

        val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasm)
        assertEquals("Should generate one stub for 'foo'", 1, stubs.size)
        assertEquals("foo", stubs[0].moduleName)

        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals("Stub should export both mem and do_something", 2, exports.size)
        assertEquals("mem", exports[0].name)
        assertEquals("do_something", exports[1].name)

        // "other" module only has function imports — no stub needed.
        assertTrue(stubs.none { it.moduleName == "other" })
    }

    fun testDuplicateImportNamesDeduplication() {
        // Build a Wasm module that imports the same module::name twice
        // (different type signatures). This is legal in the core spec but
        // the generated stub must not export the same name twice because
        // runtimes like wasmtime reject duplicate exports.
        val wasm = buildWasmWithDuplicateImports()

        val stubs = WasmImportStubGenerator.generateStubs(wasm)
        assertEquals("Should generate one stub module for 'foo'", 1, stubs.size)
        assertEquals("foo", stubs[0].moduleName)

        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals("Duplicate import name 'bar' should be deduplicated to one export", 1, exports.size)
        assertEquals("bar", exports[0].name)
    }

    fun testDuplicateImportNamesNonFunctionStubsDeduplication() {
        // Build a Wasm module that imports the same memory name twice
        // from the same module.
        val wasm = buildWasmWithDuplicateMemoryImports()

        val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasm)
        assertEquals("Should generate one stub module for 'env'", 1, stubs.size)
        assertEquals("env", stubs[0].moduleName)

        assertValidWasmModule(stubs[0].wasmBytes)

        val stubProvider = WasmBinaryProvider(stubs[0].wasmBytes.toCommonByteBuffer())
        val exports = stubProvider.exports
        assertEquals("Duplicate memory import 'memory' should be deduplicated to one export", 1, exports.size)
        assertEquals("memory", exports[0].name)
    }

    /**
     * Builds a minimal Wasm module that imports `foo::bar` twice with
     * different function signatures: `(func (param i32))` and `(func (param i64))`.
     */
    private fun buildWasmWithDuplicateImports(): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        // magic + version
        buf.write(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        buf.write(byteArrayOf(0x01, 0x00, 0x00, 0x00))

        // Type section (id=1): 2 func types
        // type 0: (param i32) -> ()
        // type 1: (param i64) -> ()
        val typePayload = byteArrayOf(
            0x02,                   // 2 types
            0x60, 0x01, 0x7F, 0x00, // func type 0: (i32) -> ()
            0x60, 0x01, 0x7E, 0x00, // func type 1: (i64) -> ()
        )
        buf.write(0x01) // section id
        buf.write(typePayload.size) // section size
        buf.write(typePayload)

        // Import section (id=2): 2 imports, both "foo" "bar"
        val importPayload = java.io.ByteArrayOutputStream()
        importPayload.write(0x02) // 2 imports
        // import 0: "foo" "bar" (func type 0)
        importPayload.write(0x03) // len("foo")
        importPayload.write("foo".toByteArray())
        importPayload.write(0x03) // len("bar")
        importPayload.write("bar".toByteArray())
        importPayload.write(0x00) // func import
        importPayload.write(0x00) // type index 0
        // import 1: "foo" "bar" (func type 1)
        importPayload.write(0x03) // len("foo")
        importPayload.write("foo".toByteArray())
        importPayload.write(0x03) // len("bar")
        importPayload.write("bar".toByteArray())
        importPayload.write(0x00) // func import
        importPayload.write(0x01) // type index 1

        val importBytes = importPayload.toByteArray()
        buf.write(0x02) // section id
        buf.write(importBytes.size) // section size
        buf.write(importBytes)

        return buf.toByteArray()
    }

    /**
     * Builds a minimal Wasm module that imports `env::memory` as a memory twice.
     */
    private fun buildWasmWithDuplicateMemoryImports(): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        // magic + version
        buf.write(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        buf.write(byteArrayOf(0x01, 0x00, 0x00, 0x00))

        // Import section (id=2): 2 memory imports, both "env" "memory"
        val importPayload = java.io.ByteArrayOutputStream()
        importPayload.write(0x02) // 2 imports
        // import 0: "env" "memory" (memory min=1)
        importPayload.write(0x03) // len("env")
        importPayload.write("env".toByteArray())
        importPayload.write(0x06) // len("memory")
        importPayload.write("memory".toByteArray())
        importPayload.write(0x02) // memory import
        importPayload.write(0x00) // limits: no max
        importPayload.write(0x01) // min = 1
        // import 1: "env" "memory" (memory min=2)
        importPayload.write(0x03) // len("env")
        importPayload.write("env".toByteArray())
        importPayload.write(0x06) // len("memory")
        importPayload.write("memory".toByteArray())
        importPayload.write(0x02) // memory import
        importPayload.write(0x00) // limits: no max
        importPayload.write(0x02) // min = 2

        val importBytes = importPayload.toByteArray()
        buf.write(0x02) // section id
        buf.write(importBytes.size) // section size
        buf.write(importBytes)

        return buf.toByteArray()
    }

    fun testGraalWasmTypedArgumentPassing() {
        val wasm = wat2wasm("""
            (module
              (func (export "add") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.add
              )
            )
        """.trimIndent())

        val context = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        context.use { ctx ->
            val mainSource = Source.newBuilder("wasm", ByteSequence.create(wasm), "test")
                .build()
            val mainModule = ctx.eval(mainSource)
            val mainInstance = mainModule.newInstance()
            val exports = mainInstance.getMember("exports")

            val addFunc = exports.getMember("add")
            assertNotNull("add should be accessible", addFunc)
            assertTrue("add should be executable", addFunc.canExecute())

            // Passing typed Int values should work
            val result = addFunc.execute(3, 4)
            assertEquals(7, result.asInt())

            // Passing strings should fail (this is the bug we're fixing)
            try {
                addFunc.execute("3", "4")
                fail("Passing string arguments should throw an error for i32 params")
            } catch (_: Exception) {
                // Expected: GraalWasm rejects string arguments for numeric params
            }
        }
    }

    fun testGraalWasmTypedArgumentPassingI64() {
        val wasm = wat2wasm("""
            (module
              (func (export "add64") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.add
              )
            )
        """.trimIndent())

        val context = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        context.use { ctx ->
            val mainSource = Source.newBuilder("wasm", ByteSequence.create(wasm), "test")
                .build()
            val mainInstance = ctx.eval(mainSource).newInstance()
            val exports = mainInstance.getMember("exports")

            val addFunc = exports.getMember("add64")
            assertNotNull(addFunc)
            val result = addFunc.execute(100L, 200L)
            assertEquals(300L, result.asLong())
        }
    }

    fun testGraalWasmTypedArgumentPassingF32() {
        val wasm = wat2wasm("""
            (module
              (func (export "add_f32") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.add
              )
            )
        """.trimIndent())

        val context = Context.newBuilder("wasm")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        context.use { ctx ->
            val mainSource = Source.newBuilder("wasm", ByteSequence.create(wasm), "test")
                .build()
            val mainInstance = ctx.eval(mainSource).newInstance()
            val exports = mainInstance.getMember("exports")

            val addFunc = exports.getMember("add_f32")
            assertNotNull(addFunc)
            val result = addFunc.execute(1.5f, 2.5f)
            assertEquals(4.0f, result.asFloat())
        }
    }

    private fun assertValidWasmModule(bytes: ByteArray) {
        val buffer = bytes.toCommonByteBuffer()
        val result = WasmParser().parse(buffer)
        assertTrue("Generated stub should be a valid Wasm module", result is WasmParseSuccess)
        val module = (result as WasmParseSuccess).wasmModule
        assertTrue("Generated stub should be a core Wasm module", module is WasmModule)
    }
}
