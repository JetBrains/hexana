package org.jetbrains.hexana.disassembly.llvmobjdump

import kotlinx.coroutines.CancellationException

sealed interface LlvmObjdumpProbeResult {
    data class Success(
        val candidate: LlvmObjdumpCandidate,
        val versionLine: String,
        val stdout: String,
        val stderr: String,
    ) : LlvmObjdumpProbeResult

    data class Failure(
        val candidate: LlvmObjdumpCandidate,
        val reason: LlvmObjdumpProbeFailureReason,
        val message: String,
        val stdout: String = "",
        val stderr: String = "",
    ) : LlvmObjdumpProbeResult
}

enum class LlvmObjdumpProbeFailureReason {
    TimedOut,
    Cancelled,
    NonZeroExit,
    EmptyVersionOutput,
    ExecutionFailed,
}

object LlvmObjdumpProbe {
    const val DEFAULT_TIMEOUT_MS: Int = 5_000

    suspend fun probe(
        candidate: LlvmObjdumpCandidate,
        timeoutInMilliseconds: Int = DEFAULT_TIMEOUT_MS,
        executor: NativeCommandExecutor = ProcessNativeCommandExecutor,
    ): LlvmObjdumpProbeResult {
        val output = try {
            executor.execute(
                executable = candidate.path,
                arguments = listOf("--version"),
                timeoutInMilliseconds = timeoutInMilliseconds,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return LlvmObjdumpProbeResult.Failure(
                candidate = candidate,
                reason = LlvmObjdumpProbeFailureReason.ExecutionFailed,
                message = e.message ?: "Failed to execute llvm-objdump",
            )
        }

        if (output.timedOut) {
            return LlvmObjdumpProbeResult.Failure(
                candidate = candidate,
                reason = LlvmObjdumpProbeFailureReason.TimedOut,
                message = "llvm-objdump --version timed out after ${timeoutInMilliseconds}ms",
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
        if (output.cancelled) {
            return LlvmObjdumpProbeResult.Failure(
                candidate = candidate,
                reason = LlvmObjdumpProbeFailureReason.Cancelled,
                message = "llvm-objdump --version was cancelled",
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
        if (output.exitCode != 0) {
            return LlvmObjdumpProbeResult.Failure(
                candidate = candidate,
                reason = LlvmObjdumpProbeFailureReason.NonZeroExit,
                message = "llvm-objdump --version exited with code ${output.exitCode}",
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }

        val versionLine = firstOutputLine(output.stdout) ?: firstOutputLine(output.stderr)
        if (versionLine == null) {
            return LlvmObjdumpProbeResult.Failure(
                candidate = candidate,
                reason = LlvmObjdumpProbeFailureReason.EmptyVersionOutput,
                message = "llvm-objdump --version produced no output",
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }

        return LlvmObjdumpProbeResult.Success(
            candidate = candidate,
            versionLine = versionLine,
            stdout = output.stdout,
            stderr = output.stderr,
        )
    }
}

private fun firstOutputLine(text: String): String? =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
