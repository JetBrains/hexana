package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCommandExecutorTest {
    @Test
    fun `collects stdout stderr and exit events`() {
        val output = runBlocking {
            executor {
                emit(NativeCommandEvent.Stdout("out"))
                emit(NativeCommandEvent.Stderr("err"))
                emit(NativeCommandEvent.Exit(7))
            }.execute(
                executable = Path.of("/usr/bin/llvm-objdump"),
                arguments = emptyList(),
                timeoutInMilliseconds = 1_000,
            )
        }

        assertEquals(7, output.exitCode)
        assertEquals("out", output.stdout)
        assertEquals("err", output.stderr)
        assertFalse(output.timedOut)
        assertFalse(output.cancelled)
    }

    @Test
    fun `marks output as timed out when event flow does not complete`() {
        val output = runBlocking {
            executor {
                emit(NativeCommandEvent.Stdout("partial"))
                awaitCancellation()
            }.execute(
                executable = Path.of("/usr/bin/llvm-objdump"),
                arguments = emptyList(),
                timeoutInMilliseconds = 25,
            )
        }

        assertEquals(-1, output.exitCode)
        assertEquals("partial", output.stdout)
        assertTrue(output.timedOut)
        assertFalse(output.cancelled)
    }

    @Test
    fun `stops collecting after exit event`() {
        val output = runBlocking {
            executor {
                emit(NativeCommandEvent.Stdout("before"))
                emit(NativeCommandEvent.Exit(0))
                awaitCancellation()
            }.execute(
                executable = Path.of("/usr/bin/llvm-objdump"),
                arguments = emptyList(),
                timeoutInMilliseconds = 1_000,
            )
        }

        assertEquals(0, output.exitCode)
        assertEquals("before", output.stdout)
        assertFalse(output.timedOut)
    }

    private fun executor(events: suspend kotlinx.coroutines.flow.FlowCollector<NativeCommandEvent>.() -> Unit) =
        object : NativeCommandExecutor {
            override fun events(
                executable: Path,
                arguments: List<String>,
            ): Flow<NativeCommandEvent> = flow(events)
        }
}
