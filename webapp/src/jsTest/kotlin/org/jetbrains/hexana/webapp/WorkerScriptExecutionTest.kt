package org.jetbrains.hexana.webapp

import org.khronos.webgl.ArrayBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerScriptExecutionTest {
    @Test
    fun cancelledScriptCannotAppendOutputOrErrorToTheNewerRun() {
        val first = TestScriptWorkerPort()
        val second = TestScriptWorkerPort()
        val workers = ArrayDeque(listOf(first, second))
        val execution = WorkerScriptExecution(
            bytes = byteArrayOf(0),
            fileName = "sample.wasm",
            workerFactory = WorkerScriptWorkerFactory { workers.removeFirst() },
        )
        val lines = mutableListOf<String>()
        val completions = mutableListOf<String?>()

        val cancelled = execution.start("print('old')", lines::add, completions::add)
        cancelled.cancel()
        execution.start("print('new')", lines::add, completions::add)

        first.done("stale failure")
        second.line("new")
        second.done(null)

        assertTrue(first.terminated)
        assertTrue(second.terminated)
        assertEquals(listOf("new"), lines)
        assertEquals(listOf<String?>(null), completions)
    }

    @Test
    fun malformedWorkerMessageFailsTheActiveRunWithoutForwardingIt() {
        val worker = TestScriptWorkerPort()
        val execution = WorkerScriptExecution(
            bytes = byteArrayOf(0),
            fileName = "sample.wasm",
            workerFactory = WorkerScriptWorkerFactory { worker },
        )
        val lines = mutableListOf<String>()
        val completions = mutableListOf<String?>()

        execution.start("print('ignored')", lines::add, completions::add)
        worker.emit(js("({ protocolVersion: 1, kind: 'script_line', requestId: 'script-worker-0', line: 'x'.repeat(4097) })"))

        assertTrue(worker.terminated)
        assertTrue(lines.isEmpty())
        assertEquals(listOf<String?>("The script worker response exceeded its output limit."), completions)
    }
}

private class TestScriptWorkerPort : WorkerScriptWorkerPort {
    private var onMessage: ((dynamic) -> Unit)? = null
    private var onError: (() -> Unit)? = null
    private var onMessageError: (() -> Unit)? = null
    var terminated: Boolean = false

    override fun setHandlers(onMessage: (dynamic) -> Unit, onError: () -> Unit, onMessageError: () -> Unit) {
        this.onMessage = onMessage
        this.onError = onError
        this.onMessageError = onMessageError
    }

    override fun clearHandlers() {
        onMessage = null
        onError = null
        onMessageError = null
    }

    override fun post(request: dynamic, buffer: ArrayBuffer) = Unit

    override fun terminate() {
        terminated = true
    }

    fun line(line: String) = emit(js("({ protocolVersion: 1, kind: 'script_line', requestId: 'script-worker-1', line: line })"))

    fun done(error: String?) {
        @Suppress("UNUSED_VARIABLE")
        val errorValue = error
        emit(js("({ protocolVersion: 1, kind: 'script_done', requestId: 'script-worker-1', error: errorValue })"))
    }

    fun emit(message: dynamic) {
        onMessage?.invoke(message)
    }
}
