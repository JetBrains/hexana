package org.jetbrains.hexana.webapp

import org.khronos.webgl.ArrayBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserWorkerControllerTest {
    @Test
    fun onlyCoreWasmRequiresTheDedicatedParserWorker() {
        assertTrue(requiresCoreParserWorker(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)))
        assertTrue(!requiresCoreParserWorker(byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 0, 0)))
        assertTrue(!requiresCoreParserWorker(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0, 0, 0, 0)))
    }

    @Test
    fun productionTimeoutIsExactlyFourMinutesWithoutWaiting() {
        val fixture = Fixture()

        fixture.controller.parse(ArrayBuffer(8), PARSER_WORKER_TIMEOUT_MS)

        assertEquals(240_000, PARSER_WORKER_TIMEOUT_MS)
        assertEquals(PARSER_WORKER_TIMEOUT_MS, fixture.scheduler.lastDelayMs)
    }

    @Test
    fun cancelTerminatesExactlyOnceAndIgnoresStaleReply() {
        val fixture = Fixture()
        fixture.controller.parse(ArrayBuffer(8), 100)
        val worker = fixture.workers.single()
        val staleHandler = worker.messageHandler

        fixture.controller.cancel()
        fixture.controller.cancel()
        staleHandler(success("parser-worker-1"))

        assertEquals(1, worker.terminateCalls)
        assertEquals(1, worker.clearCalls)
        assertEquals(ParserWorkerState.Cancelled, fixture.states.last())
    }

    @Test
    fun disposalTerminatesOutstandingWorkerAndInvalidatesItsCallback() {
        val fixture = Fixture()
        fixture.controller.parse(ArrayBuffer(8), 100)
        val worker = fixture.workers.single()
        val staleHandler = worker.messageHandler

        fixture.controller.dispose()
        staleHandler(success("parser-worker-1"))

        assertEquals(1, worker.terminateCalls)
        assertEquals(1, worker.clearCalls)
        assertTrue(fixture.states.last() is ParserWorkerState.Parsing)
    }

    @Test
    fun supersedeTerminatesOldWorkerAndAcceptsNewWorkerResult() {
        val fixture = Fixture()
        fixture.controller.parse(ArrayBuffer(8), 100)
        val oldWorker = fixture.workers.single()
        val staleHandler = oldWorker.messageHandler

        fixture.controller.parse(ArrayBuffer(16), 100)
        val newWorker = fixture.workers.last()
        staleHandler(success("parser-worker-1"))
        newWorker.fireMessage(success("parser-worker-2", inputBytes = 16))

        assertEquals(1, oldWorker.terminateCalls)
        assertEquals(1, newWorker.terminateCalls)
        assertEquals(16, assertIs<ParserWorkerState.Parsed>(fixture.states.last()).summary.inputBytes)
    }

    @Test
    fun timeoutInvalidatesThenTerminatesAndLateResultCannotWin() {
        val fixture = Fixture()
        fixture.controller.parse(ArrayBuffer(8), 100)
        val worker = fixture.workers.single()
        val staleHandler = worker.messageHandler

        fixture.scheduler.fireActive()
        staleHandler(success("parser-worker-1"))

        assertEquals(1, worker.terminateCalls)
        assertEquals(ParserWorkerState.TimedOut, fixture.states.last())
    }

    @Test
    fun malformedProtocolTerminatesWithSafeFailure() {
        val fixture = Fixture()
        fixture.controller.parse(ArrayBuffer(8), 100)
        val malformed = success("parser-worker-1")
        malformed.sectionCount = -1

        fixture.workers.single().fireMessage(malformed)

        val failure = assertIs<ParserWorkerState.Failed>(fixture.states.last())
        assertEquals("INVALID_RESPONSE", failure.code)
        assertEquals(1, fixture.workers.single().terminateCalls)
    }

    @Test
    fun safeWorkerErrorIsAcceptedWithoutExceptionDetails() {
        val fixture = Fixture()
        fixture.controller.parse(ArrayBuffer(8), 100)

        fixture.workers.single().fireMessage(workerErrorResponse("parser-worker-1"))

        val failure = assertIs<ParserWorkerState.Failed>(fixture.states.last())
        assertEquals("PARSE_FAILED", failure.code)
        assertEquals("The input could not be parsed safely.", failure.message)
        assertTrue("stack" !in failure.message.lowercase())
    }

    @Test
    fun workerErrorAndMessageErrorUseFixedSafeStates() {
        val workerError = Fixture()
        workerError.controller.parse(ArrayBuffer(8), 100)
        workerError.workers.single().fireError()
        assertEquals("WORKER_ERROR", assertIs<ParserWorkerState.Failed>(workerError.states.last()).code)

        val messageError = Fixture()
        messageError.controller.parse(ArrayBuffer(8), 100)
        messageError.workers.single().fireMessageError()
        assertEquals("MESSAGE_ERROR", assertIs<ParserWorkerState.Failed>(messageError.states.last()).code)
    }

    @Test
    fun postFailureAndFactoryFailureRemainRecoverable() {
        val postStates = mutableListOf<ParserWorkerState>()
        val postWorker = FakeParserWorker(true)
        val postController = ParserWorkerController(
            workerFactory = ParserWorkerFactory { postWorker },
            scheduler = FakeScheduler(),
            onStateChanged = postStates::add,
        )
        postController.parse(ArrayBuffer(8), 100)
        assertEquals("POST_FAILED", assertIs<ParserWorkerState.Failed>(postStates.last()).code)

        val failedFactory = Fixture(failFirstFactory = true)
        failedFactory.controller.parse(ArrayBuffer(8), 100)
        assertEquals("WORKER_START_FAILED", assertIs<ParserWorkerState.Failed>(failedFactory.states.last()).code)
        failedFactory.controller.parse(ArrayBuffer(8), 100)
        assertEquals(1, failedFactory.workers.size)
    }

    @Test
    fun wrongVersionRequestIdAndScopeMarkerAreRejected() {
        val fixture = Fixture()
        fixture.controller.parse(ArrayBuffer(8), 100)
        val wrongVersion = success("parser-worker-1")
        wrongVersion.protocolVersion = 2
        fixture.workers.single().fireMessage(wrongVersion)
        assertEquals("INVALID_RESPONSE", assertIs<ParserWorkerState.Failed>(fixture.states.last()).code)

        val wrongId = decodeParserWorkerResponse(success("other"), "expected")
        assertEquals(DecodedWorkerResponse.Malformed, wrongId)
        val wrongScope = success("expected")
        wrongScope.scopeMarker = "Window"
        assertEquals(DecodedWorkerResponse.Malformed, decodeParserWorkerResponse(wrongScope, "expected"))
    }
}

private class Fixture(failFirstFactory: Boolean = false) {
    val states = mutableListOf<ParserWorkerState>()
    val workers = mutableListOf<FakeParserWorker>()
    val scheduler = FakeScheduler()
    private var factoryCalls = 0
    val controller = ParserWorkerController(
        workerFactory = ParserWorkerFactory {
            factoryCalls++
            if (failFirstFactory && factoryCalls == 1) error("factory details")
            FakeParserWorker(false).also(workers::add)
        },
        scheduler = scheduler,
        onStateChanged = states::add,
    )
}

private class FakeParserWorker(private val throwOnPost: Boolean) : ParserWorkerPort {
    lateinit var messageHandler: (dynamic) -> Unit
    private lateinit var errorHandler: () -> Unit
    private lateinit var messageErrorHandler: () -> Unit
    var terminateCalls = 0
    var clearCalls = 0

    override fun setHandlers(
        onMessage: (dynamic) -> Unit,
        onError: () -> Unit,
        onMessageError: () -> Unit,
    ) {
        messageHandler = onMessage
        errorHandler = onError
        messageErrorHandler = onMessageError
    }

    override fun clearHandlers() {
        clearCalls++
    }

    override fun postParse(request: dynamic, buffer: ArrayBuffer) {
        if (throwOnPost) error("post details")
    }

    override fun terminate() {
        terminateCalls++
    }

    fun fireMessage(message: dynamic) = messageHandler(message)
    fun fireError() = errorHandler()
    fun fireMessageError() = messageErrorHandler()
}

private class FakeScheduler : ParserWorkerScheduler {
    private data class Scheduled(var cancelled: Boolean, val callback: () -> Unit)
    private val scheduled = mutableListOf<Scheduled>()
    var lastDelayMs: Int? = null
        private set

    override fun schedule(delayMs: Int, callback: () -> Unit): ParserWorkerTimer {
        lastDelayMs = delayMs
        val item = Scheduled(false, callback)
        scheduled += item
        return object : ParserWorkerTimer {
            override fun cancel() {
                item.cancelled = true
            }
        }
    }

    fun fireActive() {
        scheduled.last { !it.cancelled }.callback()
    }
}

private fun success(requestId: String, inputBytes: Int = 8): dynamic {
    val value = js("({})")
    value.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
    value.kind = "success"
    value.requestId = requestId
    value.scopeMarker = "DedicatedWorkerGlobalScope"
    value.documentAbsent = true
    value.inputBytes = inputBytes
    value.sectionCount = 5
    value.importCount = 1
    value.definedFunctionCount = 1
    value.codeBodyCount = 1
    value.elapsedMs = 1.0
    return value
}

private fun workerErrorResponse(requestId: String): dynamic {
    val value = js("({})")
    value.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
    value.kind = "error"
    value.requestId = requestId
    value.code = "PARSE_FAILED"
    value.message = "The input could not be parsed safely."
    return value
}
