package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f64
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for WASM memory instructions — loads, stores, memory.size, memory.grow.
 *
 * All tests use inline WAT compiled via wat2wasm.
 * Verifies opcode type, MemArg alignment, and MemArg offset.
 */
class MemoryInstructionTest {

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records[0].opCodes
    }

    private suspend fun parseAllFunctions(wat: String): List<List<OpCode>> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records.map { it.opCodes }
    }

    // ========================================================================
    // i32 loads
    // ========================================================================

    @Test
    fun `i32 load — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance< i32.Load>().single()
        assertEquals(MemArg(2, 0), load.value, "i32.load default: align=2, offset=0")
    }

    @Test
    fun `i32 load — with offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load offset=16
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i32.Load>().single()
        assertEquals(16, load.value.offset, "offset should be 16")
    }

    @Test
    fun `i32 load — with explicit alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load align=1
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance< OpCodes.Memory.i32.Load>().single()
        assertEquals(0, load.value.align, "align=1 encodes as log2(1)=0")
    }

    @Test
    fun `i32 load — offset and alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load offset=100 align=2
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<OpCodes.Memory.i32.Load>().single()
        assertEquals(100, load.value.offset)
        assertEquals(1, load.value.align, "align=2 encodes as log2(2)=1")
    }

    @Test
    fun `i32 load8_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load8_s
              )
            )
        """.trimIndent())
        assertIs<OpCodes.Memory.i32.Load8_S>(ops.last())
    }

    @Test
    fun `i32 load8_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load8_u
              )
            )
        """.trimIndent())
        assertIs<i32.Load8_U>(ops.last())
    }

    @Test
    fun `i32 load16_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load16_s
              )
            )
        """.trimIndent())
        assertIs<i32.Load16_S>(ops.last())
    }

    @Test
    fun `i32 load16_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load16_u
              )
            )
        """.trimIndent())
        assertIs<i32.Load16_U>(ops.last())
    }

    @Test
    fun `i32 load8_u — with offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load8_u offset=343
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i32.Load8_U>().single()
        assertEquals(343, load.value.offset)
    }

    // ========================================================================
    // i32 stores
    // ========================================================================

    @Test
    fun `i32 store — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.store
              )
            )
        """.trimIndent())
        val store = ops.filterIsInstance<i32.Store>().single()
        assertEquals(MemArg(2, 0), store.value, "i32.store default: align=2, offset=0")
    }

    @Test
    fun `i32 store — with offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.store offset=400
              )
            )
        """.trimIndent())
        val store = ops.filterIsInstance<i32.Store>().single()
        assertEquals(400, store.value.offset)
    }

    @Test
    fun `i32 store8`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.store8
              )
            )
        """.trimIndent())
        assertIs<i32.Store8>(ops.last())
    }

    @Test
    fun `i32 store16`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.store16
              )
            )
        """.trimIndent())
        assertIs<i32.Store16>(ops.last())
    }

    // ========================================================================
    // i64 loads
    // ========================================================================

    @Test
    fun `i64 load — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i64.Load>().single()
        assertEquals(MemArg(3, 0), load.value, "i64.load default: align=3, offset=0")
    }

    @Test
    fun `i64 load — with offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load offset=256
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i64.Load>().single()
        assertEquals(256, load.value.offset)
    }

    @Test
    fun `i64 load8_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load8_s
              )
            )
        """.trimIndent())
        assertIs<i64.Load8_S>(ops.last())
    }

    @Test
    fun `i64 load8_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load8_u
              )
            )
        """.trimIndent())
        assertIs<i64.Load8_U>(ops.last())
    }

    @Test
    fun `i64 load16_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load16_s
              )
            )
        """.trimIndent())
        assertIs<i64.Load16_S>(ops.last())
    }

    @Test
    fun `i64 load16_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load16_u
              )
            )
        """.trimIndent())
        assertIs<i64.Load16_U>(ops.last())
    }

    @Test
    fun `i64 load32_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load32_s
              )
            )
        """.trimIndent())
        assertIs<i64.Load32_S>(ops.last())
    }

    @Test
    fun `i64 load32_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load32_u
              )
            )
        """.trimIndent())
        assertIs<i64.Load32_U>(ops.last())
    }

    // ========================================================================
    // i64 stores
    // ========================================================================

    @Test
    fun `i64 store — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.store
              )
            )
        """.trimIndent())
        val store = ops.filterIsInstance<i64.Store>().single()
        assertEquals(MemArg(3, 0), store.value, "i64.store default: align=3, offset=0")
    }

    @Test
    fun `i64 store8`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.store8
              )
            )
        """.trimIndent())
        assertIs<i64.Store8>(ops.last())
    }

    @Test
    fun `i64 store16`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.store16
              )
            )
        """.trimIndent())
        assertIs<i64.Store16>(ops.last())
    }

    @Test
    fun `i64 store32`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.store32
              )
            )
        """.trimIndent())
        assertIs<i64.Store32>(ops.last())
    }

    // ========================================================================
    // f32 load/store
    // ========================================================================

    @Test
    fun `f32 load — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result f32)
                local.get 0
                f32.load
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<f32.Load>().single()
        assertEquals(MemArg(2, 0), load.value, "f32.load default: align=2, offset=0")
    }

    @Test
    fun `f32 load — with offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result f32)
                local.get 0
                f32.load offset=396
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<f32.Load>().single()
        assertEquals(396, load.value.offset)
    }

    @Test
    fun `f32 store — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 f32)
                local.get 0
                local.get 1
                f32.store
              )
            )
        """.trimIndent())
        val store = ops.filterIsInstance<f32.Store>().single()
        assertEquals(MemArg(2, 0), store.value)
    }

    @Test
    fun `f32 store — with offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 f32)
                local.get 0
                local.get 1
                f32.store offset=400
              )
            )
        """.trimIndent())
        val store = ops.filterIsInstance<f32.Store>().single()
        assertEquals(400, store.value.offset)
    }

    // ========================================================================
    // f64 load/store
    // ========================================================================

    @Test
    fun `f64 load — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result f64)
                local.get 0
                f64.load
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<f64.Load>().single()
        assertEquals(MemArg(3, 0), load.value, "f64.load default: align=3, offset=0")
    }

    @Test
    fun `f64 store — natural alignment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 f64)
                local.get 0
                local.get 1
                f64.store
              )
            )
        """.trimIndent())
        val store = ops.filterIsInstance<f64.Store>().single()
        assertEquals(MemArg(3, 0), store.value, "f64.store default: align=3, offset=0")
    }

    @Test
    fun `f64 load — with large offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result f64)
                local.get 0
                f64.load offset=65536
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<f64.Load>().single()
        assertEquals(65536, load.value.offset)
    }

    // ========================================================================
    // memory.size / memory.grow
    // ========================================================================

    @Test
    fun `memory size`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (result i32)
                memory.size
              )
            )
        """.trimIndent())
        assertIs<OpCodes.Memory.Size>(ops.single())
    }

    @Test
    fun `memory grow`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                memory.grow
              )
            )
        """.trimIndent())
        assertIs<OpCodes.Memory.Grow>(ops.last())
    }

    @Test
    fun `memory grow and size together`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (result i32)
                memory.size
                drop
                i32.const 1
                memory.grow
              )
            )
        """.trimIndent())
        assertIs<OpCodes.Memory.Size>(ops[0])
        assertIs<OpCodes.ParametricInstructions.Drop>(ops[1])
        assertIs<OpCodes.I32.Const>(ops[2])
        assertIs<OpCodes.Memory.Grow>(ops[3])
    }

    // ========================================================================
    // MemArg — alignment encoding
    // ========================================================================

    @Test
    fun `memarg — align=1 encodes as 0`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load align=1
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i32.Load>().single()
        assertEquals(0, load.value.align, "align=1 → log2(1)=0")
    }

    @Test
    fun `memarg — align=4 encodes as 2`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load align=4
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i32.Load>().single()
        assertEquals(2, load.value.align, "align=4 → log2(4)=2")
    }

    @Test
    fun `memarg — align=8 for i64`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load align=8
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i64.Load>().single()
        assertEquals(3, load.value.align, "align=8 → log2(8)=3")
    }

    @Test
    fun `memarg — zero offset`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load offset=0
              )
            )
        """.trimIndent())
        val load = ops.filterIsInstance<i32.Load>().single()
        assertEquals(0, load.value.offset)
    }

    // ========================================================================
    // Combined — multiple memory ops in one function
    // ========================================================================

    @Test
    fun `load-store roundtrip — i32`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.store offset=0
                local.get 0
                i32.load offset=0
                drop
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "All ops should parse: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(ops.any { it is i32.Store })
        assertTrue(ops.any { it is i32.Load })
    }

    @Test
    fun `all i32 load variants in one function`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load
                drop
                local.get 0
                i32.load8_s
                drop
                local.get 0
                i32.load8_u
                drop
                local.get 0
                i32.load16_s
                drop
                local.get 0
                i32.load16_u
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No unparsed: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(ops.any { it is i32.Load }, "Missing i32.load")
        assertTrue(ops.any { it is i32.Load8_S }, "Missing i32.load8_s")
        assertTrue(ops.any { it is i32.Load8_U }, "Missing i32.load8_u")
        assertTrue(ops.any { it is i32.Load16_S }, "Missing i32.load16_s")
        assertTrue(ops.any { it is i32.Load16_U }, "Missing i32.load16_u")
    }

    @Test
    fun `all i64 load variants in one function`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.load
                drop
                local.get 0
                i64.load8_s
                drop
                local.get 0
                i64.load8_u
                drop
                local.get 0
                i64.load16_s
                drop
                local.get 0
                i64.load16_u
                drop
                local.get 0
                i64.load32_s
                drop
                local.get 0
                i64.load32_u
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No unparsed: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(ops.any { it is i64.Load }, "Missing i64.load")
        assertTrue(ops.any { it is i64.Load8_S }, "Missing i64.load8_s")
        assertTrue(ops.any { it is i64.Load8_U }, "Missing i64.load8_u")
        assertTrue(ops.any { it is i64.Load16_S }, "Missing i64.load16_s")
        assertTrue(ops.any { it is i64.Load16_U }, "Missing i64.load16_u")
        assertTrue(ops.any { it is i64.Load32_S }, "Missing i64.load32_s")
        assertTrue(ops.any { it is i64.Load32_U }, "Missing i64.load32_u")
    }

    @Test
    fun `all i32 store variants in one function`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i32)
                local.get 0
                local.get 1
                i32.store
                local.get 0
                local.get 1
                i32.store8
                local.get 0
                local.get 1
                i32.store16
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No unparsed: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(ops.any { it is i32.Store }, "Missing i32.store")
        assertTrue(ops.any { it is i32.Store8 }, "Missing i32.store8")
        assertTrue(ops.any { it is i32.Store16 }, "Missing i32.store16")
    }

    @Test
    fun `all i64 store variants in one function`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i64)
                local.get 0
                local.get 1
                i64.store
                local.get 0
                local.get 1
                i64.store8
                local.get 0
                local.get 1
                i64.store16
                local.get 0
                local.get 1
                i64.store32
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No unparsed: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(ops.any { it is i64.Store }, "Missing i64.store")
        assertTrue(ops.any { it is i64.Store8 }, "Missing i64.store8")
        assertTrue(ops.any { it is i64.Store16 }, "Missing i64.store16")
        assertTrue(ops.any { it is i64.Store32 }, "Missing i64.store32")
    }

    // ========================================================================
    // Mixed types — real-world patterns
    // ========================================================================

    @Test
    fun `skiko pattern — i32 load chain with offsets`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.load offset=328
                local.get 0
                i32.load offset=344
                i32.load
                i32.add
              )
            )
        """.trimIndent())
        val loads = ops.filterIsInstance<i32.Load>()
        assertEquals(3, loads.size)
        assertEquals(328, loads[0].value.offset)
        assertEquals(344, loads[1].value.offset)
        assertEquals(0, loads[2].value.offset)
    }

    @Test
    fun `mixed float load-store`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32)
                local.get 0
                local.get 0
                f32.load offset=396
                f32.store offset=400
                local.get 0
                local.get 0
                f64.load offset=500
                f64.store offset=508
              )
            )
        """.trimIndent())
        val f32Load = ops.filterIsInstance<f32.Load>().single()
        val f32Store = ops.filterIsInstance<f32.Store>().single()
        val f64Load = ops.filterIsInstance<f64.Load>().single()
        val f64Store = ops.filterIsInstance<f64.Store>().single()
        assertEquals(396, f32Load.value.offset)
        assertEquals(400, f32Store.value.offset)
        assertEquals(500, f64Load.value.offset)
        assertEquals(508, f64Store.value.offset)
    }

    @Test
    fun `byte buffer pattern — load8_u and store8`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 i32 i32)
                (local i32)
                local.get 2
                local.set 3
                (block ${'$'}done
                  (loop ${'$'}loop
                    local.get 3
                    i32.eqz
                    br_if ${'$'}done
                    local.get 0
                    local.get 1
                    i32.load8_u
                    i32.store8
                    local.get 0
                    i32.const 1
                    i32.add
                    local.set 0
                    local.get 1
                    i32.const 1
                    i32.add
                    local.set 1
                    local.get 3
                    i32.const 1
                    i32.sub
                    local.set 3
                    br ${'$'}loop
                  )
                )
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No unparsed: ${unparsed.firstOrNull()?.e?.message}")

        fun flatOps(list: List<OpCode>): List<OpCode> {
            val result = mutableListOf<OpCode>()
            for (op in list) {
                result.add(op)
                if (op is OpCodes.Flow.Block) result.addAll(flatOps(op.value))
            }
            return result
        }

        val all = flatOps(ops)
        assertTrue(all.any { it is i32.Load8_U }, "Missing i32.load8_u")
        assertTrue(all.any { it is i32.Store8 }, "Missing i32.store8")
    }

    // ========================================================================
    // Bulk memory operations (0xFC prefix)
    // ========================================================================

    @Test
    fun `memory init — copy from data segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}d "\01\02\03\04")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 4
                memory.init ${'$'}d
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "memory.init produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `data drop — discard data segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}d "\01\02\03\04")
              (func (export "f")
                data.drop ${'$'}d
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "data.drop produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `memory copy`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f")
                i32.const 0
                i32.const 100
                i32.const 50
                memory.copy
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "memory.copy produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `memory fill`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 256
                memory.fill
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "memory.fill produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `memory init then data drop — typical init pattern`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}d "\01\02\03\04")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 4
                memory.init ${'$'}d
                data.drop ${'$'}d
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "memory.init+data.drop produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `memory fill then copy — bulk ops combined`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 64
                memory.fill
                i32.const 64
                i32.const 0
                i32.const 64
                memory.copy
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "memory.fill+copy produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
    }
}
