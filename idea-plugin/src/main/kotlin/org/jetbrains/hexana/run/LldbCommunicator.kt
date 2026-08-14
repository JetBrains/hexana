package org.jetbrains.hexana.run

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import org.jetbrains.hexana.shared.LldbStopEvent
import org.jetbrains.hexana.shared.parseLldbStopEvent
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Manages communication with an LLDB process.
 *
 * Commands are sent via the process's stdin. Output is received through
 * an [OSProcessHandler] listener (so the handler's built-in stream
 * reading is not bypassed). The communicator parses output lines
 * looking for stop events (breakpoint hits, step completions, process
 * exit) and makes them available via [waitForStopEvent].
 */
class LldbCommunicator(private val processHandler: OSProcessHandler) {

    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(processHandler.process.outputStream))

    private val stopEvents = LinkedBlockingQueue<StopEvent>()

    /**
     * Lock protecting command-response collection state.
     * When a caller uses [sendCommandAndCollectOutput], incoming output
     * lines between the start and end fence markers are captured here.
     */
    private val responseLock = Any()
    private var responseCollector: MutableList<String>? = null
    private var responseLatch: CountDownLatch? = null
    private var responseEndMarker: String? = null

    /**
     * When `true`, stop-event parsing is suppressed so that output
     * from informational commands (like `bt`, `frame info`, `frame variable`)
     * is not mistaken for a real process stop. Without this guard,
     * a `bt` response that contains `stop reason = breakpoint 1.1`
     * would feed a spurious [StopEvent] into the queue and cause
     * the event loop to trigger [WasmDebugProcess.handleStop] again,
     * which in turn issues another `bt` — creating an infinite loop.
     */
    @Volatile
    @PublishedApi
    internal var suppressStopEvents = false

    @Volatile
    var isRunning = false
        private set

    /**
     * Set to `true` once [destroy] has been called. Commands sent
     * after this point are silently dropped — the stream is expected
     * to be (or become) closed, so IOExceptions are not logged as
     * warnings.
     */
    @Volatile
    private var destroyed = false

    /**
     * Set to `true` when the debuggee process has exited (LLDB
     * reports `Process exited`).  Commands sent after this point
     * are silently dropped to avoid "Process is not running"
     * errors — particularly on Windows where the debuggee can
     * exit between a stop event being queued and the event loop
     * sending a `continue`.
     */
    @Volatile
    var debuggeeExited = false
        private set

    /**
     * Installs a [ProcessAdapter] on the handler that parses every
     * output line for stop-event patterns. Call this before starting
     * the process handler.
     */
    fun attachToProcessHandler() {
        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                for (line in text.lines()) {
                    if (line.isBlank()) continue
                    LOG.debug("LLDB> $line")
                    var insideResponse = false
                    synchronized(responseLock) {
                        val endMarker = responseEndMarker
                        if (endMarker != null) {
                            insideResponse = true
                            if (line.trim() == endMarker) {
                                responseEndMarker = null
                                responseLatch?.countDown()
                            } else if (!line.trimStart().startsWith(LLDB_PROMPT_PREFIX)) {
                                // Skip LLDB command echoes (e.g. "(lldb) expression -- x")
                                // so they don't leak into collected output.
                                responseCollector?.add(line)
                            }
                        }
                    }
                    // Skip stop-event detection for lines that are part
                    // of a command response or while suppression is active.
                    // Without this guard, `bt` or `frame info` output that
                    // echoes "stop reason = breakpoint" would be
                    // misinterpreted as a new process stop.
                    //
                    // ProcessExited events are NEVER suppressed and NEVER
                    // blocked by insideResponse: if the debuggee exits
                    // while a command response is being collected or while
                    // stop events are suppressed (e.g. during
                    // handleStopImpl → resumePastTrap), the exit event
                    // must still be queued so the event loop can terminate
                    // the debug session. Without this, the "Process exited"
                    // line gets swallowed into the response collector and
                    // the session hangs forever.
                    if (!insideResponse && !suppressStopEvents) {
                        val stopEvent = parseStopEvent(line)
                        if (stopEvent != null) {
                            LOG.debug("LLDB stop-event parsed (normal path): $stopEvent")
                            stopEvents.offer(stopEvent)
                        }
                    } else {
                        // Even when inside a response or suppressed, always
                        // parse and queue process-exit events to prevent the
                        // event loop from spinning forever after the debuggee
                        // exits.
                        val stopEvent = parseStopEvent(line)
                        if (stopEvent is StopEvent.ProcessExited) {
                            LOG.debug("LLDB stop-event parsed (insideResponse=$insideResponse, suppressStopEvents=$suppressStopEvents): $stopEvent")
                            stopEvents.offer(stopEvent)
                        } else if (stopEvent != null) {
                            LOG.debug("LLDB stop-event DROPPED (insideResponse=$insideResponse, suppressStopEvents=$suppressStopEvents): $stopEvent")
                        }
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                LOG.debug("LLDB processTerminated callback: exitCode=${event.exitCode}, debuggeeExited=$debuggeeExited")
                isRunning = false
                stopEvents.offer(StopEvent.ProcessExited(event.exitCode))
            }
        })
    }

    /**
     * Sends a command to LLDB via stdin.
     */
    fun sendCommand(command: String) {
        if (destroyed) {
            LOG.debug("LLDB CMD (dropped, already destroyed): $command")
            return
        }
        if (debuggeeExited) {
            LOG.debug("LLDB CMD (dropped, debuggee exited): $command")
            return
        }
        LOG.debug("LLDB CMD: $command")
        try {
            writer.write(command)
            writer.newLine()
            writer.flush()
        } catch (e: java.io.IOException) {
            if (destroyed) {
                // Expected during shutdown — the process stream is closed.
                LOG.debug("LLDB stream closed while sending command: $command")
            } else {
                LOG.warn("LLDB stream closed unexpectedly while sending command: $command", e)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to send LLDB command: $command", e)
        }
    }

    /**
     * Waits for the next stop event from LLDB (breakpoint hit, signal, exit).
     * Returns `null` if the timeout expires.
     */
    fun waitForStopEvent(timeoutMs: Long = 30_000): StopEvent? {
        val event = stopEvents.poll(timeoutMs, TimeUnit.MILLISECONDS)
        return event
    }

    fun sendBreakpoint(filePath: String, line: Int) {
        sendCommand("breakpoint set --file \"$filePath\" --line $line")
    }

    fun sendBreakpointOnFunction(functionName: String) {
        sendCommand("breakpoint set --name $functionName")
    }

    /**
     * Sends a file/line breakpoint and returns `true` if LLDB resolved
     * it to at least one location (i.e. the output does NOT contain
     * "pending").
     */
    fun sendBreakpointAndCheckResolved(filePath: String, line: Int): Boolean {
        val output = sendCommandAndCollectOutput(
            "breakpoint set --file \"$filePath\" --line $line",
        )
        return output.none { "pending" in it }
    }

    /**
     * On Windows, LLDB's JIT loader GDB plugin does not automatically
     * process JIT debug symbols registered via the GDB JIT interface.
     * This method manually reads `__jit_debug_descriptor.relevant_entry`
     * from the inferior's memory, extracts the in-memory ELF object
     * (symfile_addr + symfile_size), writes it to a temporary file,
     * loads it into LLDB, and then searches the loaded module's line
     * tables for addresses matching the given file/line breakpoints.
     *
     * Must be called while the process is stopped at
     * `__jit_debug_register_code`.
     *
     * @param breakpoints list of (basename, line) pairs to resolve.
     * @return list of hex addresses that were found and set as
     *         address breakpoints, empty if nothing resolved.
     */
    fun loadJitObjectAndResolveBreakpoints(
        tempDir: String,
        breakpoints: List<Pair<String, Int>>,
    ): List<String> {
        val bpListPy = breakpoints.joinToString(",") { (file, line) ->
            "('${file.replace("'", "\\'")}',${line})"
        }
        val escapedTempDir = tempDir.replace("\\", "/")
        // Build a multi-line Python script that will be written to a
        // temp file and executed via `command script import`.
        val pythonLines = listOf(
            "import struct, os, lldb",
            "t = lldb.debugger.GetSelectedTarget()",
            "p = t.GetProcess()",
            "e = t.GetByteOrder()",
            "ps = t.GetAddressByteSize()",
            "syms = [s for s in t.FindSymbols('__jit_debug_descriptor')]",
            "ok = len(syms) > 0",
            "if not ok:",
            "    gvs = t.FindGlobalVariables('__jit_debug_descriptor', 1)",
            "    if gvs.GetSize() > 0:",
            "        ok = True",
            "",
            "print('DIAG:syms=%d ok=%s' % (len(syms), ok))",
            "desc_addr = 0",
            "if ok and len(syms) > 0:",
            "    desc_addr = syms[0].GetSymbol().GetStartAddress().GetLoadAddress(t)",
            "elif ok:",
            "    gv = t.FindGlobalVariables('__jit_debug_descriptor', 1).GetValueAtIndex(0)",
            "    desc_addr = gv.GetLoadAddress() if gv else 0",
            "    ok = desc_addr != 0 and desc_addr != 0xFFFFFFFFFFFFFFFF",
            "",
            "print('DIAG:desc_addr=0x%x' % desc_addr)",
            "err = lldb.SBError()",
            "desc_bytes = p.ReadMemory(desc_addr, 4+4+ps+ps, err) if ok else b''",
            "ok = ok and err.Success() and len(desc_bytes) >= 4+4+ps+ps",
            "print('DIAG:desc_read ok=%s err=%s len=%d' % (ok, err, len(desc_bytes)))",
            "bo = '<' if e == lldb.eByteOrderLittle else '>'",
            "pfmt = bo + ('Q' if ps == 8 else 'I')",
            "action = struct.unpack_from(bo+'I', desc_bytes, 4)[0] if ok else -1",
            "rel_entry = struct.unpack_from(pfmt, desc_bytes, 8)[0] if ok else 0",
            "first_entry = struct.unpack_from(pfmt, desc_bytes, 8+ps)[0] if ok else 0",
            "print('DIAG:action=%d rel_entry=0x%x first_entry=0x%x' % (action, rel_entry, first_entry))",
            "print('DIAG:desc_hex=%s' % (desc_bytes.hex() if ok else 'N/A'))",
            "if rel_entry == 0 and first_entry != 0:",
            "    rel_entry = first_entry",
            "",
            "ok = ok and rel_entry != 0",
            "entry_bytes = p.ReadMemory(rel_entry, ps+ps+ps+8, err) if ok else b''",
            "ok = ok and err.Success() and len(entry_bytes) >= ps+ps+ps+8",
            "print('DIAG:entry_read ok=%s err=%s' % (ok, err))",
            "sym_addr = struct.unpack_from(pfmt, entry_bytes, ps+ps)[0] if ok else 0",
            "sym_size = struct.unpack_from(bo+'Q', entry_bytes, ps+ps+ps)[0] if ok else 0",
            "print('DIAG:sym_addr=0x%x sym_size=%d' % (sym_addr, sym_size))",
            "ok = ok and sym_addr != 0 and sym_size > 0 and sym_size < 100*1024*1024",
            "obj_bytes = p.ReadMemory(sym_addr, sym_size, err) if ok else b''",
            "ok = ok and err.Success() and len(obj_bytes) == sym_size",
            "print('DIAG:obj_read ok=%s err=%s len=%d' % (ok, err, len(obj_bytes)))",
            "fp = os.path.join('$escapedTempDir', 'jit_obj_' + hex(sym_addr) + '.elf') if ok else ''",
            "if ok:",
            "    with open(fp, 'wb') as f:",
            "        f.write(obj_bytes)",
            "    print('DIAG:wrote %s (%d bytes)' % (fp, len(obj_bytes)))",
            "",  // end of if block
            "mod = t.AddModule(fp, '', None) if ok else None",
            "print('DIAG:mod=%s valid=%s' % (mod, mod.IsValid() if mod else False))",
            "ok = ok and mod is not None and mod.IsValid()",
            "if ok:",
            "    for i in range(mod.GetNumSections()):",
            "        sec = mod.GetSectionAtIndex(i)",
            "        t.SetSectionLoadAddress(sec, sym_addr + sec.GetFileOffset())",
            "",  // end of if block
            "bps = [$bpListPy]",
            "found = []",
            // First try the manually-loaded JIT module (if descriptor was populated).
            "if ok:",
            "    ncu = mod.GetNumCompileUnits()",
            "    print('DIAG:compile_units=%d' % ncu)",
            "    for ci in range(ncu):",
            "        cu = mod.GetCompileUnitAtIndex(ci)",
            "        nle = cu.GetNumLineEntries()",
            "        print('DIAG:cu[%d] line_entries=%d file=%s' % (ci, nle, cu.GetFileSpec()))",
            "        for li in range(min(nle, 5)):",
            "            le = cu.GetLineEntryAtIndex(li)",
            "            fs = le.GetFileSpec()",
            "            fn = fs.GetFilename() if fs.IsValid() else ''",
            "            ln = le.GetLine()",
            "            print('DIAG:  entry[%d] file=%s line=%d' % (li, fn, ln))",
            "        for li in range(nle):",
            "            le = cu.GetLineEntryAtIndex(li)",
            "            fs = le.GetFileSpec()",
            "            fn = fs.GetFilename() if fs.IsValid() else ''",
            "            ln = le.GetLine()",
            "            for (bf, bl) in bps:",
            "                if fn == bf and ln == bl:",
            "                    addr = le.GetStartAddress().GetLoadAddress(t)",
            "                    if addr != lldb.LLDB_INVALID_ADDRESS and addr not in found:",
            "                        found.append(addr)",
            "",  // end of if block
            // Fallback: if the descriptor was empty (wasmtime on Windows ARM64
            // never populates it), search ALL modules in the target. The
            // built-in JIT loader (plugin.jit-loader.gdb.enable) may have
            // loaded JIT objects that we can find line entries in.
            "if not found:",
            "    print('DIAG:fallback scanning all %d target modules' % t.GetNumModules())",
            "    for mi in range(t.GetNumModules()):",
            "        m = t.GetModuleAtIndex(mi)",
            "        ncu2 = m.GetNumCompileUnits()",
            "        if ncu2 == 0:",
            "            continue",
            "        for ci2 in range(ncu2):",
            "            cu2 = m.GetCompileUnitAtIndex(ci2)",
            "            nle2 = cu2.GetNumLineEntries()",
            "            for li2 in range(nle2):",
            "                le2 = cu2.GetLineEntryAtIndex(li2)",
            "                fs2 = le2.GetFileSpec()",
            "                fn2 = fs2.GetFilename() if fs2.IsValid() else ''",
            "                ln2 = le2.GetLine()",
            "                for (bf, bl) in bps:",
            "                    if fn2 == bf and ln2 == bl:",
            "                        addr2 = le2.GetStartAddress().GetLoadAddress(t)",
            "                        if addr2 != lldb.LLDB_INVALID_ADDRESS and addr2 not in found:",
            "                            found.append(addr2)",
            "                            print('DIAG:fallback found addr=0x%x in module=%s cu=%s' % (addr2, m, cu2.GetFileSpec()))",
            "",  // end of if block
            "print('DIAG:bps=%s found=%s' % (bps, found))",
            "if found:",
            "    for a in found:",
            "        lldb.debugger.HandleCommand('breakpoint set --address 0x%x' % a)",
            "        print('RESOLVED:0x%x' % a)",
            "else:",
            "    print('NONE')",
            "",  // end of if/else block
        )
        val output = sendPythonScriptAndCollectOutput(pythonLines, timeoutMs = 30_000)
        val resolved = output.filter { "RESOLVED:" in it }.map { it.substringAfter("RESOLVED:").trim() }
        if (resolved.isNotEmpty()) {
            LOG.debug("JIT breakpoints resolved at: $resolved")
        } else {
            LOG.warn("JIT object load/resolve produced no matches: ${output.joinToString(" ")}")
        }
        return resolved
    }

    /**
     * Dumps every ELF image registered with the GDB JIT interface
     * (`__jit_debug_descriptor`) from the inferior's memory into
     * [tempDir], one file per registered entry.
     *
     * Wasmtime registers one image per compiled module; each contains
     * the `.wasmtime.addrmap` section with the `.text` address patched
     * to the real load address of the JIT-compiled code, which is what
     * [org.jetbrains.hexana.shared.WasmtimeAddrmap] consumes.
     *
     * Must be called while the process is stopped.
     *
     * @return absolute paths of the dumped image files, empty when the
     *         descriptor is absent or has no populated entries (e.g.
     *         wasmtime on Windows ARM64).
     */
    fun dumpJitImages(tempDir: String): List<String> {
        val escapedTempDir = tempDir.replace("\\", "/")
        val pythonLines = listOf(
            "import struct, os, lldb",
            "t = lldb.debugger.GetSelectedTarget()",
            "p = t.GetProcess()",
            "e = t.GetByteOrder()",
            "ps = t.GetAddressByteSize()",
            "bo = '<' if e == lldb.eByteOrderLittle else '>'",
            "pfmt = bo + ('Q' if ps == 8 else 'I')",
            "syms = [s for s in t.FindSymbols('__jit_debug_descriptor')]",
            "desc_addr = syms[0].GetSymbol().GetStartAddress().GetLoadAddress(t) if syms else 0",
            "err = lldb.SBError()",
            "desc = p.ReadMemory(desc_addr, 8 + ps + ps, err) if desc_addr else b''",
            "first = struct.unpack_from(pfmt, desc, 8 + ps)[0] if err.Success() and len(desc) >= 8 + ps + ps else 0",
            "entry = first",
            "i = 0",
            "while entry and i < 64:",
            "    eb = p.ReadMemory(entry, ps * 3 + 8, err)",
            "    if not err.Success() or len(eb) < ps * 3 + 8:",
            "        break",
            "    nxt = struct.unpack_from(pfmt, eb, 0)[0]",
            "    sym_addr = struct.unpack_from(pfmt, eb, ps * 2)[0]",
            "    sym_size = struct.unpack_from(bo + 'Q', eb, ps * 3)[0]",
            "    if sym_addr and 0 < sym_size < 100 * 1024 * 1024:",
            "        img = p.ReadMemory(sym_addr, sym_size, err)",
            "        if err.Success() and len(img) == sym_size:",
            "            fp = os.path.join('$escapedTempDir', 'jit_img_%d_%x.elf' % (i, sym_addr))",
            "            with open(fp, 'wb') as f:",
            "                f.write(img)",
            "            print('JITIMG:%s' % fp)",
            "    entry = nxt",
            "    i += 1",
            "",  // end of while block
        )
        val output = sendPythonScriptAndCollectOutput(pythonLines, timeoutMs = 30_000)
        val paths = output.filter { "JITIMG:" in it }.map { it.substringAfter("JITIMG:").trim() }
        if (paths.isEmpty()) {
            LOG.warn("dumpJitImages: no JIT images found: ${output.joinToString(" ")}")
        } else {
            LOG.debug("dumpJitImages: dumped ${paths.size} image(s): $paths")
        }
        return paths
    }

    fun sendSourceMap(from: String, to: String) {
        sendCommand("settings set target.source-map \"$from\" \"$to\"")
    }

    fun removeAllBreakpoints() {
        sendCommand("breakpoint delete -f")
    }

    fun resume() {
        LOG.debug("LLDB resume() called, debuggeeExited=$debuggeeExited")
        isRunning = true
        sendCommand("continue")
    }

    fun pause() {
        sendCommand("process interrupt")
    }

    fun stepOver() {
        isRunning = true
        sendCommand("next")
    }

    fun stepInto() {
        isRunning = true
        sendCommand("step")
    }

    fun stepOut() {
        isRunning = true
        sendCommand("finish")
    }

    /**
     * Issues `thread step-inst` to LLDB: steps exactly one wasm instruction
     * (the step-into variant for instruction-level stepping).
     *
     * Used by [WasmtimeGdbDebugProcess] when the Wasm disassembly tab is
     * selected, so the user steps by individual wasm instructions rather than
     * source lines.
     */
    fun stepInstInto() {
        isRunning = true
        sendCommand("thread step-inst")
    }

    /**
     * Issues `thread step-inst-over` to LLDB: steps exactly one wasm
     * instruction without entering called functions (step-over variant).
     *
     * Used by [WasmtimeGdbDebugProcess] when the Wasm disassembly tab is
     * selected.
     */
    fun stepInstOver() {
        isRunning = true
        sendCommand("thread step-inst-over")
    }

    fun runProcess() {
        LOG.debug("LLDB runProcess() called")
        isRunning = true
        sendCommand("run")
    }

    /**
     * Temporarily suppresses stop-event detection on incoming LLDB
     * output lines for the duration of [block]. This is intended for
     * code paths that issue informational LLDB commands (like
     * `frame info`) whose output may echo stop reasons but should
     * not be treated as new process stops.
     */
    internal inline fun <T> withStopEventsSuppressed(block: () -> T): T {
        suppressStopEvents = true
        try {
            return block()
        } finally {
            suppressStopEvents = false
        }
    }

    /**
     * Sends [command] to LLDB and collects the output lines produced
     * by that command. A unique end-of-response marker is emitted after
     * the real command so the collector knows when all output has arrived.
     *
     * Stop-event detection is suppressed while the response is being
     * collected so that output echoing stop reasons (e.g. `bt` printing
     * `stop reason = breakpoint 1.1`) does not trigger spurious events.
     *
     * This method blocks the calling thread until the response is
     * fully collected or the [timeoutMs] expires.
     *
     * @return the collected output lines, or an empty list on timeout.
     */
    /**
     * Sends a multi-line Python script to LLDB by writing it to a
     * temporary file and executing it with `command script import`.
     * This avoids both the semicolon-as-command-separator issue on
     * Windows and the problematic interactive Python REPL exit
     * (`quit()` can kill LLDB on some platforms).
     */
    fun sendPythonScriptAndCollectOutput(
        pythonLines: List<String>,
        timeoutMs: Long = 5_000,
    ): List<String> {
        if (destroyed || debuggeeExited) return emptyList()

        val marker = "__HEXANA_END_${System.nanoTime()}__"

        // Write the Python script to a temp file, appending the
        // end-marker print so we know when output is complete.
        val scriptFile = java.nio.file.Files.createTempFile("hexana_lldb_", ".py")
        try {
            val scriptContent = buildString {
                for (line in pythonLines) {
                    appendLine(line)
                }
                appendLine("print(\"$marker\")")
            }
            scriptFile.toFile().writeText(scriptContent)

            val latch = CountDownLatch(1)
            val collector = mutableListOf<String>()

            synchronized(responseLock) {
                responseCollector = collector
                responseLatch = latch
                responseEndMarker = marker
            }

            // Execute the script file. Using forward slashes works
            // on both Windows and Unix LLDB.
            val path = scriptFile.toAbsolutePath().toString().replace("\\", "/")
            sendCommand("command script import \"$path\"")

            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

            synchronized(responseLock) {
                responseCollector = null
                responseLatch = null
                responseEndMarker = null
            }

            if (!completed && !destroyed) {
                LOG.warn("Timed out collecting Python script output")
            }
            return collector.toList()
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(scriptFile)
            } catch (_: Exception) {
                // best-effort cleanup
            }
        }
    }

    fun sendCommandAndCollectOutput(
        command: String,
        timeoutMs: Long = 5_000,
    ): List<String> {
        if (destroyed || debuggeeExited) {
            LOG.debug("sendCommandAndCollectOutput: skipped (destroyed=$destroyed, debuggeeExited=$debuggeeExited) cmd=$command")
            return emptyList()
        }
        LOG.debug("sendCommandAndCollectOutput: cmd=$command, timeoutMs=$timeoutMs")

        val marker = "__HEXANA_END_${System.nanoTime()}__"
        val latch = CountDownLatch(1)
        val collector = mutableListOf<String>()

        synchronized(responseLock) {
            responseCollector = collector
            responseLatch = latch
            responseEndMarker = marker
        }

        sendCommand(command)
        // The marker is printed by LLDB's built-in Python, so it
        // appears in the normal output stream right after the command
        // output finishes.
        sendCommand("script print(\"$marker\")")

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        synchronized(responseLock) {
            responseCollector = null
            responseLatch = null
            responseEndMarker = null
        }

        if (!completed && !destroyed) {
            LOG.warn("Timed out collecting output for LLDB command: $command (collected so far: $collector)")
        }
        LOG.debug("sendCommandAndCollectOutput: result for '$command' = $collector")
        return collector.toList()
    }

    /**
     * Re-offers a stop event back into the queue so it can be
     * consumed by the event loop. This is used when a stop event
     * (e.g. [StopEvent.ProcessExited]) is consumed during setup
     * but must still be processed by the main event loop.
     */
    fun reofferStopEvent(event: StopEvent) {
        LOG.debug("reofferStopEvent: $event, queueSize=${stopEvents.size}")
        stopEvents.offer(event)
    }

    /**
     * Queries LLDB for the current process state via `process status`.
     * If the output contains "Process exited", sets [debuggeeExited]
     * and offers a [StopEvent.ProcessExited] into the queue.
     *
     * This is a watchdog mechanism for Windows where LLDB sometimes
     * fails to spontaneously output the "Process exited" line after
     * the debuggee terminates, leaving the event loop spinning.
     *
     * @return `true` if the process has exited.
     */
    fun queryProcessExited(): Boolean {
        if (debuggeeExited || destroyed) return debuggeeExited
        val lines = sendCommandAndCollectOutput("process status", timeoutMs = 3_000)
        LOG.debug("queryProcessExited: process status output=$lines")
        // The onTextAvailable listener already parses "Process exited"
        // lines and queues ProcessExited events (even inside a response).
        // Check whether debuggeeExited was set as a side effect.
        if (debuggeeExited) {
            LOG.debug("queryProcessExited: detected exit via process status")
            return true
        }
        lines.firstNotNullOfOrNull(::parseStopEvent)?.let { event ->
            LOG.debug("queryProcessExited: re-offering process-status stop event=$event")
            stopEvents.offer(event)
        }
        return false
    }

    /**
     * Drains all pending stop events from the queue without blocking.
     *
     * This is useful before starting the event loop in scenarios where
     * LLDB has already produced stop events (e.g. the initial
     * `stop reason = trace` from a `process connect` to WAMR) that
     * should not be processed as user-visible stops.
     */
    fun drainPendingStopEvents() {
        var count = 0
        while (stopEvents.poll() != null) {
            count++
        }
        if (count > 0) {
            LOG.debug("Drained $count pending stop event(s)")
        }
    }

    fun destroy() {
        LOG.debug("LLDB destroy() called, debuggeeExited=$debuggeeExited, isRunning=$isRunning")
        try {
            writer.write("process kill")
            writer.newLine()
            writer.write("quit")
            writer.newLine()
            writer.flush()
        } catch (_: Exception) {
            // Ignore — process may already be dead
        }
        destroyed = true
    }

    /**
     * Parses an LLDB output line looking for a stop reason.
     *
     * Delegates to the shared [parseLldbStopEvent] parser and
     * converts the result to the local [StopEvent] type.
     */
    private fun parseStopEvent(line: String): StopEvent? {
        val shared = parseLldbStopEvent(line) ?: return null
        isRunning = false
        return when (shared) {
            is LldbStopEvent.BreakpointHit -> StopEvent.BreakpointHit(
                threadId = shared.threadId,
                breakpointId = shared.breakpointId,
            )
            is LldbStopEvent.Stepped -> StopEvent.Stepped
            is LldbStopEvent.StoppedAtEntry -> StopEvent.StoppedAtEntry
            is LldbStopEvent.ProcessExited -> {
                debuggeeExited = true
                LOG.debug("Debuggee process exited (code=${shared.exitCode}), will drop further commands")
                StopEvent.ProcessExited(shared.exitCode)
            }
        }
    }

    sealed class StopEvent {
        data class BreakpointHit(val threadId: Int, val breakpointId: String) : StopEvent()
        data object Stepped : StopEvent()
        data object StoppedAtEntry : StopEvent()
        data class ProcessExited(val exitCode: Int) : StopEvent()
    }

    companion object {
        private val LOG = Logger.getInstance(LldbCommunicator::class.java)

        /**
         * Prefix that LLDB uses when echoing commands back in output.
         * Lines starting with this prefix are command echoes and should
         * be filtered out of collected command responses.
         */
        private const val LLDB_PROMPT_PREFIX = "(lldb) "
    }
}
