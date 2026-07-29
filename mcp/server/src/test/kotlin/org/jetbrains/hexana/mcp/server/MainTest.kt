package org.jetbrains.hexana.mcp.server

import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun fatalLinkageErrorIsEmittedAndRethrownByIdentity() {
        val original = SyntheticLinkageError()
        var emitted = false

        val thrown = assertFailsWith<SyntheticLinkageError> {
            rethrowFatalRuntimeFailure(original) {
                emitted = true
            }
        }

        assertTrue(emitted)
        assertSame(original, thrown)
    }

    @Test
    fun diagnosticFailureCannotReplaceFatalVirtualMachineError() {
        val original = SyntheticVirtualMachineError()

        val thrown = assertFailsWith<SyntheticVirtualMachineError> {
            rethrowFatalRuntimeFailure(original) {
                throw AssertionError("diagnostic emission failed")
            }
        }

        assertSame(original, thrown)
    }

    private class SyntheticLinkageError : LinkageError()

    private class SyntheticVirtualMachineError : VirtualMachineError()
}
