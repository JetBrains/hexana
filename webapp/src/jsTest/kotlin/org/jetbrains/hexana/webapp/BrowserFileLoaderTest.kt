package org.jetbrains.hexana.webapp

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.files.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserFileLoaderTest {
    @Test
    fun fileLargerThan50MiBIsRejectedBeforeReaderCreation() {
        val factory = RecordingReaderFactory()
        val statuses = mutableListOf<BrowserFileLoadStatus>()
        val loader = loader(factory, statuses)

        loader.select(syntheticFile("oversized.data", MAX_BROWSER_FILE_SIZE_BYTES + 1.0))

        assertEquals(listOf(BrowserFileLoadStatus.Rejected), statuses)
        assertEquals(0, factory.createCalls)
        assertTrue(factory.readers.isEmpty())
        assertFalse(loader.hasActiveReader)
    }

    @Test
    fun fileAtExact50MiBBoundaryCreatesAndInvokesReaderOnce() {
        val factory = RecordingReaderFactory()
        val statuses = mutableListOf<BrowserFileLoadStatus>()
        val loader = loader(factory, statuses)

        loader.select(syntheticFile("no-extension", MAX_BROWSER_FILE_SIZE_BYTES))

        assertEquals(listOf(BrowserFileLoadStatus.Reading), statuses)
        assertEquals(1, factory.createCalls)
        assertEquals(1, factory.readers.single().readCalls)
        assertTrue(loader.hasActiveReader)
    }

    @Test
    fun readErrorReleasesReaderAndSuccessfulRetryWorks() {
        val factory = RecordingReaderFactory()
        val statuses = mutableListOf<BrowserFileLoadStatus>()
        val loaded = mutableListOf<Pair<String, ByteArray>>()
        val loader = loader(factory, statuses, loaded)

        loader.select(syntheticFile("first.bin", 4.0))
        val failedReader = factory.readers.single()
        failedReader.fireError()

        assertEquals(BrowserFileLoadStatus.ReadError, statuses.last())
        assertFalse(loader.hasActiveReader)
        assertEquals(1, failedReader.clearCalls)

        loader.select(syntheticFile("retry.bin", 4.0))
        factory.readers.last().fireLoad(bufferOf(0x00, 0x7f, 0x80, 0xff))

        assertFalse(loader.hasActiveReader)
        assertEquals("retry.bin", loaded.single().first)
        assertContentEquals(listOf(0x00, 0x7f, 0x80, 0xff), loaded.single().second.unsignedValues())
    }

    @Test
    fun abortReleasesReaderAndSuccessfulRetryWorks() {
        val factory = RecordingReaderFactory()
        val statuses = mutableListOf<BrowserFileLoadStatus>()
        val loaded = mutableListOf<Pair<String, ByteArray>>()
        val loader = loader(factory, statuses, loaded)

        loader.select(syntheticFile("first.bin", 1.0))
        val abortedReader = factory.readers.single()
        abortedReader.fireAbort()

        assertEquals(BrowserFileLoadStatus.Aborted, statuses.last())
        assertFalse(loader.hasActiveReader)
        assertEquals(1, abortedReader.clearCalls)

        loader.select(syntheticFile("retry.bin", 1.0))
        factory.readers.last().fireLoad(bufferOf(0x7f))

        assertFalse(loader.hasActiveReader)
        assertEquals("retry.bin", loaded.single().first)
    }

    @Test
    fun replacingInFlightReadAbortsOldReaderAndIgnoresStaleCompletion() {
        val factory = RecordingReaderFactory()
        val statuses = mutableListOf<BrowserFileLoadStatus>()
        val loaded = mutableListOf<Pair<String, ByteArray>>()
        val loader = loader(factory, statuses, loaded)

        loader.select(syntheticFile("old.bin", 1.0))
        val oldReader = factory.readers.single()
        val staleLoad = oldReader.loadHandler()

        loader.select(syntheticFile("new.bin", 1.0))

        assertEquals(1, oldReader.abortCalls)
        assertEquals(1, oldReader.clearCalls)
        staleLoad(bufferOf(0xff))
        assertTrue(loaded.isEmpty())

        factory.readers.last().fireLoad(bufferOf(0x00))

        assertEquals("new.bin", loaded.single().first)
        assertContentEquals(listOf(0x00), loaded.single().second.unsignedValues())
    }

    @Test
    fun pickerCancellationAbortsActiveReadAndAllowsRetry() {
        val factory = RecordingReaderFactory()
        val statuses = mutableListOf<BrowserFileLoadStatus>()
        val loaded = mutableListOf<Pair<String, ByteArray>>()
        val loader = loader(factory, statuses, loaded)

        loader.select(syntheticFile("first.bin", 1.0))
        val cancelledReader = factory.readers.single()
        loader.cancelPickerSelection()

        assertEquals(1, cancelledReader.abortCalls)
        assertEquals(BrowserFileLoadStatus.PickerCancelled, statuses.last())
        assertFalse(loader.hasActiveReader)

        loader.select(syntheticFile("retry.bin", 1.0))
        factory.readers.last().fireLoad(bufferOf(0x7f))

        assertEquals("retry.bin", loaded.single().first)
    }

    @Test
    fun disposalAbortsAndDetachesActiveReader() {
        val factory = RecordingReaderFactory()
        val statuses = mutableListOf<BrowserFileLoadStatus>()
        val loaded = mutableListOf<Pair<String, ByteArray>>()
        val loader = loader(factory, statuses, loaded)

        loader.select(syntheticFile("active.bin", 1.0))
        val reader = factory.readers.single()
        val staleLoad = reader.loadHandler()
        loader.dispose()

        assertEquals(1, reader.abortCalls)
        assertEquals(1, reader.clearCalls)
        assertFalse(loader.hasActiveReader)

        staleLoad(bufferOf(0x7f))
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun arrayBufferConversionPreservesUnsignedBytesWithoutCopyingBackingBuffer() {
        val source = Uint8Array(bufferOf(0x00, 0x7f, 0x80, 0xff))
        val bytes = arrayBufferToByteArray(source.buffer)

        assertContentEquals(listOf(0x00, 0x7f, 0x80, 0xff), bytes.unsignedValues())

        source.asDynamic()[0] = 0x2a
        assertEquals(0x2a, bytes[0].toInt() and 0xff)
    }

    @Test
    fun fileAdmissionAndReadingMakeNoFetchOrXhrCallsAndRestoreSpies() {
        val global = js("globalThis")
        val originalFetch = global.fetch
        val originalXmlHttpRequest = global.XMLHttpRequest
        var fetchCalls = 0
        var xhrCalls = 0
        global.fetch = { _: dynamic ->
            fetchCalls++
            null
        }
        global.XMLHttpRequest = {
            xhrCalls++
            js("({})")
        }

        try {
            val factory = RecordingReaderFactory()
            val statuses = mutableListOf<BrowserFileLoadStatus>()
            val loader = loader(factory, statuses)

            loader.select(syntheticFile("offline.bin", 1.0))
            factory.readers.single().fireLoad(bufferOf(0x00))

            assertEquals(0, fetchCalls)
            assertEquals(0, xhrCalls)
        } finally {
            global.fetch = originalFetch
            global.XMLHttpRequest = originalXmlHttpRequest
        }
    }

    private fun loader(
        factory: RecordingReaderFactory,
        statuses: MutableList<BrowserFileLoadStatus>,
        loaded: MutableList<Pair<String, ByteArray>> = mutableListOf(),
    ): BrowserFileLoader = BrowserFileLoader(
        readerFactory = factory,
        onStatusChanged = statuses::add,
        onFileLoaded = { name, bytes -> loaded += name to bytes },
    )
}

private class RecordingReaderFactory : BrowserFileReaderFactory {
    var createCalls = 0
        private set
    val readers = mutableListOf<FakeBrowserFileReader>()

    override fun create(): BrowserFileReader {
        createCalls++
        return FakeBrowserFileReader().also(readers::add)
    }
}

private class FakeBrowserFileReader : BrowserFileReader {
    private var onLoad: ((ArrayBuffer) -> Unit)? = null
    private var onError: (() -> Unit)? = null
    private var onAbort: (() -> Unit)? = null

    var readCalls = 0
        private set
    var abortCalls = 0
        private set
    var clearCalls = 0
        private set

    override fun setHandlers(
        onLoad: (ArrayBuffer) -> Unit,
        onError: () -> Unit,
        onAbort: () -> Unit,
    ) {
        this.onLoad = onLoad
        this.onError = onError
        this.onAbort = onAbort
    }

    override fun clearHandlers() {
        clearCalls++
        onLoad = null
        onError = null
        onAbort = null
    }

    override fun readAsArrayBuffer(file: File) {
        readCalls++
    }

    override fun abort() {
        abortCalls++
    }

    fun loadHandler(): (ArrayBuffer) -> Unit = requireNotNull(onLoad)

    fun fireLoad(buffer: ArrayBuffer) {
        requireNotNull(onLoad)(buffer)
    }

    fun fireError() {
        requireNotNull(onError)()
    }

    fun fireAbort() {
        requireNotNull(onAbort)()
    }
}

private fun syntheticFile(name: String, size: Double): File {
    val file = js("({})")
    file.name = name
    file.size = size
    return file.unsafeCast<File>()
}

private fun bufferOf(vararg values: Int): ArrayBuffer {
    val bytes = Uint8Array(values.size)
    values.forEachIndexed { index, value -> bytes.asDynamic()[index] = value }
    return bytes.buffer
}

private fun ByteArray.unsignedValues(): List<Int> = map { it.toInt() and 0xff }
