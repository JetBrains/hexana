package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext

/**
 * Suspend context produced when LLDB stops execution (breakpoint hit,
 * step completed, signal, etc.).
 *
 * It provides a single execution stack whose top frame points at the
 * source location reported by LLDB. When an [LldbCommunicator] is
 * available, the full backtrace is retrieved from LLDB's `bt` command.
 */
internal class WasmSuspendContext(
    private val topFrame: WasmStackFrame,
    private val lldb: LldbCommunicator? = null,
    private val debugProcess: WasmDebugProcess? = null,
) : XSuspendContext() {

    private val executionStack = WasmExecutionStack(topFrame, lldb, debugProcess)

    override fun getActiveExecutionStack(): XExecutionStack = executionStack

    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(executionStack)
}

internal class WasmExecutionStack(
    private val topFrame: WasmStackFrame,
    private val lldb: LldbCommunicator?,
    private val debugProcess: WasmDebugProcess?,
) : XExecutionStack("Wasm Thread") {

    private val dwarfVariableResolver: WasmDwarfVariableResolver?
        get() = debugProcess?.dwarfVariableResolver

    override fun getTopFrame(): XStackFrame = topFrame

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer?) {
        if (container == null) return

        val communicator = lldb
        if (communicator == null) {
            if (firstFrameIndex == 0) {
                container.addStackFrames(listOf(topFrame), true)
            } else {
                container.addStackFrames(emptyList(), true)
            }
            return
        }

        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                try {
                    val lines = communicator.sendCommandAndCollectOutput("bt")
                    val frames = parseBacktrace(lines)
                    if (frames.isEmpty()) {
                        container.addStackFrames(listOf(topFrame), true)
                    } else {
                        val result = if (firstFrameIndex < frames.size) {
                            frames.subList(firstFrameIndex, frames.size)
                        } else {
                            emptyList()
                        }
                        container.addStackFrames(result, true)
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to retrieve backtrace", e)
                    if (firstFrameIndex == 0) {
                        container.addStackFrames(listOf(topFrame), true)
                    } else {
                        container.addStackFrames(emptyList(), true)
                    }
                }
            }
    }

    /**
     * Parses LLDB `bt` output into [WasmStackFrame] instances.
     *
     * Typical `bt` output:
     * ```
     * * thread #1, stop reason = breakpoint 1.1
     *   * frame #0: 0x... wasmtime`calc::add(x=1, y=2) at lib.rs:5:3
     *     frame #1: 0x... wasmtime`main at main.rs:10:5
     *     frame #2: 0x... wasmtime`_start
     * ```
     */
    private fun parseBacktrace(lines: List<String>): List<WasmStackFrame> {
        val frames = mutableListOf<WasmStackFrame>()
        for (line in lines) {
            val parsed = parseBacktraceLine(line)
            if (parsed != null) {
                frames.add(parsed)
            }
        }
        return frames
    }

    /**
     * Parses a single `bt` frame line. The format is:
     * ```
     *   * frame #0: 0x1234 module`function at file.rs:10:3
     *     frame #1: 0x5678 module`function
     * ```
     */
    private fun parseBacktraceLine(line: String): WasmStackFrame? {
        val match = BACKTRACE_FRAME_PATTERN.find(line) ?: return null
        val frameIndex = match.groupValues[1].toIntOrNull() ?: return null
        val description = match.groupValues[2].trim()

        // Parse function name: after "module`" up to " at " or end
        val funcName = parseFunctionFromDescription(description)
        val sourcePos = parseSourceFromDescription(description)

        return WasmStackFrame(
            sourcePosition = sourcePos,
            functionName = funcName,
            lldb = lldb,
            frameIndex = frameIndex,
            dwarfVariableResolver = dwarfVariableResolver,
        )
    }

    private fun parseFunctionFromDescription(description: String): String? {
        // Component-model format (backtick delimiter):
        //   "0xaddr JIT(0x...)`function(...) at file:line:col"
        // Classic wasm format (no backtick — function name directly
        // follows the closing parenthesis of the module identifier):
        //   "0xaddr JIT(0x...)function(...) at file:line:col"
        val backtickIdx = description.indexOf('`')
        val afterDelimiter = if (backtickIdx >= 0) {
            description.substring(backtickIdx + 1)
        } else {
            // No backtick — try the closing ')' of a JIT(...) module id.
            val parenIdx = description.indexOf(')')
            if (parenIdx < 0) return null
            description.substring(parenIdx + 1)
        }

        val atIdx = afterDelimiter.indexOf(" at ")
        val raw = if (atIdx >= 0) {
            afterDelimiter.substring(0, atIdx).trim()
        } else {
            afterDelimiter.trim()
        }
        return raw.ifEmpty { null }
    }

    private fun parseSourceFromDescription(description: String): com.intellij.xdebugger.XSourcePosition? {
        val atIdx = description.indexOf(" at ")
        if (atIdx < 0) return null
        val locationPart = description.substring(atIdx + 4).trim()

        // locationPart = "file.rs:10:3" or "file.rs:10"
        val parts = locationPart.split(":")
        if (parts.size < 2) return null
        val filePath = parts[0]
        val lineNum = parts[1].toIntOrNull() ?: return null

        val file = debugProcess?.resolveFileByDwarfPath(filePath)
        if (file != null) {
            return com.intellij.xdebugger.XDebuggerUtil.getInstance()
                .createPosition(file, lineNum - 1)
        }
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(WasmExecutionStack::class.java)

        /**
         * Matches a frame line in `bt` output:
         * ```
         *   * frame #0: ...
         *     frame #1: ...
         * ```
         */
        private val BACKTRACE_FRAME_PATTERN =
            """\s*\*?\s*frame #(\d+):\s+(.+)""".toRegex()
    }
}
