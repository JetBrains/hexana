package org.jetbrains.hexana.webapp

import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader

internal const val MAX_BROWSER_FILE_SIZE_BYTES: Double = 52_428_800.0

internal enum class BrowserFileLoadStatus(
    val message: String,
    val isError: Boolean,
) {
    Ready("Choose a binary file to inspect.", false),
    Reading("Reading file…", false),
    Rejected("The selected file is larger than the 50 MiB limit.", true),
    ReadError("The selected file could not be read. Try again.", true),
    Aborted("Reading was cancelled. Choose a file to try again.", false),
    PickerCancelled("No file was selected. Choose a file to try again.", false),
}

internal interface BrowserFileReader {
    fun setHandlers(
        onLoad: (ArrayBuffer) -> Unit,
        onError: () -> Unit,
        onAbort: () -> Unit,
    )

    fun clearHandlers()

    fun readAsArrayBuffer(file: File)

    fun abort()
}

internal fun interface BrowserFileReaderFactory {
    fun create(): BrowserFileReader
}

private class DomBrowserFileReader : BrowserFileReader {
    private val delegate = FileReader()

    override fun setHandlers(
        onLoad: (ArrayBuffer) -> Unit,
        onError: () -> Unit,
        onAbort: () -> Unit,
    ) {
        delegate.onload = { event ->
            onLoad(event.target.asDynamic().result.unsafeCast<ArrayBuffer>())
        }
        delegate.onerror = { onError() }
        delegate.onabort = { onAbort() }
    }

    override fun clearHandlers() {
        delegate.onload = null
        delegate.onerror = null
        delegate.onabort = null
    }

    override fun readAsArrayBuffer(file: File) {
        delegate.readAsArrayBuffer(file)
    }

    override fun abort() {
        delegate.abort()
    }
}

internal val domBrowserFileReaderFactory = BrowserFileReaderFactory { DomBrowserFileReader() }

internal class BrowserFileLoader(
    private val readerFactory: BrowserFileReaderFactory = domBrowserFileReaderFactory,
    private val onStatusChanged: (BrowserFileLoadStatus) -> Unit,
    private val onFileLoaded: (name: String, bytes: ByteArray) -> Unit,
) {
    private data class ActiveRead(
        val generation: Int,
        val reader: BrowserFileReader,
    )

    private var generation = 0
    private var activeRead: ActiveRead? = null

    internal val hasActiveReader: Boolean
        get() = activeRead != null

    fun select(file: File) {
        if (!BrowserFileAdmissionPolicy.accepts(file.name, file.size.toDouble())) {
            cancelActiveRead()
            onStatusChanged(BrowserFileLoadStatus.Rejected)
            return
        }

        cancelActiveRead()
        val currentGeneration = ++generation
        onStatusChanged(BrowserFileLoadStatus.Reading)

        val reader = try {
            readerFactory.create()
        } catch (_: Throwable) {
            onStatusChanged(BrowserFileLoadStatus.ReadError)
            return
        }
        activeRead = ActiveRead(currentGeneration, reader)
        try {
            reader.setHandlers(
                onLoad = { buffer -> handleLoad(currentGeneration, reader, file.name, buffer) },
                onError = { finishWithStatus(currentGeneration, reader, BrowserFileLoadStatus.ReadError) },
                onAbort = { finishWithStatus(currentGeneration, reader, BrowserFileLoadStatus.Aborted) },
            )
            reader.readAsArrayBuffer(file)
        } catch (_: Throwable) {
            finishWithStatus(currentGeneration, reader, BrowserFileLoadStatus.ReadError)
        }
    }

    fun cancelPickerSelection() {
        cancelActiveRead()
        onStatusChanged(BrowserFileLoadStatus.PickerCancelled)
    }

    fun dispose() {
        cancelActiveRead()
    }

    private fun handleLoad(
        expectedGeneration: Int,
        reader: BrowserFileReader,
        fileName: String,
        buffer: ArrayBuffer,
    ) {
        if (!isActive(expectedGeneration, reader)) return

        val bytes = try {
            arrayBufferToByteArray(buffer)
        } catch (_: Throwable) {
            finishWithStatus(expectedGeneration, reader, BrowserFileLoadStatus.ReadError)
            return
        }
        releaseIfActive(expectedGeneration, reader)
        onFileLoaded(fileName, bytes)
    }

    private fun finishWithStatus(
        expectedGeneration: Int,
        reader: BrowserFileReader,
        status: BrowserFileLoadStatus,
    ) {
        if (!releaseIfActive(expectedGeneration, reader)) return
        onStatusChanged(status)
    }

    private fun releaseIfActive(expectedGeneration: Int, reader: BrowserFileReader): Boolean {
        if (!isActive(expectedGeneration, reader)) return false
        activeRead = null
        clearHandlers(reader)
        return true
    }

    private fun isActive(expectedGeneration: Int, reader: BrowserFileReader): Boolean {
        val current = activeRead
        return current?.generation == expectedGeneration && current.reader === reader
    }

    private fun cancelActiveRead() {
        generation++
        val current = activeRead ?: return
        activeRead = null
        clearHandlers(current.reader)
        try {
            current.reader.abort()
        } catch (_: Throwable) {
            // The active read is already detached; cancellation remains terminal.
        }
    }

    private fun clearHandlers(reader: BrowserFileReader) {
        try {
            reader.clearHandlers()
        } catch (_: Throwable) {
            // The controller reference is already cleared, so stale callbacks stay harmless.
        }
    }
}

internal fun arrayBufferToByteArray(buffer: ArrayBuffer): ByteArray =
    Int8Array(buffer).unsafeCast<ByteArray>()

internal fun openFilePicker(accept: String? = null, onSelection: (File?) -> Unit) {
    val input = document.createElement("input").unsafeCast<HTMLInputElement>()
    input.type = "file"
    if (accept != null) input.accept = accept
    var completed = false

    fun finish(file: File?) {
        if (completed) return
        completed = true
        input.onchange = null
        input.asDynamic().oncancel = null
        onSelection(file)
    }

    input.onchange = { finish(input.files?.item(0)) }
    input.asDynamic().oncancel = { finish(null) }
    try {
        input.click()
    } catch (_: Throwable) {
        finish(null)
    }
}
