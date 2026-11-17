package org.jetbrains.hexana.disassembly.llvmobjdump

import kotlinx.coroutines.CancellationException

sealed interface LlvmObjdumpRunResult {
    data class Success(
        val candidate: LlvmObjdumpCandidate,
        val command: LlvmObjdumpCommand,
        val stdout: String,
        val stderr: String,
    ) : LlvmObjdumpRunResult

    data class Failure(
        val reason: LlvmObjdumpRunFailureReason,
        val message: String,
        val candidate: LlvmObjdumpCandidate? = null,
        val command: LlvmObjdumpCommand? = null,
        val stdout: String = "",
        val stderr: String = "",
    ) : LlvmObjdumpRunResult
}

enum class LlvmObjdumpRunFailureReason {
    NotFound,
    ProbeFailed,
    TimedOut,
    Cancelled,
    NonZeroExit,
    ExecutionFailed,
}

object LlvmObjdumpRunner {
    const val DEFAULT_COMMAND_TIMEOUT_MS: Int = 30_000

    suspend fun discoverProbeAndRun(
        command: LlvmObjdumpCommand,
        extraDirectories: List<java.nio.file.Path> = emptyList(),
        discovery: (List<java.nio.file.Path>) -> List<LlvmObjdumpCandidate> = { directories ->
            LlvmObjdumpDiscovery.executableCandidates(extraDirectories = directories)
        },
        probe: suspend (LlvmObjdumpCandidate) -> LlvmObjdumpProbeResult = { candidate ->
            LlvmObjdumpProbe.probe(candidate)
        },
        executor: NativeCommandExecutor = ProcessNativeCommandExecutor,
        timeoutInMilliseconds: Int = DEFAULT_COMMAND_TIMEOUT_MS,
    ): LlvmObjdumpRunResult {
        val candidates = discovery(extraDirectories)
        if (candidates.isEmpty()) {
            return LlvmObjdumpRunResult.Failure(
                reason = LlvmObjdumpRunFailureReason.NotFound,
                message = "llvm-objdump executable was not found",
                command = command,
            )
        }

        var lastProbeFailure: LlvmObjdumpProbeResult.Failure? = null
        for (candidate in candidates) {
            val probeResult = probe(candidate)
            if (probeResult is LlvmObjdumpProbeResult.Success) {
                return run(candidate, command, executor, timeoutInMilliseconds)
            }

            val failure = probeResult as LlvmObjdumpProbeResult.Failure
            lastProbeFailure = failure
            if (candidate.source == LlvmObjdumpSource.ExplicitPath) {
                break
            }
        }

        val failure = lastProbeFailure
            ?: return LlvmObjdumpRunResult.Failure(
                reason = LlvmObjdumpRunFailureReason.NotFound,
                message = "llvm-objdump executable was not found",
                command = command,
            )
        return LlvmObjdumpRunResult.Failure(
            reason = LlvmObjdumpRunFailureReason.ProbeFailed,
            message = failure.message,
            candidate = failure.candidate,
            command = command,
            stdout = failure.stdout,
            stderr = failure.stderr,
        )
    }

    suspend fun run(
        candidate: LlvmObjdumpCandidate,
        command: LlvmObjdumpCommand,
        executor: NativeCommandExecutor = ProcessNativeCommandExecutor,
        timeoutInMilliseconds: Int = DEFAULT_COMMAND_TIMEOUT_MS,
    ): LlvmObjdumpRunResult {
        val output = try {
            executor.execute(
                executable = candidate.path,
                arguments = command.arguments,
                timeoutInMilliseconds = timeoutInMilliseconds,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return LlvmObjdumpRunResult.Failure(
                reason = LlvmObjdumpRunFailureReason.ExecutionFailed,
                message = e.message ?: "Failed to execute llvm-objdump",
                candidate = candidate,
                command = command,
            )
        }

        if (output.timedOut) {
            return LlvmObjdumpRunResult.Failure(
                reason = LlvmObjdumpRunFailureReason.TimedOut,
                message = "llvm-objdump ${command.mode} timed out after ${timeoutInMilliseconds}ms",
                candidate = candidate,
                command = command,
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
        if (output.cancelled) {
            return LlvmObjdumpRunResult.Failure(
                reason = LlvmObjdumpRunFailureReason.Cancelled,
                message = "llvm-objdump ${command.mode} was cancelled",
                candidate = candidate,
                command = command,
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
        if (output.exitCode != 0) {
            return LlvmObjdumpRunResult.Failure(
                reason = LlvmObjdumpRunFailureReason.NonZeroExit,
                message = "llvm-objdump ${command.mode} exited with code ${output.exitCode}",
                candidate = candidate,
                command = command,
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }

        return LlvmObjdumpRunResult.Success(
            candidate = candidate,
            command = command,
            stdout = output.stdout,
            stderr = output.stderr,
        )
    }
}
