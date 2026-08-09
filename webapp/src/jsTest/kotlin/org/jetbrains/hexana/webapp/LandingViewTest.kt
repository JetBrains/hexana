package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composition
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.Element
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

private const val DOM_WAIT_TIMEOUT_MS = 2_000.0
private var landingRootSequence = 0

class LandingViewTest {
    @Test
    fun landingStatusRecoversFromRejectedAndFailedDropForSuccessfulRetry(): Promise<Unit> {
        return Promise { resolve, reject ->
            val root = document.createElement("div").unsafeCast<HTMLDivElement>()
            root.id = "landing-view-test-root-${landingRootSequence++}"
            val factory = LandingReaderFactory()
            val loadedNames = mutableListOf<String>()
            var composition: Composition? = null
            var networkSpies: LandingNetworkSpies? = null
            var completed = false

            fun complete(failure: Throwable? = null) {
                if (completed) return
                completed = true
                var finalFailure = failure
                try {
                    composition?.dispose()
                } catch (cleanupFailure: Throwable) {
                    if (finalFailure == null) finalFailure = cleanupFailure
                }
                try {
                    root.parentNode?.removeChild(root)
                } catch (cleanupFailure: Throwable) {
                    if (finalFailure == null) finalFailure = cleanupFailure
                }
                try {
                    networkSpies?.assertNoCalls()
                } catch (cleanupFailure: Throwable) {
                    if (finalFailure == null) finalFailure = cleanupFailure
                }
                try {
                    networkSpies?.restore()
                } catch (cleanupFailure: Throwable) {
                    if (finalFailure == null) finalFailure = cleanupFailure
                }

                val rejection = finalFailure
                if (rejection == null) resolve(Unit) else reject(rejection)
            }

            fun awaitCondition(
                description: String,
                currentValue: () -> String?,
                condition: () -> Boolean,
                onReady: () -> Unit,
            ) {
                val deadline = window.performance.now() + DOM_WAIT_TIMEOUT_MS
                lateinit var poll: (Double) -> Unit
                poll = poll@ { timestamp ->
                    if (completed) return@poll
                    try {
                        if (condition()) {
                            onReady()
                        } else if (timestamp >= deadline) {
                            complete(
                                AssertionError(
                                    "Timed out waiting for $description; current value was ${currentValue()}",
                                ),
                            )
                        } else {
                            window.requestAnimationFrame(poll)
                        }
                    } catch (failure: Throwable) {
                        complete(failure)
                    }
                }
                window.requestAnimationFrame(poll)
            }

            fun statusMessage(): String? = root.querySelector("[role='status']")?.textContent

            fun dropFile(file: File) {
                drop(checkNotNull(root.querySelector("[data-file-drop-zone]")), file)
            }

            fun awaitStatus(expected: BrowserFileLoadStatus, onReady: () -> Unit) {
                awaitCondition(
                    description = "landing status ${expected.name}",
                    currentValue = ::statusMessage,
                    condition = { statusMessage() == expected.message },
                    onReady = onReady,
                )
            }

            try {
                networkSpies = LandingNetworkSpies()
                checkNotNull(document.body).appendChild(root)
                composition = renderComposable(rootElementId = root.id) {
                    LandingView(
                        onFileLoaded = { name, _ -> loadedNames += name },
                        readerFactory = factory,
                    )
                }

                awaitCondition(
                    description = "accessible landing controls",
                    currentValue = ::statusMessage,
                    condition = {
                        root.querySelector("[data-file-drop-zone]") != null &&
                            root.querySelector("[role='status']")?.getAttribute("aria-live") == "polite"
                    },
                ) {
                    dropFile(landingSyntheticFile("too-large.bin", MAX_BROWSER_FILE_SIZE_BYTES + 1.0))
                    awaitStatus(BrowserFileLoadStatus.Rejected) {
                        assertEquals(0, factory.createCalls)

                        dropFile(landingSyntheticFile("failed.bin", 1.0))
                        awaitStatus(BrowserFileLoadStatus.Reading) {
                            factory.readers.single().fireError()
                            awaitStatus(BrowserFileLoadStatus.ReadError) {
                                dropFile(landingSyntheticFile("retry.bin", 1.0))
                                awaitStatus(BrowserFileLoadStatus.Reading) {
                                    factory.readers.last().fireLoad(landingBuffer())
                                    awaitCondition(
                                        description = "successful file callback",
                                        currentValue = { loadedNames.joinToString() },
                                        condition = { loadedNames == listOf("retry.bin") },
                                        onReady = { complete() },
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                complete(failure)
            }
        }
    }
}

private class LandingNetworkSpies {
    private val global = js("globalThis")
    private val objectApi = js("Object")
    private val reflectApi = js("Reflect")
    private val originalFetchDescriptor = objectApi.getOwnPropertyDescriptor(global, "fetch")
    private val originalXmlHttpRequestDescriptor = objectApi.getOwnPropertyDescriptor(global, "XMLHttpRequest")
    private var restored = false

    var fetchCalls = 0
        private set
    var xhrCalls = 0
        private set

    init {
        try {
            global.fetch = { _: dynamic ->
                fetchCalls++
                null
            }
            global.XMLHttpRequest = {
                xhrCalls++
                js("({})")
            }
        } catch (failure: Throwable) {
            try {
                restore()
            } catch (_: Throwable) {
                // Preserve the installation failure while still attempting both restorations.
            }
            throw failure
        }
    }

    fun assertNoCalls() {
        assertEquals(0, fetchCalls, "Landing file handling must not call fetch")
        assertEquals(0, xhrCalls, "Landing file handling must not construct XMLHttpRequest")
    }

    fun restore() {
        if (restored) return
        restored = true
        var failure: Throwable? = null
        try {
            restoreProperty("fetch", originalFetchDescriptor)
        } catch (restoreFailure: Throwable) {
            failure = restoreFailure
        }
        try {
            restoreProperty("XMLHttpRequest", originalXmlHttpRequestDescriptor)
        } catch (restoreFailure: Throwable) {
            if (failure == null) failure = restoreFailure
        }
        failure?.let { throw it }
    }

    private fun restoreProperty(name: String, descriptor: dynamic) {
        if (descriptor == null) {
            reflectApi.deleteProperty(global, name)
        } else {
            objectApi.defineProperty(global, name, descriptor)
        }
    }
}

private class LandingReaderFactory : BrowserFileReaderFactory {
    var createCalls = 0
        private set
    val readers = mutableListOf<LandingReader>()

    override fun create(): BrowserFileReader {
        createCalls++
        return LandingReader().also(readers::add)
    }
}

private class LandingReader : BrowserFileReader {
    private var onLoad: ((ArrayBuffer) -> Unit)? = null
    private var onError: (() -> Unit)? = null
    private var onAbort: (() -> Unit)? = null

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
        onLoad = null
        onError = null
        onAbort = null
    }

    override fun readAsArrayBuffer(file: File) = Unit

    override fun abort() = Unit

    fun fireLoad(buffer: ArrayBuffer) {
        requireNotNull(onLoad)(buffer)
    }

    fun fireError() {
        requireNotNull(onError)()
    }
}

private fun drop(target: Element, file: File) {
    val event = js("new Event('drop', { bubbles: true, cancelable: true })")
    val files = js("({})")
    files.item = { index: Int -> if (index == 0) file else null }
    val dataTransfer = js("({})")
    dataTransfer.files = files
    event.dataTransfer = dataTransfer
    target.dispatchEvent(event.unsafeCast<Event>())
}

private fun landingSyntheticFile(name: String, size: Double): File {
    val file = js("({})")
    file.name = name
    file.size = size
    return file.unsafeCast<File>()
}

private fun landingBuffer(): ArrayBuffer = js("new Uint8Array([0]).buffer").unsafeCast<ArrayBuffer>()
