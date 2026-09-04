package org.jetbrains.hexana.wasm.analysis

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasmProposalDetectorTest {

    private suspend fun detectProposals(wat: String): Set<WasmProposal> {
        val buffer = watStringToBuffer(wat)
        val result = WasmParser().parse(buffer)
        val module = (result as WasmParseSuccess).wasmModule as WasmModule
        return WasmProposalDetector.detect(module, buffer)
    }

    // ========================================================================
    // Minimal MVP module — should detect no proposals
    // ========================================================================

    @Test
    fun `empty module detects no proposals`() = runTest {
        val proposals = detectProposals("(module)")
        assertTrue(proposals.isEmpty(), "Empty module should not trigger any proposals, got: $proposals")
    }

    @Test
    fun `mvp-only module detects no proposals`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (export "add") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.add
              )
            )
        """.trimIndent())
        assertTrue(proposals.isEmpty(), "MVP-only module should not trigger any proposals, got: $proposals")
    }

    // ========================================================================
    // Sign-extension operators
    // ========================================================================

    @Test
    fun `sign-extension — i32 extend8_s`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param i32) (result i32)
                local.get 0
                i32.extend8_s
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.SignExtensionOps)
    }

    @Test
    fun `sign-extension — i64 extend32_s`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param i64) (result i64)
                local.get 0
                i64.extend32_s
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.SignExtensionOps)
    }

    // ========================================================================
    // Non-trapping float-to-int conversions
    // ========================================================================

    @Test
    fun `non-trapping float-to-int — i32 trunc_sat_f32_s`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param f32) (result i32)
                local.get 0
                i32.trunc_sat_f32_s
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.NonTrappingFloatToInt)
    }

    @Test
    fun `non-trapping float-to-int — i64 trunc_sat_f64_u`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param f64) (result i64)
                local.get 0
                i64.trunc_sat_f64_u
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.NonTrappingFloatToInt)
    }

    // ========================================================================
    // Multi-value
    // ========================================================================

    @Test
    fun `multi-value — function returning two values`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (result i32 i32)
                i32.const 1
                i32.const 2
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.MultiValue)
    }

    @Test
    fun `single return value does not trigger multi-value`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (result i32) i32.const 42)
            )
        """.trimIndent())
        assertFalse(WasmProposal.MultiValue in proposals)
    }

    // ========================================================================
    // Reference types
    // ========================================================================

    @Test
    fun `reference types — ref null`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (result funcref)
                ref.null func
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ReferenceTypes)
    }

    @Test
    fun `reference types — ref is_null`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param funcref) (result i32)
                local.get 0
                ref.is_null
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ReferenceTypes)
    }

    @Test
    fun `reference types — table get and set`() = runTest {
        val proposals = detectProposals("""
            (module
              (table 1 funcref)
              (func (param i32) (result funcref)
                local.get 0
                table.get 0
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ReferenceTypes)
    }

    @Test
    fun `reference types — table grow`() = runTest {
        val proposals = detectProposals("""
            (module
              (table 0 funcref)
              (func (result i32)
                ref.null func
                i32.const 10
                table.grow 0
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ReferenceTypes)
    }

    // ========================================================================
    // Bulk memory operations
    // ========================================================================

    @Test
    fun `bulk memory — memory copy`() = runTest {
        val proposals = detectProposals("""
            (module
              (memory 1)
              (func
                i32.const 0
                i32.const 100
                i32.const 50
                memory.copy
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.BulkMemoryOperations)
    }

    @Test
    fun `bulk memory — memory fill`() = runTest {
        val proposals = detectProposals("""
            (module
              (memory 1)
              (func
                i32.const 0
                i32.const 0
                i32.const 100
                memory.fill
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.BulkMemoryOperations)
    }

    @Test
    fun `bulk memory — data count section triggers detection`() = runTest {
        val proposals = detectProposals("""
            (module
              (memory 1)
              (data "hello")
              (func
                i32.const 0
                i32.const 0
                i32.const 5
                memory.init 0
                data.drop 0
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.BulkMemoryOperations)
    }

    @Test
    fun `bulk memory — table init`() = runTest {
        val proposals = detectProposals("""
            (module
              (table 1 funcref)
              (elem funcref (ref.null func))
              (func
                i32.const 0
                i32.const 0
                i32.const 1
                table.init 0
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.BulkMemoryOperations)
    }

    // ========================================================================
    // SIMD
    // ========================================================================

    @Test
    fun `simd — v128 const`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (result v128)
                v128.const i32x4 0 0 0 0
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.SIMD)
    }

    // ========================================================================
    // Mutable globals (import/export)
    // ========================================================================

    @Test
    fun `mutable globals — mutable global import`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "g" (global (mut i32)))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.MutableGlobals)
    }

    @Test
    fun `mutable globals — mutable global export`() = runTest {
        val proposals = detectProposals("""
            (module
              (global (export "g") (mut i32) (i32.const 0))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.MutableGlobals)
    }

    @Test
    fun `immutable global import does not trigger mutable globals`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "g" (global i32))
            )
        """.trimIndent())
        assertFalse(WasmProposal.MutableGlobals in proposals)
    }

    @Test
    fun `immutable global export does not trigger mutable globals`() = runTest {
        val proposals = detectProposals("""
            (module
              (global (export "g") i32 (i32.const 42))
            )
        """.trimIndent())
        assertFalse(WasmProposal.MutableGlobals in proposals)
    }

    // ========================================================================
    // Tail call
    // ========================================================================

    @Test
    fun `tail call — return_call`() = runTest {
        val proposals = detectProposals("""
            (module
              (func ${'$'}f (result i32) i32.const 42)
              (func (result i32) return_call ${'$'}f)
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.TailCall)
    }

    @Test
    fun `tail call — return_call_indirect`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}t (func (result i32)))
              (table 1 funcref)
              (func (result i32)
                i32.const 0
                return_call_indirect (type ${'$'}t)
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.TailCall)
    }

    // ========================================================================
    // Exception handling
    // ========================================================================

    @Test
    fun `exception handling — throw`() = runTest {
        val proposals = detectProposals("""
            (module
              (tag ${'$'}e (param i32))
              (func
                i32.const 42
                throw ${'$'}e
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ExceptionHandling)
    }

    @Test
    fun `exception handling — tag section alone triggers detection`() = runTest {
        val proposals = detectProposals("""
            (module
              (tag (param i32))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ExceptionHandling)
    }

    @Test
    fun `exception handling — tag import`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "e" (tag (param i32)))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ExceptionHandling)
    }

    @Test
    fun `exception handling — tag export`() = runTest {
        val proposals = detectProposals("""
            (module
              (tag (export "e") (param i32))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ExceptionHandling)
    }

    // ========================================================================
    // Typed function references
    // ========================================================================

    @Test
    fun `typed function references — call_ref`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}t (func (result i32)))
              (func ${'$'}f (result i32) i32.const 42)
              (func (result i32)
                ref.func ${'$'}f
                call_ref ${'$'}t
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.TypedFunctionReferences)
    }

    // ========================================================================
    // GC (garbage collection)
    // ========================================================================

    @Test
    fun `gc — struct type detected`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}point (struct (field i32) (field i32)))
              (func (result (ref ${'$'}point))
                i32.const 1
                i32.const 2
                struct.new ${'$'}point
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.GarbageCollection)
    }

    @Test
    fun `gc — array type detected`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}arr (array i32))
              (func (result (ref ${'$'}arr))
                i32.const 0
                i32.const 10
                array.new ${'$'}arr
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.GarbageCollection)
    }

    // ========================================================================
    // Multiple proposals in one module
    // ========================================================================

    @Test
    fun `combined — sign-ext and non-trapping in same module`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param i32 f32) (result i32 i32)
                local.get 0
                i32.extend8_s
                local.get 1
                i32.trunc_sat_f32_s
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.SignExtensionOps)
        assertContains(proposals, WasmProposal.NonTrappingFloatToInt)
        assertContains(proposals, WasmProposal.MultiValue)
    }

    // ========================================================================
    // Nested blocks
    // ========================================================================

    @Test
    fun `opcodes inside nested blocks are detected`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param i32) (result i32)
                block (result i32)
                  local.get 0
                  i32.extend8_s
                end
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.SignExtensionOps)
    }

    @Test
    fun `opcodes inside loop are detected`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param f64) (result i64)
                loop (result i64)
                  local.get 0
                  i64.trunc_sat_f64_s
                end
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.NonTrappingFloatToInt)
    }

    @Test
    fun `opcodes inside if-else are detected`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param i32) (result i32)
                local.get 0
                if (result i32)
                  local.get 0
                  i32.extend16_s
                else
                  i32.const 0
                end
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.SignExtensionOps)
    }

    // ========================================================================
    // Threads / Atomics
    // ========================================================================

    @Test
    fun `threads — shared memory triggers detection`() = runTest {
        val proposals = detectProposals("""
            (module
              (memory 1 1 shared)
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.Threads)
    }

    @Test
    fun `threads — atomic instruction triggers detection`() = runTest {
        val proposals = detectProposals("""
            (module
              (memory 1 1 shared)
              (func (param i32) (result i32)
                local.get 0
                i32.atomic.load
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.Threads)
    }

    @Test
    fun `threads — shared imported memory triggers detection`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "mem" (memory 1 1 shared))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.Threads)
    }

    // ========================================================================
    // Multiple memories
    // ========================================================================

    @Test
    fun `multiple memories — two imported memories without memory section`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "m1" (memory 1))
              (import "env" "m2" (memory 1))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.MultipleMemories)
    }

    @Test
    fun `multiple memories — one defined and one imported`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "m" (memory 1))
              (memory 1)
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.MultipleMemories)
    }

    @Test
    fun `single imported memory does not trigger multiple memories`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "m" (memory 1))
            )
        """.trimIndent())
        assertFalse(WasmProposal.MultipleMemories in proposals)
    }

    // ========================================================================
    // Extended constant expressions
    // ========================================================================

    @Test
    fun `extended const — arithmetic in a global initializer`() = runTest {
        val proposals = detectProposals("""
            (module
              (global i32 (i32.add (i32.const 1) (i32.const 2)))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ExtendedConst)
    }

    @Test
    fun `extended const — arithmetic in a data segment offset`() = runTest {
        val proposals = detectProposals("""
            (module
              (memory 1)
              (data (offset (i32.add (i32.const 1) (i32.const 1))) "x")
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ExtendedConst)
    }

    @Test
    fun `extended const — arithmetic in an element segment offset`() = runTest {
        val proposals = detectProposals("""
            (module
              (table 4 funcref)
              (elem (offset (i32.add (i32.const 1) (i32.const 1))) func)
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.ExtendedConst)
    }

    @Test
    fun `mvp global initializer does not trigger extended const`() = runTest {
        val proposals = detectProposals("(module (global i32 (i32.const 1)))")
        assertFalse(WasmProposal.ExtendedConst in proposals)
    }

    /**
     * `global.get` and `ref.func` are MVP/reference-types constant expressions, not
     * extended ones — the distinction the detector's whitelist exists to make.
     */
    @Test
    fun `global-get and ref-func initializers do not trigger extended const`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "base" (global i32))
              (global i32 (global.get 0))
              (func ${'$'}f)
              (global funcref (ref.func ${'$'}f))
            )
        """.trimIndent())
        assertFalse(WasmProposal.ExtendedConst in proposals)
    }

    // ========================================================================
    // Custom page sizes
    // ========================================================================

    @Test
    fun `custom page sizes — explicit pagesize on a memory`() = runTest {
        val proposals = detectProposals("(module (memory 1 1 (pagesize 1)))")
        assertContains(proposals, WasmProposal.CustomPageSizes)
    }

    @Test
    fun `custom page sizes — explicit pagesize on an imported memory`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "m" (memory 1 1 (pagesize 1)))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.CustomPageSizes)
    }

    @Test
    fun `default page size does not trigger custom page sizes`() = runTest {
        val proposals = detectProposals("(module (memory 1 1))")
        assertFalse(WasmProposal.CustomPageSizes in proposals)
    }

    // ========================================================================
    // JS string builtins
    // ========================================================================

    @Test
    fun `js string builtins — import from the wasm colon js-string module`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "wasm:js-string" "length" (func (param externref) (result i32)))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.JsStringBuiltins)
    }

    @Test
    fun `an ordinary import does not trigger js string builtins`() = runTest {
        val proposals = detectProposals("""
            (module
              (import "env" "length" (func (param externref) (result i32)))
            )
        """.trimIndent())
        assertFalse(WasmProposal.JsStringBuiltins in proposals)
    }

    // ========================================================================
    // Branch hinting
    // ========================================================================

    @Test
    fun `branch hinting — the metadata code branch_hint custom section`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param i32) (result i32)
                local.get 0
                (@metadata.code.branch_hint "\00") if (result i32) (i32.const 1) else (i32.const 2) end
              )
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.BranchHinting)
    }

    @Test
    fun `an if without a hint does not trigger branch hinting`() = runTest {
        val proposals = detectProposals("""
            (module
              (func (param i32) (result i32)
                local.get 0
                if (result i32) (i32.const 1) else (i32.const 2) end
              )
            )
        """.trimIndent())
        assertFalse(WasmProposal.BranchHinting in proposals)
    }

    // ========================================================================
    // Extended name section
    //
    // Core-spec name subsections are module (0), function (1) and locals (2);
    // everything above that is the extended-name-section proposal.
    // ========================================================================

    @Test
    fun `extended name section — named block label emits the labels subsection`() = runTest {
        val proposals = detectProposals("(module (func ${'$'}f (block ${'$'}b (br ${'$'}b))))")
        assertContains(proposals, WasmProposal.ExtendedNameSection)
    }

    @Test
    fun `extended name section — named global emits the globals subsection`() = runTest {
        val proposals = detectProposals("(module (global ${'$'}g i32 (i32.const 1)))")
        assertContains(proposals, WasmProposal.ExtendedNameSection)
    }

    @Test
    fun `extended name section — named tag emits the tags subsection`() = runTest {
        val proposals = detectProposals("(module (tag ${'$'}t))")
        assertContains(proposals, WasmProposal.ExtendedNameSection)
    }

    @Test
    fun `function and local names alone do not trigger the extended name section`() = runTest {
        val proposals = detectProposals("(module (func ${'$'}f (local ${'$'}x i32)))")
        assertFalse(WasmProposal.ExtendedNameSection in proposals)
    }

    @Test
    fun `a module without a name section does not trigger the extended name section`() = runTest {
        val proposals = detectProposals("(module (func))")
        assertFalse(WasmProposal.ExtendedNameSection in proposals)
    }

    // ========================================================================
    // Custom descriptors
    // ========================================================================

    @Test
    fun `custom descriptors — exact heap type in a function signature`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}s (struct))
              (func (param (ref null (exact ${'$'}s))))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.CustomDescriptors)
    }

    @Test
    fun `custom descriptors — exact heap type in a struct field`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}s (struct))
              (type ${'$'}holder (struct (field (ref null (exact ${'$'}s)))))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.CustomDescriptors)
    }

    @Test
    fun `custom descriptors — exact heap type in a code local`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}s (struct))
              (func (local (ref null (exact ${'$'}s))))
            )
        """.trimIndent())
        assertContains(proposals, WasmProposal.CustomDescriptors)
    }

    @Test
    fun `an inexact reference does not trigger custom descriptors`() = runTest {
        val proposals = detectProposals("""
            (module
              (type ${'$'}s (struct))
              (func (param (ref null ${'$'}s)))
            )
        """.trimIndent())
        assertFalse(WasmProposal.CustomDescriptors in proposals)
    }
}
