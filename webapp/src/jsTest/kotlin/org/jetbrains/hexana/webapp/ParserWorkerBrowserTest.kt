package org.jetbrains.hexana.webapp

import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.fetch.RequestInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val REAL_WORKER_TEST_TIMEOUT_MS = 10_000

class ParserWorkerBrowserTest {
    @Test
    fun workerTransferDetachesOnlyTheWorkerCopyAndLeavesUiBytesUsable(): Promise<Unit> =
        Promise { resolve, reject ->
            val uiBytes = Int8Array(additionFixtureBuffer()).unsafeCast<ByteArray>()
            val workerBuffer = copyForParserWorker(uiBytes)
            var guard = 0
            val controller = ParserWorkerController(
                workerFactory = browserParserWorkerFactory,
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state ->
                    when (state) {
                    is ParserWorkerState.Parsed -> {
                        window.clearTimeout(guard)
                        try {
                            assertEquals(0, workerBuffer.byteLength)
                            assertEquals(55, uiBytes.size)
                            assertEquals(0, uiBytes[0].toInt() and 0xff)
                            assertEquals(0x6d, uiBytes[3].toInt() and 0xff)
                            resolve(Unit)
                        } catch (failure: Throwable) {
                            reject(failure)
                        }
                    }
                    is ParserWorkerState.Failed -> { window.clearTimeout(guard); reject(Throwable("Worker copy proof failed: ${state.code}")) }
                    ParserWorkerState.TimedOut -> { window.clearTimeout(guard); reject(Throwable("Worker copy proof timed out.")) }
                    else -> Unit
                    }
                },
            )
            guard = window.setTimeout({
                controller.dispose()
                reject(Throwable("Worker copy proof did not complete."))
            }, REAL_WORKER_TEST_TIMEOUT_MS)
            controller.parse(workerBuffer, 5_000)
        }

    @Test
    fun realWorkerProvesDedicatedScopeParsesAdditionAndTransfersOwnership(): Promise<Unit> =
        Promise { resolve, reject ->
            val input = additionFixtureBuffer()
            val beforeTransfer = input.byteLength
            var controller: ParserWorkerController? = null
            val guard = window.setTimeout({
                controller?.dispose()
                reject(Throwable("Real parser worker did not answer within the browser-test deadline."))
            }, REAL_WORKER_TEST_TIMEOUT_MS)
            controller = ParserWorkerController(
                workerFactory = browserParserWorkerFactory,
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state ->
                    when (state) {
                        is ParserWorkerState.Parsed -> {
                            window.clearTimeout(guard)
                            try {
                                assertFalse(isDedicatedWorkerScope(window.asDynamic()))
                                assertEquals(55, beforeTransfer)
                                assertEquals(0, input.byteLength)
                                assertEquals("DedicatedWorkerGlobalScope", state.summary.scopeMarker)
                                assertTrue(state.summary.documentAbsent)
                                assertEquals(55, state.summary.inputBytes)
                                assertEquals(5, state.summary.sectionCount)
                                assertEquals(1, state.summary.importCount)
                                assertEquals(1, state.summary.definedFunctionCount)
                                assertEquals(1, state.summary.codeBodyCount)
                                assertEquals(0, state.presentation.summary.exportCount)
                                assertEquals(
                                    listOf("summary", "structural_ranges", "tables", "diagnostics"),
                                    state.presentation.capabilities,
                                )
                                assertTrue(state.presentation.ranges.isNotEmpty())
                                assertEquals(
                                    listOf("Imports", "Functions"),
                                    state.presentation.tables.map { it.id },
                                )
                                assertEquals(
                                    listOf("Top", "Garbage", "Wat", "WAT (virtualized)"),
                                    state.presentation.legacyTabs,
                                )
                                assertTrue(state.presentation.diagnostics.isEmpty())
                                resolve(Unit)
                            } catch (failure: Throwable) {
                                reject(failure)
                            }
                        }
                        is ParserWorkerState.Failed -> {
                            window.clearTimeout(guard)
                            reject(Throwable("Real parser worker failed safely: ${state.code}: ${state.message}"))
                        }
                        ParserWorkerState.TimedOut -> {
                            window.clearTimeout(guard)
                            reject(Throwable("Real parser worker timed out."))
                        }
                        else -> Unit
                    }
                },
            )
            controller.parse(input, 5_000)
        }

    @Test
    fun realWorkerParsesComponentFixtureAndReturnsDependencyGraph(): Promise<Unit> =
        Promise { resolve, reject ->
            window.fetch("/kotlin/parser-worker-fixtures/composed.wasm", js("({})").unsafeCast<RequestInit>()).then(
                onFulfilled = { fixture ->
                    if (!fixture.ok) {
                        reject(Throwable("Could not fetch the component worker fixture."))
                        return@then
                    }
                    fixture.arrayBuffer().then(
                        onFulfilled = { input ->
                            var controller: ParserWorkerController? = null
                            val guard = window.setTimeout({
                                controller?.dispose()
                                reject(Throwable("Component parser worker did not answer within the browser-test deadline."))
                            }, REAL_WORKER_TEST_TIMEOUT_MS)
                            controller = ParserWorkerController(
                                workerFactory = browserParserWorkerFactory,
                                scheduler = browserParserWorkerScheduler,
                                onStateChanged = { state ->
                                    when (state) {
                                        is ParserWorkerState.Parsed -> {
                                            window.clearTimeout(guard)
                                            try {
                                                assertEquals("wasm_component", state.presentation.format)
                                                assertEquals("DedicatedWorkerGlobalScope", state.summary.scopeMarker)
                                                assertTrue(state.summary.documentAbsent)
                                                assertTrue("component_dependency_graph" in state.presentation.capabilities)
                                                assertTrue(state.presentation.ranges.isNotEmpty())
                                                assertTrue(state.presentation.tables.isNotEmpty())
                                                assertTrue(state.presentation.tables.all { table ->
                                                    table.id in setOf("Imports", "Exports", "Modules")
                                                })
                                                val graph = checkNotNull(state.presentation.componentGraph)
                                                assertTrue(graph.nodes.isNotEmpty())
                                                assertTrue(graph.edges.all { edge ->
                                                    graph.nodes.any { it.id == edge.fromId } &&
                                                        graph.nodes.any { it.id == edge.toId }
                                                })
                                                assertEquals(
                                                    graph.nodes.any { it.kind == "nested_component" },
                                                    "Components" in state.presentation.legacyTabs,
                                                )
                                                assertEquals(
                                                    listOf("Wat", "WAT (virtualized)"),
                                                    state.presentation.legacyTabs.takeLast(2),
                                                )
                                                resolve(Unit)
                                            } catch (failure: Throwable) {
                                                reject(failure)
                                            }
                                        }
                                        is ParserWorkerState.Failed -> {
                                            window.clearTimeout(guard)
                                            reject(Throwable("Component parser worker failed safely: ${state.code}: ${state.message}"))
                                        }
                                        ParserWorkerState.TimedOut -> {
                                            window.clearTimeout(guard)
                                            reject(Throwable("Component parser worker timed out."))
                                        }
                                        else -> Unit
                                    }
                                },
                            )
                            controller.parse(input, 5_000)
                        },
                        onRejected = reject,
                    )
                },
                onRejected = reject,
            )
        }

    @Test
    fun realWorkerReturnsFixedSafeErrorForInvalidInput(): Promise<Unit> =
        Promise { resolve, reject ->
            val controller = ParserWorkerController(
                workerFactory = browserParserWorkerFactory,
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state ->
                    if (state is ParserWorkerState.Failed) {
                        try {
                            assertEquals("PARSE_FAILED", state.code)
                            assertTrue(state.message.length <= 160)
                            assertFalse("exception" in state.message.lowercase())
                            resolve(Unit)
                        } catch (failure: Throwable) {
                            reject(failure)
                        }
                    }
                },
            )
            controller.parse(ArrayBuffer(32), 5_000)
        }

    @Test
    fun rawRealWorkerRejectsMalformedWireRequestWithFixedSafeError(): Promise<Unit> =
        Promise { resolve, reject ->
            val worker = BrowserParserWorkerPort()
            val input = ArrayBuffer(1)
            var finished = false
            var guard = 0

            fun cleanup() {
                if (finished) return
                finished = true
                window.clearTimeout(guard)
                worker.clearHandlers()
                worker.terminate()
            }

            guard = window.setTimeout({
                cleanup()
                reject(Throwable("Raw malformed worker request did not answer within the browser-test deadline."))
            }, REAL_WORKER_TEST_TIMEOUT_MS)
            worker.setHandlers(
                onMessage = { message ->
                    cleanup()
                    try {
                        assertEquals(PARSER_WORKER_PROTOCOL_VERSION, message.protocolVersion)
                        assertEquals("error", message.kind)
                        assertEquals("raw-malformed-request", message.requestId)
                        assertEquals("INVALID_REQUEST", message.code)
                        assertEquals("Parser worker request is invalid.", message.message)
                        assertEquals(0, input.byteLength)
                        resolve(Unit)
                    } catch (failure: Throwable) {
                        reject(failure)
                    }
                },
                onError = {
                    cleanup()
                    reject(Throwable("Raw malformed worker request raised a worker error."))
                },
                onMessageError = {
                    cleanup()
                    reject(Throwable("Raw malformed worker response could not be decoded."))
                },
            )

            val request = js("({})")
            request.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
            request.kind = "malformed-kind"
            request.requestId = "raw-malformed-request"
            request.buffer = input
            worker.postParse(request, input)
        }

    @Test
    fun rawRealWorkerBoundsTheErrorForAnOversizedRequestId(): Promise<Unit> =
        Promise { resolve, reject ->
            val worker = BrowserParserWorkerPort()
            val input = ArrayBuffer(1)
            var finished = false
            var guard = 0

            fun cleanup() {
                if (finished) return
                finished = true
                window.clearTimeout(guard)
                worker.clearHandlers()
                worker.terminate()
            }

            guard = window.setTimeout({
                cleanup()
                reject(Throwable("Raw oversized request-ID worker request did not answer within the browser-test deadline."))
            }, REAL_WORKER_TEST_TIMEOUT_MS)
            worker.setHandlers(
                onMessage = { message ->
                    cleanup()
                    try {
                        assertEquals(PARSER_WORKER_PROTOCOL_VERSION, message.protocolVersion)
                        assertEquals("error", message.kind)
                        assertEquals("", message.requestId)
                        assertEquals("INVALID_REQUEST", message.code)
                        assertEquals("Parser worker request is invalid.", message.message)
                        assertTrue(utf8WireBytes(message) <= MAX_PARSER_WORKER_WIRE_BYTES)
                        resolve(Unit)
                    } catch (failure: Throwable) {
                        reject(failure)
                    }
                },
                onError = {
                    cleanup()
                    reject(Throwable("Raw oversized request-ID worker request raised a worker error."))
                },
                onMessageError = {
                    cleanup()
                    reject(Throwable("Raw oversized request-ID worker response could not be decoded."))
                },
            )

            val request = js("({})")
            request.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
            request.kind = "parse"
            request.requestId = "x".repeat(MAX_PARSER_WORKER_REQUEST_ID_CHARS + 1)
            request.buffer = input
            worker.postParse(request, input)
        }

    @Test
    fun nonresponsiveWorkerTimesOutByTerminationAndNextRealWorkerRecovers(): Promise<Unit> =
        Promise { resolve, reject ->
            val blob = js("new Blob(['self.onmessage = function () {}'], { type: 'text/javascript' })")
            val workerUrl = window.asDynamic().URL.createObjectURL(blob).unsafeCast<String>()
            val nonresponsiveWorker = TrackingParserWorkerPort(BrowserParserWorkerPort(workerUrl))
            val workers = ArrayDeque<ParserWorkerFactory>().apply {
                add(ParserWorkerFactory { nonresponsiveWorker })
                add(browserParserWorkerFactory)
            }
            val startedAt = window.performance.now()
            var timeoutOvershootMs = -1.0
            var controller: ParserWorkerController? = null
            val guard = window.setTimeout({
                controller?.dispose()
                window.asDynamic().URL.revokeObjectURL(workerUrl)
                reject(Throwable("Timeout recovery test exceeded its browser-test deadline."))
            }, REAL_WORKER_TEST_TIMEOUT_MS)
            controller = ParserWorkerController(
                workerFactory = ParserWorkerFactory { workers.removeFirst().create() },
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state ->
                    when (state) {
                        ParserWorkerState.TimedOut -> {
                            timeoutOvershootMs = window.performance.now() - startedAt - 25.0
                            controller?.parse(additionFixtureBuffer(), 5_000)
                        }
                        is ParserWorkerState.Parsed -> {
                            window.clearTimeout(guard)
                            window.asDynamic().URL.revokeObjectURL(workerUrl)
                            try {
                                assertTrue(timeoutOvershootMs >= 0.0)
                                assertEquals(1, nonresponsiveWorker.terminateCalls)
                                assertEquals(55, state.summary.inputBytes)
                                resolve(Unit)
                            } catch (failure: Throwable) {
                                reject(failure)
                            }
                        }
                        is ParserWorkerState.Failed -> {
                            window.clearTimeout(guard)
                            window.asDynamic().URL.revokeObjectURL(workerUrl)
                            reject(Throwable("Timeout recovery failed: ${state.code}: ${state.message}"))
                        }
                        else -> Unit
                    }
                },
            )
            controller.parse(ArrayBuffer(1), 25)
        }
}

internal fun additionFixtureBuffer(): ArrayBuffer {
    val bytes = intArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02,
        0x60, 0x01, 0x7f, 0x00, 0x60, 0x00, 0x00, 0x02, 0x0f, 0x01, 0x07,
        0x63, 0x6f, 0x6e, 0x73, 0x6f, 0x6c, 0x65, 0x03, 0x6c, 0x6f, 0x67,
        0x00, 0x00, 0x03, 0x02, 0x01, 0x01, 0x08, 0x01, 0x01, 0x0a, 0x0b,
        0x01, 0x09, 0x00, 0x41, 0x0a, 0x41, 0x03, 0x6a, 0x10, 0x00, 0x0b,
    )
    return Uint8Array(bytes.map(Int::toDouble).toTypedArray()).buffer
}

internal class TrackingParserWorkerPort(
    private val delegate: ParserWorkerPort,
) : ParserWorkerPort by delegate {
    var terminateCalls: Int = 0
        private set

    override fun terminate() {
        terminateCalls++
        delegate.terminate()
    }
}
