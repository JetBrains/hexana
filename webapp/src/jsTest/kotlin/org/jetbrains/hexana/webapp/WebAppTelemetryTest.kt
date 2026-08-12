package org.jetbrains.hexana.webapp

import androidx.compose.runtime.Composition
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.analytics.browser.BrowserAnalyticsClient
import org.jetbrains.hexana.analytics.browser.BrowserAnalyticsConfig
import org.jetbrains.hexana.analytics.browser.createBrowserAnalyticsClient
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAppTelemetryTest {
    @Test
    fun browserUiSendsOptedInEventToFakePostHogServer(): Promise<Unit> = Promise { resolve, reject ->
        val storage = FakeTelemetryStorage()
        val telemetry = BrowserWebAppTelemetry(
            storage = storage,
            analyticsClient = createBrowserAnalyticsClient(BrowserAnalyticsConfig(
                apiHost = "${window.location.origin}/__fake-posthog",
                requestBatching = false,
                advancedDisableDecide = true,
                optOutUseragentFilter = true,
            )),
        )
        val root = document.createElement("div").unsafeCast<HTMLDivElement>()
        root.id = "telemetry-http-test-root"
        var composition: Composition? = null
        var completed = false
        var timeout = 0

        fun finish(failure: Throwable? = null) {
            if (completed) return
            completed = true
            window.clearTimeout(timeout)
            telemetry.setEnabled(false)
            composition?.dispose()
            root.parentNode?.removeChild(root)
            if (failure == null) resolve(Unit) else reject(failure)
        }

        fun awaitParsed() {
            lateinit var poll: (Double) -> Unit
            poll = poll@ {
                try {
                    if (root.querySelector("[data-parser-worker-state='parsed']") != null) {
                        window.setTimeout({ finish() }, 250)
                    } else {
                        window.requestAnimationFrame(poll)
                    }
                } catch (failure: Throwable) {
                    finish(failure)
                }
            }
            window.requestAnimationFrame(poll)
        }

        try {
            try {
                checkNotNull(document.body).appendChild(root)
                composition = renderComposable(rootElementId = root.id) {
                    WebAppRoot(telemetryFactory = WebAppTelemetryFactory { telemetry })
                }
                timeout = window.setTimeout(
                    { finish(AssertionError("Browser UI did not send telemetry to the fake PostHog server.")) },
                    12_000,
                )
                window.requestAnimationFrame {
                    try {
                        root.querySelector("[data-telemetry-consent-toggle]")
                            .unsafeCast<HTMLButtonElement>()
                            .click()
                        telemetryDrop(
                            checkNotNull(root.querySelector("[data-file-drop-zone]")),
                            js("new File([new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])], 'module.wasm')")
                                .unsafeCast<File>(),
                        )
                        awaitParsed()
                    } catch (failure: Throwable) {
                        finish(failure)
                    }
                }
            } catch (failure: Throwable) {
                finish(failure)
            }
        } catch (failure: Throwable) {
            finish(failure)
        }
    }

    @Test
    fun enabledTelemetryCapturesOnlyIntentionalBoundedEventsWithWebappSurface() {
        val storage = FakeTelemetryStorage()
        val client = FakeBrowserAnalyticsClient()
        val telemetry = BrowserWebAppTelemetry(storage, client)

        telemetry.initialize()
        telemetry.setEnabled(true)
        telemetry.captureBinaryOpened(fileSizeBytes = 55, parserFormat = "core_wasm")
        telemetry.captureTabSelected("Functions")

        assertEquals(1, client.initializeCalls)
        assertEquals(
            listOf(
                HexanaEvent.BinaryOpened(
                    format = HexanaEvent.BinaryFormat.WASM,
                    fileSizeBytes = 55,
                    surface = HexanaEvent.Surface.WEBAPP,
                ),
                HexanaEvent.HexTabSelected("Functions", HexanaEvent.Surface.WEBAPP),
            ),
            client.events,
        )
        assertEquals(listOf(false, true), client.consentUpdates)
        assertEquals("true", storage.values["hexana.webapp.telemetryEnabled"])
    }

    @Test
    fun disabledTelemetryNeverCapturesEvents() {
        val client = FakeBrowserAnalyticsClient()
        val telemetry = BrowserWebAppTelemetry(FakeTelemetryStorage(), client)

        telemetry.initialize()
        telemetry.captureBinaryOpened(fileSizeBytes = 55, parserFormat = "core_wasm")
        telemetry.captureTabSelected("Sections")

        assertTrue(client.events.isEmpty())
        assertEquals(listOf(false), client.consentUpdates)
        assertFalse(telemetry.isEnabled)
    }

    @Test
    fun tabSelectionUsesBrowserHostCaptureCallback() {
        val selectedTabs = mutableListOf<String>()
        val host = BrowserHost(
            currentFileName = { "module.wasm" },
            currentBytes = { null },
            openModule = { _, _, _ -> },
            navigateToParent = {},
            onScriptFilePicked = { _, _ -> },
            onScriptFileContent = { _, _ -> },
            onTabSelected = selectedTabs::add,
        )

        host.tabSelected("Functions")

        assertEquals(listOf("Functions"), selectedTabs)
    }

    @Test
    fun consentControlIsAccessibleAndReloadsPersistedChoice(): Promise<Unit> = Promise { resolve, reject ->
        val storage = FakeTelemetryStorage()
        val firstTelemetry = BrowserWebAppTelemetry(storage, FakeBrowserAnalyticsClient())
        val root = document.createElement("div").unsafeCast<HTMLDivElement>()
        root.id = "telemetry-consent-test-root"
        var composition: Composition? = null
        var completed = false
        var timeout = 0
        fun finish(failure: Throwable? = null) {
            if (completed) return
            completed = true
            window.clearTimeout(timeout)
            composition?.dispose()
            root.parentNode?.removeChild(root)
            if (failure == null) resolve(Unit) else reject(failure)
        }
        fun awaitLoaded() {
            val deadline = window.performance.now() + 2_000.0
            lateinit var poll: (Double) -> Unit
            poll = poll@ { timestamp ->
                try {
                    if (root.querySelector("[data-parser-worker-state='parsed']") != null) {
                        val toggle = root.querySelector("[data-telemetry-consent-toggle]")
                            .unsafeCast<HTMLButtonElement>()
                        assertEquals("Enable anonymous usage analytics", toggle.getAttribute("aria-label"))
                        assertEquals("false", toggle.getAttribute("aria-pressed"))
                        toggle.click()
                        window.requestAnimationFrame {
                            window.requestAnimationFrame {
                                try {
                                    assertEquals("true", toggle.getAttribute("aria-pressed"))
                                    assertTrue(BrowserWebAppTelemetry(storage, FakeBrowserAnalyticsClient()).isEnabled)
                                    finish()
                                } catch (failure: Throwable) {
                                    finish(failure)
                                }
                            }
                        }
                    } else if (timestamp >= deadline) {
                        finish(AssertionError("Telemetry control was not available after the binary loaded."))
                    } else {
                        window.requestAnimationFrame(poll)
                    }
                } catch (failure: Throwable) {
                    finish(failure)
                }
            }
            window.requestAnimationFrame(poll)
        }
        try {
            checkNotNull(document.body).appendChild(root)
            composition = renderComposable(rootElementId = root.id) {
                WebAppRoot(telemetryFactory = WebAppTelemetryFactory { firstTelemetry })
            }
            timeout = window.setTimeout({ finish(AssertionError("Telemetry control test timed out.")) }, 2_500)
            telemetryDrop(
                checkNotNull(root.querySelector("[data-file-drop-zone]")),
                js("new File([new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])], 'module.wasm')").unsafeCast<File>(),
            )
            awaitLoaded()
        } catch (failure: Throwable) {
            finish(failure)
        }
    }
}

private fun telemetryDrop(target: Element, file: File) {
    val event = js("new Event('drop', { bubbles: true, cancelable: true })")
    val files = js("({})")
    files.item = { index: Int -> if (index == 0) file else null }
    val dataTransfer = js("({})")
    dataTransfer.files = files
    event.dataTransfer = dataTransfer
    target.dispatchEvent(event.unsafeCast<Event>())
}

private class FakeTelemetryStorage : WebAppTelemetryStorage {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }
}

private class FakeBrowserAnalyticsClient : BrowserAnalyticsClient {
    var initializeCalls = 0
    val consentUpdates = mutableListOf<Boolean>()
    val events = mutableListOf<HexanaEvent>()

    override fun initialize() {
        initializeCalls++
    }

    override fun setConsent(enabled: Boolean) {
        consentUpdates += enabled
    }

    override fun capture(event: HexanaEvent) {
        events += event
    }

    override fun flush() = Unit

    override fun shutdown() = Unit
}
