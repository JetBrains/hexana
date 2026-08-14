package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LldbOutputParserTest {

    // ── parseLldbStopEvent ──────────────────────────────────────────

    @Test
    fun breakpointHit() {
        val event = parseLldbStopEvent("* thread #1, stop reason = breakpoint 1.1")
        assertIs<LldbStopEvent.BreakpointHit>(event)
        assertEquals(1, event.threadId)
        assertEquals("1.1", event.breakpointId)
    }

    @Test
    fun breakpointHitThread2() {
        val event = parseLldbStopEvent("* thread #2, stop reason = breakpoint 3.1")
        assertIs<LldbStopEvent.BreakpointHit>(event)
        assertEquals(2, event.threadId)
        assertEquals("3.1", event.breakpointId)
    }

    @Test
    fun excBreakpoint() {
        val event = parseLldbStopEvent(
            "* thread #1, stop reason = EXC_BREAKPOINT (code=1, subcode=0x1234)"
        )
        assertIs<LldbStopEvent.BreakpointHit>(event)
        assertEquals(1, event.threadId)
        assertEquals("", event.breakpointId)
    }

    @Test
    fun windowsStatusBreakpoint() {
        val event = parseLldbStopEvent(
            "* thread #1, stop reason = Exception 0x80000003"
        )
        assertIs<LldbStopEvent.BreakpointHit>(event)
        assertEquals(1, event.threadId)
        assertEquals("", event.breakpointId)
    }

    @Test
    fun stepped() {
        val event = parseLldbStopEvent("* thread #1, stop reason = step in")
        assertIs<LldbStopEvent.Stepped>(event)
    }

    @Test
    fun planComplete() {
        val event = parseLldbStopEvent("* thread #1, stop reason = plan complete")
        assertIs<LldbStopEvent.Stepped>(event)
    }

    @Test
    fun trace() {
        val event = parseLldbStopEvent("* thread #1, stop reason = trace")
        assertIs<LldbStopEvent.Stepped>(event)
    }

    @Test
    fun stoppedAtEntry() {
        val event = parseLldbStopEvent("* thread #1, stop reason = signal SIGSTOP")
        assertIs<LldbStopEvent.StoppedAtEntry>(event)
    }

    @Test
    fun processExited() {
        val event = parseLldbStopEvent("Process 12345 exited with status = 0 (0x00000000)")
        assertIs<LldbStopEvent.ProcessExited>(event)
        assertEquals(0, event.exitCode)
    }

    @Test
    fun processExitedNonZero() {
        val event = parseLldbStopEvent("Process 12345 exited with status = 42 (0x0000002a)")
        assertIs<LldbStopEvent.ProcessExited>(event)
        assertEquals(42, event.exitCode)
    }

    @Test
    fun noStopEvent() {
        assertNull(parseLldbStopEvent("some random LLDB output"))
        assertNull(parseLldbStopEvent(""))
        assertNull(parseLldbStopEvent("  "))
    }

    // ── parseLldbExpressionResult ───────────────────────────────────

    @Test
    fun parseSimpleInt() {
        val result = parseLldbExpressionResult("(int) \$0 = 42")
        assertEquals("int", result.typeName)
        assertEquals("42", result.displayValue)
    }

    @Test
    fun parsePointerWithString() {
        val result = parseLldbExpressionResult(
            """(const char *) ${'$'}1 = 0x00007ff "hello""""
        )
        assertEquals("const char *", result.typeName)
        assertEquals("""0x00007ff "hello"""", result.displayValue)
    }

    @Test
    fun parseFloat() {
        val result = parseLldbExpressionResult("(double) \$2 = 3.14")
        assertEquals("double", result.typeName)
        assertEquals("3.14", result.displayValue)
    }

    @Test
    fun parseBool() {
        val result = parseLldbExpressionResult("(bool) \$5 = true")
        assertEquals("bool", result.typeName)
        assertEquals("true", result.displayValue)
    }

    @Test
    fun fallbackForUnrecognized() {
        val raw = "some unexpected output"
        val result = parseLldbExpressionResult(raw)
        assertNull(result.typeName)
        assertEquals(raw, result.displayValue)
    }

    @Test
    fun parseMultilineValue() {
        val text = "(MyStruct) \$0 = {\n  x = 1\n  y = 2\n}"
        val result = parseLldbExpressionResult(text)
        assertEquals("MyStruct", result.typeName)
        assertEquals("{\n  x = 1\n  y = 2\n}", result.displayValue)
    }

    // ── parseLldbFrameLocation ──────────────────────────────────────

    @Test
    fun frameLocation() {
        val lines = listOf(
            "frame #0: 0x1234 JIT(0x5678)`calculate(a=1, b=2) at src/lib.rs:42:5"
        )
        val loc = parseLldbFrameLocation(lines)
        assertEquals("src/lib.rs", loc?.filePath)
        assertEquals(42, loc?.line)
    }

    @Test
    fun frameLocationNoColumn() {
        val lines = listOf(
            "frame #0: 0x1234 JIT(0x5678)`func at /home/user/project/main.c:10"
        )
        val loc = parseLldbFrameLocation(lines)
        assertEquals("/home/user/project/main.c", loc?.filePath)
        assertEquals(10, loc?.line)
    }

    @Test
    fun frameLocationNotFound() {
        val lines = listOf("some random output", "no frame info here")
        assertNull(parseLldbFrameLocation(lines))
    }

    @Test
    fun framePc() {
        val lines = listOf(
            "frame #0: 0x0000000102290abc JIT(0x5678)`calculate(a=1, b=2) at src/lib.rs:42:5"
        )
        assertEquals(0x102290abcuL, parseLldbFramePc(lines))
    }

    @Test
    fun framePcPrefersLastFrameLine() {
        val lines = listOf(
            "frame #1: 0x0000000100001000 wasmtime`main",
            "frame #0: 0x0000000102290abc JIT(0x5678)`calculate",
        )
        assertEquals(0x102290abcuL, parseLldbFramePc(lines))
    }

    @Test
    fun framePcNotFound() {
        assertEquals(null, parseLldbFramePc(listOf("Process 123 stopped", "no frame here 0x1234")))
    }

    // ── parseRawDwarfPath / parseRawDwarfLine ───────────────────────

    @Test
    fun rawDwarfPath() {
        val lines = listOf(
            "frame #0: 0x1234 JIT(0x5678)`func at src/lib.rs:42"
        )
        assertEquals("src/lib.rs", parseRawDwarfPath(lines))
    }

    @Test
    fun rawDwarfLine() {
        val lines = listOf(
            "frame #0: 0x1234 JIT(0x5678)`func at src/lib.rs:42"
        )
        assertEquals(42, parseRawDwarfLine(lines))
    }

    // ── parseLldbFunctionName ───────────────────────────────────────

    @Test
    fun functionNameClassicModule() {
        val lines = listOf(
            "frame #0: 0x1234 JIT(0x5678)some_func(a=1) at src/lib.rs:42"
        )
        assertEquals("some_func", parseLldbFunctionName(lines))
    }

    @Test
    fun functionNameComponentModel() {
        val lines = listOf(
            "frame #0: 0x00000001048e3d94 JIT(0x12e008600)`_\$LT\$calculator..Component\$u20\$as\$u20\$calculator..bindings..exports..component..calculator..operations..Guest\$GT\$::calculate::h9c65fe17a79e7c17(a=1, b=2) at src/lib.rs:36:5"
        )
        val name = parseLldbFunctionName(lines)
        assertTrue(name != null && name.contains("calculator"))
    }

    @Test
    fun functionNameNotFound() {
        val lines = listOf("some random output")
        assertNull(parseLldbFunctionName(lines))
    }

    @Test
    fun wamrGoFunctionName() {
        // WAMR frame output for Go — no backtick or JIT module identifier.
        // The function name follows the hex address directly.
        val lines = listOf(
            "frame #0: 0x400000000000041a add(x=<unavailable>, y=<unavailable>) at main.go:15:14"
        )
        assertEquals("add", parseLldbFunctionName(lines))
    }

    @Test
    fun wamrGoFunctionNameWithOffset() {
        // WAMR frame output with `+ N` offset instead of `at file:line`.
        val lines = listOf(
            "frame #0: 0x400000000000041a add + 1"
        )
        assertEquals("add", parseLldbFunctionName(lines))
    }

    // ── Wasmtime gdbstub instruction-step stop events ─────────────

    @Test
    fun instructionStepInto() {
        // `thread step-inst` in gdbstub mode produces this stop reason.
        val event = parseLldbStopEvent(
            "* thread #1, stop reason = instruction step into"
        )
        assertIs<LldbStopEvent.Stepped>(event)
    }

    @Test
    fun instructionStepOver() {
        // `thread step-inst-over` in gdbstub mode produces this stop reason.
        val event = parseLldbStopEvent(
            "* thread #1, stop reason = instruction step over"
        )
        assertIs<LldbStopEvent.Stepped>(event)
    }

    @Test
    fun instructionStepIntoWith64BitWasmPc() {
        // In gdbstub mode the PC encodes the wasm byte offset in the low 32
        // bits: 0x4000000000000095 -> wasm offset 0x95.
        val lines = listOf(
            "* thread #1, stop reason = instruction step into",
            "    frame #0: 0x4000000000000095 wasm-0`add(a=3, b=7) at debug_add.rs:10:15",
        )
        val event = parseLldbStopEvent(lines[0])
        assertIs<LldbStopEvent.Stepped>(event)

        val pc = parseLldbFramePc(lines)
        assertEquals(0x4000000000000095uL, pc)
        // Subclass extracts wasm offset as low 32 bits.
        assertEquals(0x95, (pc!! and 0xFFFFFFFFuL).toInt())
    }

    @Test
    fun instructionStepOverWith64BitWasmPc() {
        val lines = listOf(
            "* thread #1, stop reason = instruction step over",
            "    frame #0: 0x40000000000001a8 wasm-0`main() at main.rs:5:9",
        )
        val pc = parseLldbFramePc(lines)
        assertEquals(0x40000000000001a8uL, pc)
        assertEquals(0x1a8, (pc!! and 0xFFFFFFFFuL).toInt())
    }

    // ── WAMR-specific stop events and frame output ────────────────

    @Test
    fun wamrTraceStopOnConnect() {
        // WAMR produces `stop reason = trace` when LLDB first connects.
        val event = parseLldbStopEvent(
            "* thread #1, name = 'nobody', stop reason = trace"
        )
        assertIs<LldbStopEvent.Stepped>(event)
    }

    @Test
    fun wamrBreakpointHit() {
        // WAMR breakpoint hit with the `name = 'nobody'` thread name.
        val event = parseLldbStopEvent(
            "* thread #1, name = 'nobody', stop reason = breakpoint 1.1"
        )
        assertIs<LldbStopEvent.BreakpointHit>(event)
        assertEquals(1, event.threadId)
        assertEquals("1.1", event.breakpointId)
    }

    @Test
    fun wamrGoFrameLocation() {
        // WAMR frame output for Go — no backtick or JIT module identifier.
        val lines = listOf(
            "frame #0: 0x400000000000041a add(x=<unavailable>, y=<unavailable>) at main.go:15:14"
        )
        val loc = parseLldbFrameLocation(lines)
        assertEquals("main.go", loc?.filePath)
        assertEquals(15, loc?.line)
    }

    @Test
    fun wamrGoRawDwarfPath() {
        val lines = listOf(
            "frame #0: 0x400000000000041a add(x=<unavailable>, y=<unavailable>) at main.go:15:14"
        )
        assertEquals("main.go", parseRawDwarfPath(lines))
    }

    @Test
    fun wamrGoRawDwarfLine() {
        val lines = listOf(
            "frame #0: 0x400000000000041a add(x=<unavailable>, y=<unavailable>) at main.go:15:14"
        )
        assertEquals(15, parseRawDwarfLine(lines))
    }

    // ── looksLikeWamrDebugNotSupported ──────────────────────────────

    @Test
    fun wamrDebugNotSupportedWithUsageAndNoGFlag() {
        assertTrue(looksLikeWamrDebugNotSupported("Usage: iwasm [options] -- wasm_file"))
    }

    @Test
    fun wamrDebugSupportedWithGFlag() {
        val output = "Usage: iwasm [options] -- wasm_file\n  -g=host:port"
        assertEquals(false, looksLikeWamrDebugNotSupported(output))
    }

    @Test
    fun wamrDebugBlankOutput() {
        assertEquals(false, looksLikeWamrDebugNotSupported(""))
        assertEquals(false, looksLikeWamrDebugNotSupported("   "))
    }
}
