package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.hexana.dwarf.DwarfTypeInfo
import org.jetbrains.hexana.shared.CdpSourceLocationResolver
import org.jetbrains.hexana.shared.DwarfMemoryDecoder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end demonstration of the Node.js wasm debugging pipeline against a
 * live `node --inspect-brk` session, using the production classes directly
 * ([CdpClient], [CdpSourceLocationResolver], [DwarfMemoryDecoder]) without the
 * XDebugger UI layer.
 *
 * The scenario mirrors what a user sees when debugging
 * `dwarf-memvars.wasm` (`make(5)`, built at -O0 so locals live in linear
 * memory; source and build command next to the fixture):
 *
 * 1. A breakpoint set via the DWARF line table (display line 11, `p.x += 1;`)
 *    is hit at the expected module-absolute offset.
 * 2. The pause exposes the operand stack in a `wasm-expression-stack` scope
 *    (the data source of the debugger's Wasm tab).
 * 3. The struct variable `p` is located via DWARF (`DW_OP_fbreg +4` relative
 *    to the frame-base wasm local), its bytes are read from `memories[0]`
 *    with `Debugger.evaluateOnCallFrame`, and decode to
 *    `Point { x: 5, y: 2, z: 3 }` — the pre-increment state.
 *
 * Skips (passes vacuously, with a message) when `node` is not on PATH.
 */
class CdpDebugIntegrationTest : UsefulTestCase() {

    private fun repoRoot(): File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "debugInfo/dwarf-fixtures").isDirectory }

    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS)
    } catch (_: Exception) {
        false
    }

    fun testStructReadFromLinearMemoryAtBreakpoint() {
        if (!nodeAvailable()) {
            println("CdpDebugIntegrationTest: node not on PATH — skipping")
            return
        }
        val root = repoRoot() ?: error("repo root not found")
        val wasm = File(root, "debugInfo/dwarf-fixtures/test-data/wasm32/dwarf-memvars.wasm")
        assertTrue("fixture must exist: $wasm", wasm.exists())

        val resolver = CdpSourceLocationResolver.create(wasm.readBytes())
            ?: error("resolver creation failed")
        // sourceToAllWasmOffsets takes 0-based editor lines: key 10 = display
        // line 11, `p.x += 1;`.
        val bpOffset = resolver.sourceToAllWasmOffsets("lib.rs", 10).firstOrNull()?.toInt()
            ?: error("no line-table offset for display line 11")
        val memVars = resolver.memoryVariablesAt(bpOffset.toULong())
        val pVar = memVars.firstOrNull { it.name == "p" } ?: error("DWARF variable 'p' not found")
        val fbLocalIdx = pVar.frameBaseLocalIndex ?: error("p must be frame-relative")
        assertTrue("frame base must be a wasm local", fbLocalIdx >= 0)

        val harness = """
            const fs=require('fs');
            const buf=fs.readFileSync(${jsString(wasm.absolutePath)});
            (async()=>{
              const mod=await WebAssembly.compile(buf);
              const instance=await WebAssembly.instantiate(mod,{});
              console.log('make(5) ->',instance.exports['make'](5));
            })();
        """.trimIndent()
        val process = ProcessBuilder("node", "--inspect-brk=0", "--eval", harness)
            .redirectErrorStream(false)
            .start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val wsUrl = process.errorStream.bufferedReader().lineSequence()
                .mapNotNull { Regex("ws://\\S+").find(it)?.value }
                .firstOrNull() ?: error("no ws:// url from node --inspect-brk")

            runBlocking {
                withTimeout(30_000) {
                    val cdp = CdpClient(scope)
                    cdp.connect(wsUrl)

                    val pauses = kotlinx.coroutines.channels.Channel<Map<String, Any?>>(Int.MAX_VALUE)
                    cdp.on("Debugger.paused") { params -> pauses.trySend(params) }
                    val wasmScriptId = CompletableDeferred<String>()
                    cdp.on("Debugger.scriptParsed") { params ->
                        val url = params["url"] as? String ?: return@on
                        val id = params["scriptId"] as? String ?: return@on
                        if (url.startsWith("wasm://")) wasmScriptId.complete(id)
                    }

                    cdp.send("Runtime.enable")
                    cdp.send("Debugger.enable")
                    cdp.send("Debugger.setInstrumentationBreakpoint", mapOf("instrumentation" to "beforeScriptExecution"))
                    cdp.send("Runtime.runIfWaitingForDebugger")

                    var bpSet = false
                    while (true) {
                        val pause = pauses.receive()
                        val reason = pause["reason"] as? String
                        @Suppress("UNCHECKED_CAST")
                        val frames = pause["callFrames"] as? List<Map<String, Any?>> ?: emptyList()
                        val hit = (pause["hitBreakpoints"] as? List<*>)?.filterNotNull() ?: emptyList()

                        if (reason == "instrumentation") {
                            if (wasmScriptId.isCompleted && !bpSet) {
                                cdp.send("Debugger.setBreakpoint", mapOf(
                                    "location" to mapOf(
                                        "scriptId" to wasmScriptId.await(),
                                        "lineNumber" to 0,
                                        "columnNumber" to bpOffset,
                                    ),
                                ))
                                bpSet = true
                            }
                            scope.launch { runCatching { cdp.send("Debugger.resume") } }
                            continue
                        }
                        if (!bpSet || hit.isEmpty()) {
                            scope.launch { runCatching { cdp.send("Debugger.resume") } }
                            continue
                        }

                        // ---- 1. Breakpoint hit at the DWARF line-table offset ----
                        val frame = frames.first()
                        @Suppress("UNCHECKED_CAST")
                        val location = frame["location"] as? Map<String, Any?> ?: error("no location")
                        assertEquals("pause offset", bpOffset, (location["columnNumber"] as Number).toInt())

                        @Suppress("UNCHECKED_CAST")
                        val scopeChain = frame["scopeChain"] as? List<Map<String, Any?>> ?: error("no scopeChain")

                        // ---- 2. The operand stack scope exists (Wasm tab data source) ----
                        assertTrue(
                            "expected a wasm-expression-stack scope, got ${scopeChain.map { it["type"] }}",
                            scopeChain.any { it["type"] == "wasm-expression-stack" },
                        )

                        // ---- 3. Read the struct from linear memory ----
                        val localScope = scopeChain.first { it["type"] == "local" }
                        @Suppress("UNCHECKED_CAST")
                        val localObjectId = (localScope["object"] as Map<String, Any?>)["objectId"] as String
                        val frameBase = wasmLocalValue(cdp, localObjectId, fbLocalIdx.toInt())
                            ?: error("frame-base local \$var$fbLocalIdx not resolvable")

                        val address = frameBase + (pVar.fbregOffset ?: 0L)
                        val callFrameId = frame["callFrameId"] as String
                        val hex = readMemoryHex(cdp, callFrameId, address, pVar.byteSize)
                        assertEquals("slab size", pVar.byteSize * 2, hex.length)

                        val bytes = ByteArray(pVar.byteSize) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                        val rendered = DwarfMemoryDecoder.decode(bytes, 0, pVar.type)
                            ?: error("decode failed for ${pVar.type}")

                        val struct = pVar.type as? DwarfTypeInfo.Struct ?: error("p must be a struct")
                        assertEquals("Point", struct.name)
                        val members = rendered.children.associate { it.name to it.value.summary }
                        assertEquals("pre-increment x", "5", members["x"])
                        assertEquals("y", "2", members["y"])
                        assertEquals("z", "3", members["z"])

                        runCatching { cdp.send("Debugger.resume") }
                        break
                    }
                    cdp.close()
                }
            }
        } finally {
            scope.cancel()
            process.destroyForcibly()
        }
    }

    /** Resolves the numeric value of wasm local [index] (`$var<index>`) from the local scope. */
    private suspend fun wasmLocalValue(cdp: CdpClient, localScopeObjectId: String, index: Int): Long? {
        @Suppress("UNCHECKED_CAST")
        val props = cdp.send("Runtime.getProperties", mapOf(
            "objectId" to localScopeObjectId, "ownProperties" to true,
        ))["result"] as? List<Map<String, Any?>> ?: return null
        val local = props.firstOrNull { it["name"] == "\$var$index" } ?: return null
        @Suppress("UNCHECKED_CAST")
        val objId = (local["value"] as? Map<String, Any?>)?.get("objectId") as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val inner = cdp.send("Runtime.getProperties", mapOf(
            "objectId" to objId, "ownProperties" to true,
        ))["result"] as? List<Map<String, Any?>> ?: return null
        @Suppress("UNCHECKED_CAST")
        val valueProp = inner.firstOrNull { it["name"] == "value" }?.get("value") as? Map<String, Any?>
        return (valueProp?.get("value") as? Number)?.toLong()
    }

    /** Reads [size] bytes at [address] from `memories[0]`, hex-encoded — the plugin's read expression. */
    private suspend fun readMemoryHex(cdp: CdpClient, callFrameId: String, address: Long, size: Int): String {
        val expr = """(function(){
            var mem = memories[0].buffer;
            var arr = new Uint8Array(mem, $address, $size);
            var hex = '';
            for(var i=0;i<arr.length;i++){var b=arr[i].toString(16);hex+=(b.length<2?'0'+b:b);}
            return hex;
        })()"""
        @Suppress("UNCHECKED_CAST")
        val result = cdp.send("Debugger.evaluateOnCallFrame", mapOf(
            "callFrameId" to callFrameId,
            "expression" to expr,
            "returnByValue" to true,
        ))["result"] as? Map<String, Any?> ?: error("no evaluate result")
        return result["value"] as? String ?: error("no hex payload: $result")
    }

    private fun jsString(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
