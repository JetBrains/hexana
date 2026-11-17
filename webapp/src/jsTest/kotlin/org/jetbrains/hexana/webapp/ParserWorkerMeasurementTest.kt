package org.jetbrains.hexana.webapp

import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.w3c.fetch.RequestInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MEASUREMENT_TIMEOUT_MS = 60_000
private const val TIMEOUT_PROBE_MS = 25
private const val PARALLEL_TRIAL_COUNT = 3
private val measurementFetchInit: RequestInit = js("({})")

class ParserWorkerMeasurementTest {
    @Test
    fun collectChromeSpikeMeasurements(): Promise<Unit> = Promise { resolve, reject ->
        val memoryBefore = usedJsHeapBytes()
        fetchFixture("addition.wasm").then({ addition ->
            repeatMeasurements(2) { addition.slice(0) }.then({
                repeatMeasurements(10) { addition.slice(0) }.then({ additionSamples ->
                    fetchFixture("faustide.wasm").then({ faustide ->
                        measure(faustide.slice(0)).then({ faustideSample ->
                            fetchFixture("mpp-demo.wasm").then({ mppDemo ->
                                measure(mppDemo.slice(0)).then({ mppSample ->
                                    repeatParallelMeasurements(faustide, 1, PARALLEL_TRIAL_COUNT).then({ concurrency1 ->
                                        repeatParallelMeasurements(faustide, 2, PARALLEL_TRIAL_COUNT).then({ concurrency2 ->
                                            repeatParallelMeasurements(faustide, 4, PARALLEL_TRIAL_COUNT).then({ concurrency4 ->
                                                repeatTimeoutMeasurements(10).then({ overshoots ->
                                                    measure(ArrayBuffer(52_428_800)).then({ synthetic ->
                                                        try {
                                                            assertFalse(synthetic.success)
                                                            assertEquals("PARSE_FAILED", synthetic.errorCode)
                                                            assertEquals(0, synthetic.senderBytesAfterPost)
                                                            assertTrue(mppSample.success)
                                                            val output = js("({})")
                                                            output.userAgent = window.navigator.userAgent
                                                            output.hardwareConcurrency = window.navigator.asDynamic().hardwareConcurrency
                                                            output.memoryBefore = memoryBefore
                                                            output.memoryAfter = usedJsHeapBytes()
                                                            output.addition = samplesJson(additionSamples)
                                                            output.faustide = sampleJson(faustideSample)
                                                            output.mppDemo = sampleJson(mppSample)
                                                            output.concurrency1 = parallelSamplesJson(concurrency1)
                                                            output.concurrency2 = parallelSamplesJson(concurrency2)
                                                            output.concurrency4 = parallelSamplesJson(concurrency4)
                                                            output.timeoutOvershootMs = overshoots.toTypedArray()
                                                            output.synthetic50MiB = sampleJson(synthetic)
                                                            output.successDtoJsonBytes = successDtoJsonBytes(additionSamples.first())
                                                            println(
                                                                "PARSER_WORKER_MEASUREMENTS=" + js("JSON.stringify(output)")
                                                            )
                                                            resolve(Unit)
                                                        } catch (failure: Throwable) {
                                                            reject(failure)
                                                        }
                                                    }, reject)
                                                }, reject)
                                            }, reject)
                                        }, reject)
                                    }, reject)
                                }, reject)
                            }, reject)
                        }, reject)
                    }, reject)
                }, reject)
            }, reject)
        }, reject)
    }
}

private data class WorkerSample(
    val inputBytes: Int,
    val senderBytesAfterPost: Int,
    val success: Boolean,
    val errorCode: String?,
    val parseMs: Double?,
    val endToEndMs: Double,
)

private data class ParallelSample(
    val concurrency: Int,
    val wallMs: Double,
    val throughputPerSecond: Double,
    val requestLatenciesMs: List<Double>,
    val maxHeartbeatDelayMs: Double,
)

private fun measure(input: ArrayBuffer): Promise<WorkerSample> = Promise { resolve, reject ->
    val inputBytes = input.byteLength
    val startedAt = window.performance.now()
    var controller: ParserWorkerController? = null
    controller = ParserWorkerController(
        workerFactory = browserParserWorkerFactory,
        scheduler = browserParserWorkerScheduler,
        onStateChanged = { state ->
            when (state) {
                is ParserWorkerState.Parsed -> resolve(
                    WorkerSample(
                        inputBytes,
                        input.byteLength,
                        true,
                        null,
                        state.summary.elapsedMs,
                        window.performance.now() - startedAt,
                    )
                )
                is ParserWorkerState.Failed -> resolve(
                    WorkerSample(
                        inputBytes,
                        input.byteLength,
                        false,
                        state.code,
                        null,
                        window.performance.now() - startedAt,
                    )
                )
                ParserWorkerState.TimedOut -> reject(Throwable("Measurement timed out for $inputBytes bytes."))
                else -> Unit
            }
        },
    )
    controller.parse(input, MEASUREMENT_TIMEOUT_MS)
}

private fun repeatMeasurements(count: Int, buffer: () -> ArrayBuffer): Promise<List<WorkerSample>> =
    Promise { resolve, reject ->
        val samples = mutableListOf<WorkerSample>()
        fun next() {
            if (samples.size == count) {
                resolve(samples)
                return
            }
            measure(buffer()).then(
                onFulfilled = { sample -> samples += sample; next() },
                onRejected = { failure -> reject(failure) },
            )
        }
        next()
    }

private fun measureParallel(source: ArrayBuffer, concurrency: Int): Promise<ParallelSample> =
    Promise { resolve, reject ->
        val startedAt = window.performance.now()
        val frameDelays = mutableListOf<Double>()
        var lastFrameAt = startedAt
        var running = true
        fun frame(timestamp: Double) {
            frameDelays += timestamp - lastFrameAt
            lastFrameAt = timestamp
            if (running) window.requestAnimationFrame(::frame)
        }
        window.requestAnimationFrame(::frame)
        val promises = Array(concurrency) { measure(source.slice(0)) }
        Promise.all(promises).then(
            onFulfilled = { samples ->
                running = false
                val wallMs = window.performance.now() - startedAt
                window.requestAnimationFrame {
                    resolve(
                        ParallelSample(
                            concurrency,
                            wallMs,
                            concurrency * 1_000.0 / wallMs,
                            samples.map { it.endToEndMs },
                            frameDelays.maxOrNull() ?: 0.0,
                        )
                    )
                }
            },
            onRejected = { failure -> running = false; reject(failure) },
        )
    }

private fun repeatParallelMeasurements(
    source: ArrayBuffer,
    concurrency: Int,
    count: Int,
): Promise<List<ParallelSample>> = Promise { resolve, reject ->
    val samples = mutableListOf<ParallelSample>()
    fun next() {
        if (samples.size == count) {
            resolve(samples)
            return
        }
        measureParallel(source, concurrency).then(
            onFulfilled = { sample -> samples += sample; next() },
            onRejected = reject,
        )
    }
    next()
}

private fun repeatTimeoutMeasurements(count: Int): Promise<List<Double>> {
    val blob = js("new Blob(['self.onmessage = function () {}'], { type: 'text/javascript' })")
    val workerUrl = window.asDynamic().URL.createObjectURL(blob).unsafeCast<String>()
    return Promise { resolve, reject ->
        val samples = mutableListOf<Double>()
        fun next() {
            if (samples.size == count) {
                window.asDynamic().URL.revokeObjectURL(workerUrl)
                resolve(samples)
                return
            }
            val startedAt = window.performance.now()
            val controller = ParserWorkerController(
                workerFactory = ParserWorkerFactory { BrowserParserWorkerPort(workerUrl) },
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state ->
                    when (state) {
                        ParserWorkerState.TimedOut -> {
                            samples += window.performance.now() - startedAt - TIMEOUT_PROBE_MS
                            next()
                        }
                        is ParserWorkerState.Failed -> {
                            window.asDynamic().URL.revokeObjectURL(workerUrl)
                            reject(Throwable("Timeout probe failed early: ${state.code}"))
                        }
                        else -> Unit
                    }
                },
            )
            controller.parse(ArrayBuffer(1), TIMEOUT_PROBE_MS)
        }
        next()
    }
}

private fun fetchFixture(name: String): Promise<ArrayBuffer> = Promise { resolve, reject ->
    window.fetch("/kotlin/parser-worker-fixtures/$name", measurementFetchInit).then(
        onFulfilled = { response ->
            if (!response.ok) {
                reject(Throwable("Could not fetch measurement fixture $name: ${response.status}"))
            } else {
                response.arrayBuffer().then(resolve, reject)
            }
        },
        onRejected = reject,
    )
    }

private fun samplesJson(samples: List<WorkerSample>): dynamic =
    samples.map(::sampleJson).toTypedArray()

private fun sampleJson(sample: WorkerSample): dynamic {
    val value = js("({})")
    value.inputBytes = sample.inputBytes
    value.senderBytesAfterPost = sample.senderBytesAfterPost
    value.success = sample.success
    value.errorCode = sample.errorCode
    value.parseMs = sample.parseMs
    value.endToEndMs = sample.endToEndMs
    return value
}

private fun parallelSamplesJson(samples: List<ParallelSample>): dynamic =
    samples.map(::parallelJson).toTypedArray()

private fun parallelJson(sample: ParallelSample): dynamic {
    val value = js("({})")
    value.concurrency = sample.concurrency
    value.wallMs = sample.wallMs
    value.throughputPerSecond = sample.throughputPerSecond
    value.requestLatenciesMs = sample.requestLatenciesMs.toTypedArray()
    value.maxHeartbeatDelayMs = sample.maxHeartbeatDelayMs
    return value
}

private fun successDtoJsonBytes(sample: WorkerSample): Int {
    val base = representativePresentation(sample.inputBytes).copy(
        capabilities = listOf("summary"),
        ranges = emptyList(),
        tables = emptyList(),
    )
    val presentation = base.copy(summary = base.summary.copy(elapsedMs = sample.parseMs ?: 0.0))
    return utf8WireBytes(
        encodeBoundedParserWorkerSuccess("parser-worker-1", presentation),
    )
}

private fun usedJsHeapBytes(): Double? {
    val memory = window.performance.asDynamic().memory ?: return null
    return memory.usedJSHeapSize as? Double
}
