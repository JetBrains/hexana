package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composition
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.Element
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DOM_WAIT_TIMEOUT_MS = 2_000.0
private var landingRootSequence = 0

class LandingViewTest {
    @Test
    fun aboutMenuUsesBundledMetadataWithoutNetworkAccess(): Promise<Unit> {
        return Promise { resolve, reject ->
        val root = landingRoot()
        var networkSpies: LandingNetworkSpies? = null
        val info = WebappReleaseInfo(
            version = "0.1.0",
            revision = "0123456789abcdef",
            changelog = "# Changelog\n\n## [0.1.0] - 2026-08-14\n\n- Initial public Webapp release.\n",
            repositoryUrl = "https://github.com/JetBrains/hexana",
            websiteUrl = "https://jetbrains.github.io/hexana/",
        )
        var composition: Composition? = null
        var finished = false
        fun finish(failure: Throwable? = null) {
            if (finished) return
            finished = true
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
            if (finalFailure == null) resolve(Unit) else reject(finalFailure)
        }
        fun awaitCondition(description: String, condition: () -> Boolean, ready: () -> Unit) {
            val deadline = window.performance.now() + DOM_WAIT_TIMEOUT_MS
            fun poll() {
                try {
                    when {
                        condition() -> ready()
                        window.performance.now() >= deadline -> finish(AssertionError("Timed out waiting for $description"))
                        else -> window.requestAnimationFrame { poll() }
                    }
                } catch (failure: Throwable) { finish(failure) }
            }
            window.requestAnimationFrame { poll() }
        }
        try {
            networkSpies = LandingNetworkSpies()
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                LandingView(onFileLoaded = { _, _, _ -> }, releaseInfo = info)
            }
            val trigger = root.querySelector("[data-about-trigger]")!!.unsafeCast<HTMLElement>()
            assertEquals("About Hexana 0.1.0", trigger.getAttribute("aria-label"))
            assertEquals("false", trigger.getAttribute("aria-expanded"))
            trigger.focus()
            landingKeyDown(trigger, "Enter")
            awaitCondition("about menu opening", { root.querySelector("[data-about-surface]") != null }) {
                try {
                    val surface = root.querySelector("[data-about-surface]")!!.unsafeCast<HTMLElement>()
                    val openTrigger = root.querySelector("[data-about-trigger]")!!
                    assertEquals("true", openTrigger.getAttribute("aria-expanded"))
                    check(surface.textContent!!.contains(info.version))
                    check(surface.textContent!!.contains(info.revision))
                    check(surface.textContent!!.contains(info.changelog.trim()))
                    val github = root.querySelector("[data-about-link='github']")!!
                    assertEquals(info.repositoryUrl, github.getAttribute("href")); assertEquals("_blank", github.getAttribute("target")); assertEquals("noopener noreferrer", github.getAttribute("rel"))
                    val website = root.querySelector("[data-about-link='website']")!!
                    assertEquals(info.websiteUrl, website.getAttribute("href")); assertEquals("noopener noreferrer", website.getAttribute("rel"))
                    landingKeyDown(surface, "Escape")
                    awaitCondition("about menu closing with focus restoration", {
                        val current = root.querySelector("[data-about-trigger]") as? HTMLElement
                        root.querySelector("[data-about-surface]") == null && current?.getAttribute("aria-expanded") == "false" && document.activeElement === current
                    }) {
                        val current = root.querySelector("[data-about-trigger]")!!.unsafeCast<HTMLElement>()
                        current.click()
                        awaitCondition("about menu reopening", { root.querySelector("[data-about-surface]") != null }) {
                            landingMouseDown(document.body!!)
                            awaitCondition("outside click closing the about menu", { root.querySelector("[data-about-surface]") == null }) { finish() }
                        }
                    }
                } catch (failure: Throwable) { finish(failure) }
            }
        } catch (failure: Throwable) {
            finish(failure)
        }
    }
    }

    @Test
    fun coreWasmLandingPathUsesProductionWorkerBeforeExposingUiBytes(): Promise<Unit> = Promise { resolve, reject ->
        val root = landingRoot()
        val factory = LandingReaderFactory()
        var composition: Composition? = null
        var guard = 0
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                LandingView(onFileLoaded = { _, bytes, _ ->
                    window.clearTimeout(guard)
                    try {
                        assertEquals(55, bytes.size)
                        assertEquals(0, bytes[0].toInt() and 0xff)
                        assertEquals(0x6d, bytes[3].toInt() and 0xff)
                        resolve(Unit)
                    } catch (failure: Throwable) { reject(failure) }
                    composition?.dispose()
                    root.parentNode?.removeChild(root)
                }, readerFactory = factory)
            }
            guard = window.setTimeout({
                composition?.dispose(); root.parentNode?.removeChild(root)
                reject(Throwable("Landing core-Wasm production worker did not complete."))
            }, DOM_WAIT_TIMEOUT_MS.toInt())
            drop(root.querySelector("[data-file-drop-zone]")!!, landingSyntheticFile("addition.wasm", 55.0))
            factory.readers.single().fireLoad(additionFixtureBuffer())
        } catch (failure: Throwable) {
            composition?.dispose(); root.parentNode?.removeChild(root); reject(failure)
        }
    }

    @Test
    fun timeoutRetryRereadsRetainedFileAndUsesFreshWorker(): Promise<Unit> = Promise { resolve, reject ->
        val root = landingRoot()
        val readers = LandingReaderFactory()
        val scheduler = LandingScheduler()
        val workers = mutableListOf<LandingWorker>()
        val file = landingSyntheticFile("retry.wasm", 55.0)
        var composition: Composition? = null
        var finished = false
        var guard = 0
        fun finish(failure: Throwable? = null) {
            if (finished) return
            finished = true
            window.clearTimeout(guard)
            composition?.dispose()
            root.parentNode?.removeChild(root)
            if (failure == null) resolve(Unit) else reject(failure)
        }
        try {
            document.body?.appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                LandingView(
                    onFileLoaded = { name, bytes, _ ->
                        try {
                            assertEquals("retry.wasm", name); assertEquals(55, bytes.size)
                            assertEquals(2, readers.readFiles.size); assertTrue(readers.readFiles.all { it === file })
                            assertEquals(PARSER_WORKER_TIMEOUT_MS, scheduler.lastDelayMs)
                            finish()
                        } catch (t: Throwable) { finish(t) }
                    },
                    readerFactory = readers,
                    workerFactory = ParserWorkerFactory { LandingWorker().also(workers::add) },
                    workerScheduler = scheduler,
                )
            }
            guard = window.setTimeout({ finish(Throwable("Landing timeout retry did not complete.")) }, DOM_WAIT_TIMEOUT_MS.toInt())
            drop(root.querySelector("[data-file-drop-zone]")!!, file)
            readers.readers.single().fireLoad(additionFixtureBuffer())
            scheduler.fireActive()
            window.requestAnimationFrame {
                window.requestAnimationFrame {
                try {
                    root.querySelector("[data-parser-worker-action='retry']")
                        .unsafeCast<org.w3c.dom.HTMLButtonElement>().click()
                    window.requestAnimationFrame {
                        try {
                            assertEquals(2, readers.readers.size)
                            readers.readers.last().fireLoad(additionFixtureBuffer())
                            workers.last().fireSuccess()
                        } catch (t: Throwable) { finish(t) }
                    }
                } catch (t: Throwable) { finish(t) }
                }
            }
        } catch (t: Throwable) { finish(t) }
    }

    @Test
    fun unmountTerminatesLandingWorkerAndStaleCallbackCannotExposeBytes() {
        val root = landingRoot()
        val readers = LandingReaderFactory()
        val worker = LandingWorker()
        val loaded = mutableListOf<ByteArray>()
        document.body?.appendChild(root)
        val composition = renderComposable(rootElementId = root.id) {
            LandingView(
                onFileLoaded = { _, bytes, _ -> loaded += bytes },
                readerFactory = readers,
                workerFactory = ParserWorkerFactory { worker },
                workerScheduler = LandingScheduler(),
            )
        }
        drop(root.querySelector("[data-file-drop-zone]")!!, landingSyntheticFile("active.wasm", 55.0))
        readers.readers.single().fireLoad(additionFixtureBuffer())
        composition.dispose()
        worker.fireSuccess()
        root.parentNode?.removeChild(root)
        assertEquals(1, worker.terminateCalls)
        assertTrue(loaded.isEmpty())
    }
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
                        onFileLoaded = { name, _, _ -> loadedNames += name },
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
    val readFiles = mutableListOf<File>()

    override fun create(): BrowserFileReader {
        createCalls++
        return LandingReader(readFiles).also(readers::add)
    }
}

private class LandingReader(private val readFiles: MutableList<File>) : BrowserFileReader {
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

    override fun readAsArrayBuffer(file: File) { readFiles += file }

    override fun abort() = Unit

    fun fireLoad(buffer: ArrayBuffer) {
        requireNotNull(onLoad)(buffer)
    }

    fun fireError() {
        requireNotNull(onError)()
    }
}

private class LandingWorker : ParserWorkerPort {
    private lateinit var onMessage: (dynamic) -> Unit
    var terminateCalls = 0
    private var requestId = ""
    override fun setHandlers(onMessage: (dynamic) -> Unit, onError: () -> Unit, onMessageError: () -> Unit) {
        this.onMessage = onMessage
    }
    override fun clearHandlers() = Unit
    override fun postParse(request: dynamic, buffer: ArrayBuffer) { requestId = request.requestId as String }
    override fun terminate() { terminateCalls++ }
    fun fireSuccess() {
        onMessage(encodeBoundedParserWorkerSuccess(requestId, representativePresentation(55)))
    }
}

private class LandingScheduler : ParserWorkerScheduler {
    private data class Item(var cancelled: Boolean, val callback: () -> Unit)
    private val items = mutableListOf<Item>()
    var lastDelayMs: Int? = null
    override fun schedule(delayMs: Int, callback: () -> Unit): ParserWorkerTimer {
        lastDelayMs = delayMs
        val item = Item(false, callback); items += item
        return object : ParserWorkerTimer { override fun cancel() { item.cancelled = true } }
    }
    fun fireActive() = items.last { !it.cancelled }.callback()
}

private fun landingRoot(): HTMLDivElement = document.createElement("div").unsafeCast<HTMLDivElement>().also {
    it.id = "landing-view-test-root-${landingRootSequence++}"
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

private fun landingKeyDown(target: Element, key: String) {
    val event = js("new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: key })")
    target.dispatchEvent(event.unsafeCast<Event>())
}

private fun landingMouseDown(target: Element) {
    val event = js("new MouseEvent('mousedown', { bubbles: true, cancelable: true })")
    target.dispatchEvent(event.unsafeCast<Event>())
}

private fun landingSyntheticFile(name: String, size: Double): File {
    val file = js("({})")
    file.name = name
    file.size = size
    return file.unsafeCast<File>()
}

private fun landingBuffer(): ArrayBuffer = js("new Uint8Array([0]).buffer").unsafeCast<ArrayBuffer>()
