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

class LlvmObjdumpRunnerTest {
    @Test
    fun `runs command with candidate and returns stdout`() {
        var seenArguments: List<String>? = null
        val result = runBlocking { LlvmObjdumpRunner.run(
            candidate = candidate(),
            command = LlvmObjdumpCommands.symbols(Path.of("/tmp/app")),
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
                    return NativeCommandOutput(0, "symbols", "", false, false)
                }
            },
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Success)
        assertEquals(listOf("--syms", "/tmp/app"), seenArguments)
        assertEquals("symbols", (result as LlvmObjdumpRunResult.Success).stdout)
    }

    @Test
    fun `reports command timeout`() {
        val result = runBlocking { LlvmObjdumpRunner.run(
            candidate = candidate(),
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            executor = executor(NativeCommandOutput(-1, "", "partial", true, false)),
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Failure)
        assertEquals(LlvmObjdumpRunFailureReason.TimedOut, (result as LlvmObjdumpRunResult.Failure).reason)
        assertEquals("partial", result.stderr)
    }

    @Test
    fun `reports command cancellation`() {
        val result = runBlocking { LlvmObjdumpRunner.run(
            candidate = candidate(),
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            executor = executor(NativeCommandOutput(-1, "partial", "", false, true)),
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Failure)
        assertEquals(LlvmObjdumpRunFailureReason.Cancelled, (result as LlvmObjdumpRunResult.Failure).reason)
        assertEquals("partial", result.stdout)
    }

    @Test
    fun `reports command nonzero exit`() {
        val result = runBlocking { LlvmObjdumpRunner.run(
            candidate = candidate(),
            command = LlvmObjdumpCommands.relocations(Path.of("/tmp/app")),
            executor = executor(NativeCommandOutput(1, "", "not an object", false, false)),
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Failure)
        assertEquals(LlvmObjdumpRunFailureReason.NonZeroExit, (result as LlvmObjdumpRunResult.Failure).reason)
        assertEquals("not an object", result.stderr)
    }

    @Test
    fun `reports command execution failure`() {
        val result = runBlocking { LlvmObjdumpRunner.run(
            candidate = candidate(),
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            executor = failingExecutor(),
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Failure)
        assertEquals(LlvmObjdumpRunFailureReason.ExecutionFailed, (result as LlvmObjdumpRunResult.Failure).reason)
    }

    @Test
    fun `propagates coroutine cancellation`() {
        val exception = assertThrows(CancellationException::class.java) {
            runBlocking { LlvmObjdumpRunner.run(
                candidate = candidate(),
                command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
                executor = cancellingExecutor(),
            ) }
        }

        assertEquals("stop", exception.message)
    }

    @Test
    fun `discover probe and run reports not found`() {
        val result = runBlocking { LlvmObjdumpRunner.discoverProbeAndRun(
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            discovery = { emptyList() },
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Failure)
        assertEquals(LlvmObjdumpRunFailureReason.NotFound, (result as LlvmObjdumpRunResult.Failure).reason)
    }

    @Test
    fun `discover probe and run reports failed probe when all candidates fail`() {
        val result = runBlocking { LlvmObjdumpRunner.discoverProbeAndRun(
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            discovery = { listOf(candidate()) },
            probe = {
                LlvmObjdumpProbeResult.Failure(
                    candidate = it,
                    reason = LlvmObjdumpProbeFailureReason.NonZeroExit,
                    message = "probe failed",
                    stderr = "bad binary",
                )
            },
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Failure)
        assertEquals(LlvmObjdumpRunFailureReason.ProbeFailed, (result as LlvmObjdumpRunResult.Failure).reason)
        assertEquals("bad binary", result.stderr)
    }

    @Test
    fun `discover probe and run tries next candidate after failed probe`() {
        val first = candidate(Path.of("/bad/llvm-objdump"))
        val second = candidate(Path.of("/good/llvm-objdump"))
        val probed = mutableListOf<Path>()

        val result = runBlocking { LlvmObjdumpRunner.discoverProbeAndRun(
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            discovery = { listOf(first, second) },
            probe = {
                probed.add(it.path)
                if (it == second) {
                    LlvmObjdumpProbeResult.Success(
                        candidate = it,
                        versionLine = "LLVM",
                        stdout = "LLVM",
                        stderr = "",
                    )
                } else {
                    LlvmObjdumpProbeResult.Failure(
                        candidate = it,
                        reason = LlvmObjdumpProbeFailureReason.NonZeroExit,
                        message = "bad candidate",
                        stderr = "bad binary",
                    )
                }
            },
            executor = executor(NativeCommandOutput(0, "headers", "", false, false)),
        ) }

        assertEquals(listOf(first.path, second.path), probed)
        assertTrue(result is LlvmObjdumpRunResult.Success)
        assertEquals(second, (result as LlvmObjdumpRunResult.Success).candidate)
    }

    @Test
    fun `discover probe and run fails fast for explicit candidate`() {
        val explicit = candidate(Path.of("/configured/llvm-objdump"), LlvmObjdumpSource.ExplicitPath)
        val fallback = candidate(Path.of("/fallback/llvm-objdump"))
        val probed = mutableListOf<Path>()

        val result = runBlocking { LlvmObjdumpRunner.discoverProbeAndRun(
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            discovery = { listOf(explicit, fallback) },
            probe = {
                probed.add(it.path)
                LlvmObjdumpProbeResult.Failure(
                    candidate = it,
                    reason = LlvmObjdumpProbeFailureReason.NonZeroExit,
                    message = "configured candidate failed",
                    stderr = "bad binary",
                )
            },
        ) }

        assertEquals(listOf(explicit.path), probed)
        assertTrue(result is LlvmObjdumpRunResult.Failure)
        assertEquals(explicit, (result as LlvmObjdumpRunResult.Failure).candidate)
    }

    @Test
    fun `discover probe and run executes after successful probe`() {
        val result = runBlocking { LlvmObjdumpRunner.discoverProbeAndRun(
            command = LlvmObjdumpCommands.headers(Path.of("/tmp/app")),
            discovery = { listOf(candidate()) },
            probe = {
                LlvmObjdumpProbeResult.Success(
                    candidate = it,
                    versionLine = "LLVM",
                    stdout = "LLVM",
                    stderr = "",
                )
            },
            executor = executor(NativeCommandOutput(0, "headers", "", false, false)),
        ) }

        assertTrue(result is LlvmObjdumpRunResult.Success)
        assertEquals("headers", (result as LlvmObjdumpRunResult.Success).stdout)
    }

    private fun candidate(
        path: Path = Path.of("/usr/bin/llvm-objdump"),
        source: LlvmObjdumpSource = LlvmObjdumpSource.PathEnvironment,
    ) = LlvmObjdumpCandidate(path = path, source = source)

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
