package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.khronos.webgl.ArrayBuffer
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val HARNESS_STATE_TIMEOUT_MS = 5_000.0
private const val HARNESS_TEST_TIMEOUT_MS = 10_000
private const val NONRESPONSIVE_WORKER_TIMEOUT_MS = 100
private var harnessRootSequence = 0

class ParserWorkerRecoveryHarnessTest {
    @Test
    fun composeHarnessRendersParsingTimeoutAndSuccessfulRetryWithoutSleeps(): Promise<Unit> =
        Promise { resolve, reject ->
            val root = document.createElement("div")
            root.id = "parser-worker-harness-${harnessRootSequence++}"
            document.body?.appendChild(root)
            val state = mutableStateOf<ParserWorkerState>(ParserWorkerState.Ready)
            val blob = js("new Blob(['self.onmessage = function () {}'], { type: 'text/javascript' })")
            val workerUrl = window.asDynamic().URL.createObjectURL(blob).unsafeCast<String>()
            val nonresponsiveWorker = TrackingParserWorkerPort(BrowserParserWorkerPort(workerUrl))
            val workers = ArrayDeque<ParserWorkerFactory>().apply {
                add(ParserWorkerFactory { nonresponsiveWorker })
                add(browserParserWorkerFactory)
            }
            val composition: Composition = renderComposable(rootElementId = root.id) {
                ParserWorkerRecoveryHarness(state.value)
            }
            var controller: ParserWorkerController? = null
            var cleaned = false
            var guard = 0

            fun cleanup() {
                if (cleaned) return
                cleaned = true
                window.clearTimeout(guard)
                controller?.dispose()
                window.asDynamic().URL.revokeObjectURL(workerUrl)
                composition.dispose()
                root.parentNode?.removeChild(root)
            }

            fun fail(failure: Throwable) {
                cleanup()
                reject(failure)
            }

            fun expect(expected: String, next: () -> Unit) {
                val deadline = window.performance.now() + HARNESS_STATE_TIMEOUT_MS
                fun poll() {
                    if (cleaned) return
                    try {
                        val actual = root.querySelector("[data-parser-worker-state]")
                            ?.getAttribute("data-parser-worker-state")
                        if (actual == expected) {
                            assertEquals(expected, root.textContent)
                            next()
                        } else if (window.performance.now() >= deadline) {
                            fail(Throwable("Timed out waiting for harness state $expected; actual=$actual"))
                        } else {
                            window.requestAnimationFrame { poll() }
                        }
                    } catch (failure: Throwable) {
                        fail(failure)
                    }
                }
                window.requestAnimationFrame { poll() }
            }

            guard = window.setTimeout({
                fail(Throwable("Controller-driven Compose recovery test exceeded its deadline."))
            }, HARNESS_TEST_TIMEOUT_MS)
            controller = ParserWorkerController(
                workerFactory = ParserWorkerFactory { workers.removeFirst().create() },
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state.value = it },
            )
            controller?.parse(ArrayBuffer(1), NONRESPONSIVE_WORKER_TIMEOUT_MS)
            expect("Parsing") {
                expect("Timed out") {
                    val retryInput = additionFixtureBuffer()
                    controller?.parse(retryInput, 5_000)
                    expect("Parsed") {
                        val parsed = assertIs<ParserWorkerState.Parsed>(state.value)
                        assertEquals(55, parsed.summary.inputBytes)
                        assertEquals(0, retryInput.byteLength)
                        assertEquals(1, nonresponsiveWorker.terminateCalls)
                        cleanup()
                        resolve(Unit)
                    }
                }
            }
        }
}

@Composable
private fun ParserWorkerRecoveryHarness(state: ParserWorkerState) {
    val label = when (state) {
        ParserWorkerState.Ready -> "Ready"
        is ParserWorkerState.Parsing -> "Parsing"
        ParserWorkerState.TimedOut -> "Timed out"
        is ParserWorkerState.Parsed -> "Parsed"
        is ParserWorkerState.Failed -> "Failed"
        ParserWorkerState.Cancelled -> "Cancelled"
    }
    Span({ attr("data-parser-worker-state", label) }) {
        Text(label)
    }
}
