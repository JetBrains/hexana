package org.jetbrains.hexana.wasm.analysis.diff.match

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FunctionNormalizerTest {

    private fun hash(
        ops: List<OpCode>,
        params: Int = 0,
        results: Int = 0,
        seed: Long = 0L,
        ctx: NormalizerContext = NormalizerContext.EMPTY,
    ): Long = FunctionNormalizer().normalize(ops, params, results, seed, ctx).hash

    private fun localGet(i: Int) = OpCodes.Local.Get(i.toUInt())
    private fun localSet(i: Int) = OpCodes.Local.Set(i.toUInt())
    private fun call(i: Int) = OpCodes.Flow.Call(i.toUInt())
    private fun i32(v: Int) = I32.Const(v)

    private val callerCtx = object : NormalizerContext {
        override fun calleeToken(funcIdx: Int): Long = 1000L + funcIdx
        override fun typeArity(typeIdx: Int): Int = 0
        override fun globalToken(globalIdx: Int): Long = 0L
    }

    @Test
    fun `declared-local renumbering is first-use invariant`() {
        // Same first-use pattern, different declared local indices -> equal hash.
        val a = listOf(localGet(5), localSet(5), localGet(6))
        val b = listOf(localGet(2), localSet(2), localGet(9))
        assertEquals(hash(a), hash(b))
    }

    @Test
    fun `different local-use pattern changes the hash`() {
        val a = listOf(localGet(5), localSet(5), localGet(6)) // -> 0,0,1
        val c = listOf(localGet(5), localSet(5), localGet(5)) // -> 0,0,0
        assertNotEquals(hash(a), hash(c))
    }

    @Test
    fun `params keep their index and are not first-use renumbered`() {
        // Use order is reversed: get 1 then get 0.
        // params=2 -> both kept -> tokens 1,0. params=0 -> first-use -> 1->0, 0->1 -> tokens 0,1. Differ.
        val body = listOf(localGet(1), localGet(0))
        assertNotEquals(hash(body, params = 2), hash(body, params = 0))
    }

    @Test
    fun `constant value is part of the hash`() {
        assertNotEquals(hash(listOf(i32(1))), hash(listOf(i32(2))))
    }

    @Test
    fun `signature seed distinguishes identical bodies`() {
        val body = listOf(i32(1))
        assertNotEquals(hash(body, seed = 10L), hash(body, seed = 20L))
    }

    @Test
    fun `call target discrimination requires a context`() {
        val callA = listOf(call(0))
        val callB = listOf(call(1))
        // EMPTY context: all calls anonymous -> equal.
        assertEquals(hash(callA), hash(callB))
        // Real context: different targets -> different hash.
        assertNotEquals(hash(callA, ctx = callerCtx), hash(callB, ctx = callerCtx))
        // Same target -> equal.
        assertEquals(hash(listOf(call(3)), ctx = callerCtx), hash(listOf(call(3)), ctx = callerCtx))
    }

    @Test
    fun `nesting in a block changes the hash and recurses into the body`() {
        val flat = listOf(call(0))
        val nested = listOf(OpCodes.Flow.Block(null, listOf(call(0))))
        assertNotEquals(hash(flat, ctx = callerCtx), hash(nested, ctx = callerCtx))

        // The call inside the block still contributes (empty block differs from call-in-block).
        val emptyBlock = listOf(OpCodes.Flow.Block(null, emptyList()))
        val callInBlock = listOf(OpCodes.Flow.Block(null, listOf(call(0))))
        assertNotEquals(hash(emptyBlock, ctx = callerCtx), hash(callInBlock, ctx = callerCtx))
    }
}
