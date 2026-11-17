package org.jetbrains.hexana.disassembly.llvmobjdump

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlvmObjdumpProbeTest {
    @Test
    fun `returns version line from stdout`() {
        val result = runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            executor = executor(NativeCommandOutput(0, "LLVM version 18.1.0\nextra", "", false, false)),
        ) }

        assertTrue(result is LlvmObjdumpProbeResult.Success)
        assertEquals("LLVM version 18.1.0", (result as LlvmObjdumpProbeResult.Success).versionLine)
    }

    @Test
    fun `falls back to stderr for version line`() {
        val result = runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            executor = executor(NativeCommandOutput(0, "", "LLVM objdump\n", false, false)),
        ) }

        assertTrue(result is LlvmObjdumpProbeResult.Success)
        assertEquals("LLVM objdump", (result as LlvmObjdumpProbeResult.Success).versionLine)
    }

    @Test
    fun `reports nonzero exit`() {
        val result = runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            executor = executor(NativeCommandOutput(2, "", "bad option", false, false)),
        ) }

        assertTrue(result is LlvmObjdumpProbeResult.Failure)
        assertEquals(
            LlvmObjdumpProbeFailureReason.NonZeroExit,
            (result as LlvmObjdumpProbeResult.Failure).reason,
        )
    }

    @Test
    fun `reports timeout`() {
        val result = runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            executor = executor(NativeCommandOutput(-1, "", "", true, false)),
        ) }

        assertTrue(result is LlvmObjdumpProbeResult.Failure)
        assertEquals(
            LlvmObjdumpProbeFailureReason.TimedOut,
            (result as LlvmObjdumpProbeResult.Failure).reason,
        )
    }

    @Test
    fun `reports cancellation`() {
        val result = runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            executor = executor(NativeCommandOutput(-1, "partial", "", false, true)),
        ) }

        assertTrue(result is LlvmObjdumpProbeResult.Failure)
        assertEquals(
            LlvmObjdumpProbeFailureReason.Cancelled,
            (result as LlvmObjdumpProbeResult.Failure).reason,
        )
        assertEquals("partial", result.stdout)
    }

    @Test
    fun `reports empty version output`() {
        val result = runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            executor = executor(NativeCommandOutput(0, " \n", "\n", false, false)),
        ) }

        assertTrue(result is LlvmObjdumpProbeResult.Failure)
        assertEquals(
            LlvmObjdumpProbeFailureReason.EmptyVersionOutput,
            (result as LlvmObjdumpProbeResult.Failure).reason,
        )
    }

    @Test
    fun `reports execution failure`() {
        val result = runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            executor = failingExecutor(),
        ) }

        assertTrue(result is LlvmObjdumpProbeResult.Failure)
        assertEquals(
            LlvmObjdumpProbeFailureReason.ExecutionFailed,
            (result as LlvmObjdumpProbeResult.Failure).reason,
        )
    }

    @Test
    fun `propagates coroutine cancellation`() {
        val exception = assertThrows(CancellationException::class.java) {
            runBlocking { LlvmObjdumpProbe.probe(
                candidate = candidate(),
                executor = cancellingExecutor(),
            ) }
        }

        assertEquals("stop", exception.message)
    }

    @Test
    fun `passes version command and timeout to executor`() {
        var seenArguments: List<String>? = null
        var seenTimeout: Int? = null

        runBlocking { LlvmObjdumpProbe.probe(
            candidate = candidate(),
            timeoutInMilliseconds = 123,
            executor = object : NativeCommandExecutor {
                override fun events(
                    executable: Path,
                    arguments: List<String>,
                ): Flow<NativeCommandEvent> = emptyFlow()

                override suspend fun execute(
                    executable: Path,
                    arguments: List<String>,
                    timeoutInMilliseconds: Int,
                ): NativeCommandOutput {
                    seenArguments = arguments
                    seenTimeout = timeoutInMilliseconds
                    return NativeCommandOutput(0, "LLVM", "", false, false)
                }
            },
        ) }

        assertEquals(listOf("--version"), seenArguments)
        assertEquals(123, seenTimeout)
    }

    private fun candidate() = LlvmObjdumpCandidate(
        path = Path.of("/usr/bin/llvm-objdump"),
        source = LlvmObjdumpSource.PathEnvironment,
    )

    private fun executor(output: NativeCommandOutput) = object : NativeCommandExecutor {
        override fun events(
            executable: Path,
            arguments: List<String>,
        ): Flow<NativeCommandEvent> = emptyFlow()

        override suspend fun execute(
            executable: Path,
            arguments: List<String>,
            timeoutInMilliseconds: Int,
        ): NativeCommandOutput = output
    }

    private fun failingExecutor() = object : NativeCommandExecutor {
        override fun events(
            executable: Path,
            arguments: List<String>,
        ): Flow<NativeCommandEvent> = emptyFlow()

        override suspend fun execute(
            executable: Path,
            arguments: List<String>,
            timeoutInMilliseconds: Int,
        ): NativeCommandOutput = error("boom")
    }

    private fun cancellingExecutor() = object : NativeCommandExecutor {
        override fun events(
            executable: Path,
            arguments: List<String>,
        ): Flow<NativeCommandEvent> = emptyFlow()

        override suspend fun execute(
            executable: Path,
            arguments: List<String>,
            timeoutInMilliseconds: Int,
        ): NativeCommandOutput = throw CancellationException("stop")
    }
}
