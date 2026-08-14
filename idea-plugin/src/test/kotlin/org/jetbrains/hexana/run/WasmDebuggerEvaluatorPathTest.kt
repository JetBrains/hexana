package org.jetbrains.hexana.run

import junit.framework.TestCase

/**
 * Tests for [WasmDebuggerEvaluator.isFrameVariablePath], the gate that
 * decides whether an expression is safe to evaluate via `frame variable`
 * in gdbstub guest sessions (where LLDB `expression` aborts the process).
 */
class WasmDebuggerEvaluatorPathTest : TestCase() {

    fun testAcceptsPlainIdentifiers() {
        assertTrue(WasmDebuggerEvaluator.isFrameVariablePath("a"))
        assertTrue(WasmDebuggerEvaluator.isFrameVariablePath("_count"))
        assertTrue(WasmDebuggerEvaluator.isFrameVariablePath("arr2"))
    }

    fun testAcceptsMemberAndIndexPaths() {
        assertTrue(WasmDebuggerEvaluator.isFrameVariablePath("p.x"))
        assertTrue(WasmDebuggerEvaluator.isFrameVariablePath("p->x"))
        assertTrue(WasmDebuggerEvaluator.isFrameVariablePath("arr[0]"))
        assertTrue(WasmDebuggerEvaluator.isFrameVariablePath("s.inner->items[12]"))
    }

    fun testRejectsArithmeticAndCalls() {
        assertFalse(WasmDebuggerEvaluator.isFrameVariablePath("a + b"))
        assertFalse(WasmDebuggerEvaluator.isFrameVariablePath("f()"))
        assertFalse(WasmDebuggerEvaluator.isFrameVariablePath("__vmctx->set()"))
        assertFalse(WasmDebuggerEvaluator.isFrameVariablePath("*p"))
        assertFalse(WasmDebuggerEvaluator.isFrameVariablePath("a; run"))
        assertFalse(WasmDebuggerEvaluator.isFrameVariablePath(""))
        assertFalse(WasmDebuggerEvaluator.isFrameVariablePath("arr[i]"))
    }
}
