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
                                assertEquals("Core Wasm", state.presentation.displayLabel)
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
                                                assertEquals("Component Model", state.presentation.displayLabel)
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
    fun realWorkerReturnsSuccessfulRawPresentationForGenericInput(): Promise<Unit> =
        Promise { resolve, reject ->
            val controller = ParserWorkerController(
                workerFactory = browserParserWorkerFactory,
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state ->
                    if (state is ParserWorkerState.Parsed) {
                        try {
                            assertEquals("generic", state.presentation.format)
                            assertEquals("Binary", state.presentation.displayLabel)
                            assertEquals(listOf("summary"), state.presentation.capabilities)
                            assertTrue(state.presentation.tables.isEmpty())
                            resolve(Unit)
                        } catch (failure: Throwable) {
                            reject(failure)
                        }
                    } else if (state is ParserWorkerState.Failed) {
                        reject(Throwable("Generic worker input failed: ${state.code}: ${state.message}"))
                    }
                },
            )
            controller.parse(ArrayBuffer(32), 5_000)
        }

    @Test
    fun realWorkerParsesAnApkDescriptorAndKeepsItsOpenActionDeferred(): Promise<Unit> =
        Promise { resolve, reject ->
            val input = apkFixtureBuffer()
            var controller: ParserWorkerController? = null
            val guard = window.setTimeout({
                controller?.dispose()
                reject(Throwable("APK parser worker did not answer within the browser-test deadline."))
            }, REAL_WORKER_TEST_TIMEOUT_MS)
            controller = ParserWorkerController(
                workerFactory = browserParserWorkerFactory,
                scheduler = browserParserWorkerScheduler,
                onStateChanged = { state ->
                    when (state) {
                        is ParserWorkerState.Parsed -> {
                            window.clearTimeout(guard)
                            try {
                                assertEquals("zip", state.presentation.format)
                                assertEquals("Zip File", state.presentation.displayLabel)
                                assertEquals("DedicatedWorkerGlobalScope", state.summary.scopeMarker)
                                assertTrue(state.summary.documentAbsent)
                                val entries = state.presentation.tables.single { it.id == "Entries" }
                                assertEquals(listOf("Name", "Compressed", "Uncompressed", "Kind"), entries.columns)
                                val entry = entries.records.single()
                                assertEquals("classes.dex", entry.values.first())
                                assertEquals("zip_entry", entry.actions.single().kind)
                                assertEquals("classes.dex", entry.actions.single().name)
                                resolve(Unit)
                            } catch (failure: Throwable) {
                                reject(failure)
                            }
                        }
                        is ParserWorkerState.Failed -> {
                            window.clearTimeout(guard)
                            reject(Throwable("APK worker input failed: ${state.code}: ${state.message}"))
                        }
                        ParserWorkerState.TimedOut -> {
                            window.clearTimeout(guard)
                            reject(Throwable("APK worker input timed out."))
                        }
                        else -> Unit
                    }
                },
            )
            controller.parse(input, 5_000, "application.apk")
        }

    @Test
    fun realWorkerParsesClassFileWithWorkerDerivedLabelAndTables(): Promise<Unit> =
        assertRealWorkerPresentation(minimalClassFileBuffer(), "Class file") { presentation ->
            assertEquals("class", presentation.format)
            assertEquals("Class File", presentation.displayLabel)
            assertTrue("Header" in presentation.tables.map { it.id })
            assertTrue("Methods" in presentation.tables.map { it.id })
        }

    @Test
    fun realWorkerParsesDexWithWorkerDerivedLabelAndTables(): Promise<Unit> =
        assertRealWorkerPresentation(minimalDexBuffer(), "DEX") { presentation ->
            assertEquals("dex", presentation.format)
            assertEquals("Android DEX File", presentation.displayLabel)
            assertTrue("Header" in presentation.tables.map { it.id })
        }

    @Test
    fun realWorkerParsesPcapAndPcapNgWithDistinctWorkerDerivedLabels(): Promise<Unit> =
        Promise { resolve, reject ->
            assertRealWorkerPresentation(minimalPcapBuffer(), "PCAP") { pcap ->
                assertEquals("pcap", pcap.format)
                assertEquals("PCAP Capture", pcap.displayLabel)
                assertTrue("Header" in pcap.tables.map { it.id })
            }.then(
                onFulfilled = {
                    assertRealWorkerPresentation(minimalPcapNgBuffer(), "PCAP-NG") { pcapNg ->
                        assertEquals("pcap", pcapNg.format)
                        assertEquals("PCAP-NG Capture", pcapNg.displayLabel)
                        assertTrue("Header" in pcapNg.tables.map { it.id })
                    }.then(
                        onFulfilled = { resolve(Unit) },
                        onRejected = reject,
                    )
                },
                onRejected = reject,
            )
        }

    @Test
    fun realWorkerParsesProtobufDescriptorWithGenericWorkerLabel(): Promise<Unit> =
        assertRealWorkerPresentation(protobufDescriptorSetBuffer(), "Protobuf descriptor") { presentation ->
            assertEquals("protobuf", presentation.format)
            assertEquals("Binary", presentation.displayLabel)
            assertTrue("Files" in presentation.tables.map { it.id })
        }

    @Test
    fun realWorkerParsesNativeElfFixtureWithExactWorkerDerivedLabelAndTables(): Promise<Unit> =
        assertFixtureWorkerPresentation("sample.elf", "ELF") { presentation ->
            assertEquals("elf", presentation.format)
            assertEquals("ELF64", presentation.displayLabel)
            assertTrue("Headers" in presentation.tables.map { it.id })
            assertTrue("Sections" in presentation.tables.map { it.id })
        }

    @Test
    fun realWorkerParsesStaticArchiveFixtureWithWorkerDerivedLabelAndTables(): Promise<Unit> =
        assertFixtureWorkerPresentation("sample.lib", "static archive") { presentation ->
            assertEquals("archive", presentation.format)
            assertEquals("Archive", presentation.displayLabel)
            assertTrue("Members" in presentation.tables.map { it.id })
        }

    @Test
    fun arrowAndParquetMagicStayGenericInTheWorker(): Promise<Unit> =
        Promise { resolve, reject ->
            assertRealWorkerPresentation(binaryBuffer("ARROW1".encodeToByteArray()), "Arrow magic") { arrow ->
                assertEquals("generic", arrow.format)
                assertEquals("Binary", arrow.displayLabel)
            }.then(
                onFulfilled = {
                    assertRealWorkerPresentation(binaryBuffer("PAR1".encodeToByteArray()), "Parquet magic") { parquet ->
                        assertEquals("generic", parquet.format)
                        assertEquals("Binary", parquet.displayLabel)
                    }.then(
                        onFulfilled = { resolve(Unit) },
                        onRejected = reject,
                    )
                },
                onRejected = reject,
            )
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
    fun rawRealWorkerRejectsOversizedAndNonStringFileNames(): Promise<Unit> =
        Promise { resolve, reject ->
            rawWorkerRejectsInvalidFileName("x".repeat(MAX_PARSER_WORKER_FILE_NAME_CHARS + 1)).then(
                onFulfilled = {
                    rawWorkerRejectsInvalidFileName(42).then(
                        onFulfilled = { resolve(Unit) },
                        onRejected = reject,
                    )
                },
                onRejected = reject,
            )
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

private fun apkFixtureBuffer(): ArrayBuffer {
    val output = mutableListOf<Byte>()
    val name = "classes.dex".encodeToByteArray()
    val data = byteArrayOf(0)

    fun u2(value: Int) {
        output += (value and 0xff).toByte()
        output += ((value ushr 8) and 0xff).toByte()
    }
    fun u4(value: Int) {
        output += (value and 0xff).toByte()
        output += ((value ushr 8) and 0xff).toByte()
        output += ((value ushr 16) and 0xff).toByte()
        output += ((value ushr 24) and 0xff).toByte()
    }
    fun bytes(value: ByteArray) {
        value.forEach(output::add)
    }

    u4(0x04034B50)
    u2(20); u2(0); u2(0); u2(0); u2(0)
    u4(0); u4(data.size); u4(data.size)
    u2(name.size); u2(0)
    bytes(name); bytes(data)

    val centralDirectoryOffset = output.size
    u4(0x02014B50)
    u2(20); u2(20); u2(0); u2(0); u2(0); u2(0)
    u4(0); u4(data.size); u4(data.size)
    u2(name.size); u2(0); u2(0); u2(0); u2(0); u4(0); u4(0)
    bytes(name)

    val centralDirectorySize = output.size - centralDirectoryOffset
    u4(0x06054B50)
    u2(0); u2(0); u2(1); u2(1)
    u4(centralDirectorySize); u4(centralDirectoryOffset); u2(0)

    return Uint8Array(output.map { it.toInt().and(0xff).toDouble() }.toTypedArray()).buffer
}

private fun assertRealWorkerPresentation(
    input: ArrayBuffer,
    description: String,
    fileName: String? = null,
    assertion: (ParserWorkerPresentation) -> Unit,
): Promise<Unit> = Promise { resolve, reject ->
    var controller: ParserWorkerController? = null
    val guard = window.setTimeout({
        controller?.dispose()
        reject(Throwable("$description parser worker did not answer within the browser-test deadline."))
    }, REAL_WORKER_TEST_TIMEOUT_MS)
    controller = ParserWorkerController(
        workerFactory = browserParserWorkerFactory,
        scheduler = browserParserWorkerScheduler,
        onStateChanged = { state ->
            when (state) {
                is ParserWorkerState.Parsed -> {
                    window.clearTimeout(guard)
                    try {
                        assertion(state.presentation)
                        resolve(Unit)
                    } catch (failure: Throwable) {
                        reject(failure)
                    }
                }
                is ParserWorkerState.Failed -> {
                    window.clearTimeout(guard)
                    reject(Throwable("$description worker input failed: ${state.code}: ${state.message}"))
                }
                ParserWorkerState.TimedOut -> {
                    window.clearTimeout(guard)
                    reject(Throwable("$description parser worker timed out."))
                }
                else -> Unit
            }
        },
    )
    controller.parse(input, 5_000, fileName)
}

private fun assertFixtureWorkerPresentation(
    fixtureName: String,
    description: String,
    assertion: (ParserWorkerPresentation) -> Unit,
): Promise<Unit> = Promise { resolve, reject ->
    window.fetch("/kotlin/parser-worker-fixtures/$fixtureName", js("({})").unsafeCast<RequestInit>()).then(
        onFulfilled = { fixture ->
            if (!fixture.ok) {
                reject(Throwable("Could not fetch the $description parser-worker fixture."))
                return@then
            }
            fixture.arrayBuffer().then(
                onFulfilled = { input ->
                    assertRealWorkerPresentation(input, description, fixtureName, assertion).then(
                        onFulfilled = { resolve(Unit) },
                        onRejected = reject,
                    )
                },
                onRejected = reject,
            )
        },
        onRejected = reject,
    )
}

private fun minimalClassFileBuffer(): ArrayBuffer = binaryBuffer(
    byteArrayOf(
        0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(),
        0x00, 0x00, 0x00, 0x34, 0x00, 0x05,
        0x01, 0x00, 0x06, 0x48, 0x65, 0x78, 0x61, 0x6e, 0x61,
        0x07, 0x00, 0x01,
        0x01, 0x00, 0x10, 0x6a, 0x61, 0x76, 0x61, 0x2f, 0x6c, 0x61, 0x6e, 0x67, 0x2f, 0x4f, 0x62, 0x6a, 0x65, 0x63, 0x74,
        0x07, 0x00, 0x03,
        0x00, 0x21, 0x00, 0x02, 0x00, 0x04,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    ),
)

private fun minimalDexBuffer(): ArrayBuffer {
    val bytes = ByteArray(112)
    bytes[0] = 'd'.code.toByte(); bytes[1] = 'e'.code.toByte(); bytes[2] = 'x'.code.toByte(); bytes[3] = '\n'.code.toByte()
    bytes[4] = '0'.code.toByte(); bytes[5] = '3'.code.toByte(); bytes[6] = '5'.code.toByte()
    putU32Le(bytes, 32, 112)
    putU32Le(bytes, 36, 112)
    putU32Le(bytes, 40, 0x12345678)
    return binaryBuffer(bytes)
}

private fun minimalPcapBuffer(): ArrayBuffer {
    val bytes = ByteArray(24)
    bytes[0] = 0xd4.toByte(); bytes[1] = 0xc3.toByte(); bytes[2] = 0xb2.toByte(); bytes[3] = 0xa1.toByte()
    bytes[4] = 2; bytes[6] = 4
    putU32Le(bytes, 16, 65_535)
    putU32Le(bytes, 20, 1)
    return binaryBuffer(bytes)
}

private fun minimalPcapNgBuffer(): ArrayBuffer {
    val sectionHeader = ByteArray(28)
    sectionHeader[0] = 0x0a; sectionHeader[1] = 0x0d; sectionHeader[2] = 0x0d; sectionHeader[3] = 0x0a
    putU32Le(sectionHeader, 4, 28)
    sectionHeader[8] = 0x4d; sectionHeader[9] = 0x3c; sectionHeader[10] = 0x2b; sectionHeader[11] = 0x1a
    sectionHeader[12] = 1
    for (index in 16..23) sectionHeader[index] = 0xff.toByte()
    putU32Le(sectionHeader, 24, 28)

    val interfaceDescription = ByteArray(20)
    putU32Le(interfaceDescription, 0, 1)
    putU32Le(interfaceDescription, 4, 20)
    interfaceDescription[8] = 1
    putU32Le(interfaceDescription, 12, 65_535)
    putU32Le(interfaceDescription, 16, 20)
    return binaryBuffer(sectionHeader + interfaceDescription)
}

private fun protobufDescriptorSetBuffer(): ArrayBuffer = binaryBuffer(
    byteArrayOf(0x0a, 12, 0x0a, 10) + "test.proto".encodeToByteArray(),
)

private fun putU32Le(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

private fun binaryBuffer(bytes: ByteArray): ArrayBuffer =
    Uint8Array(bytes.map { byte -> (byte.toInt() and 0xff).toDouble() }.toTypedArray()).buffer

private fun rawWorkerRejectsInvalidFileName(fileName: dynamic): Promise<Unit> =
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
            reject(Throwable("Raw invalid file-name worker request did not answer within the browser-test deadline."))
        }, REAL_WORKER_TEST_TIMEOUT_MS)
        worker.setHandlers(
            onMessage = { message ->
                cleanup()
                try {
                    assertEquals(PARSER_WORKER_PROTOCOL_VERSION, message.protocolVersion)
                    assertEquals("error", message.kind)
                    assertEquals("raw-invalid-file-name", message.requestId)
                    assertEquals("INVALID_REQUEST", message.code)
                    assertEquals("Parser worker request is invalid.", message.message)
                    resolve(Unit)
                } catch (failure: Throwable) {
                    reject(failure)
                }
            },
            onError = {
                cleanup()
                reject(Throwable("Raw invalid file-name worker request raised a worker error."))
            },
            onMessageError = {
                cleanup()
                reject(Throwable("Raw invalid file-name worker response could not be decoded."))
            },
        )

        val request = js("({})")
        request.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
        request.kind = "parse"
        request.requestId = "raw-invalid-file-name"
        request.fileName = fileName
        request.buffer = input
        worker.postParse(request, input)
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
