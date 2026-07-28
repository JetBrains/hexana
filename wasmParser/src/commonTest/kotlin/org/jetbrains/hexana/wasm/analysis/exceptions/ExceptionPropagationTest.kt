package org.jetbrains.hexana.wasm.analysis.exceptions

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExceptionPropagationTest {

    private suspend fun propagation(resource: String): ExceptionPropagation {
        val buf = resourceAsByteBuffer(resource)
        val result = WasmParser().parse(buf)
        assertIs(result)
        val module = (result as WasmParseSuccess).wasmModule as WasmModule
        return ExceptionPropagationBuilder.build(buf, module)
    }

    private fun assertIs(result: Any?) = assertTrue(result is WasmParseSuccess, "parse should succeed")

    // Fixture (exceptions-prop.wat): func 0 $callee (throws $t0, escapes),
    // 1 $contained (exported, catches $t0), 2 $leaky (exported, propagates $t0).

    @Test
    fun `callee escapes its own tag`() = runTest {
        val p = propagation("snippets/exceptions-prop.wasm")
        assertEquals(setOf(0), p.thrownBy(0).tags)
    }

    @Test
    fun `a try_table around the call contains the exception`() = runTest {
        val p = propagation("snippets/exceptions-prop.wasm")
        // $contained catches $t0 around its call to $callee -> nothing escapes.
        assertTrue(p.thrownBy(1).isEmpty, "contained must not escape any exception")
    }

    @Test
    fun `an unguarded call propagates the callee's tag to the caller`() = runTest {
        val p = propagation("snippets/exceptions-prop.wasm")
        // $leaky calls $callee with no handler -> $t0 propagates.
        assertEquals(setOf(0), p.thrownBy(2).tags)
    }

    @Test
    fun `panic surface lists only the leaky exported entry`() = runTest {
        val p = propagation("snippets/exceptions-prop.wasm")
        val surface = p.uncaughtAtEntry()
        assertEquals(listOf(2), surface.map { it.funcIdx }, "only \$leaky (func 2) escapes at an entry")
        val leaky = surface.single()
        assertTrue(leaky.isExportedEntry)
        assertEquals(setOf(0), leaky.thrown.tags)
        assertFalse(leaky.thrown.dynamic)
    }
}
