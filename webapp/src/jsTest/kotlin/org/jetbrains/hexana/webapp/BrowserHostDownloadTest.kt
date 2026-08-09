package org.jetbrains.hexana.webapp

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.files.Blob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BrowserHostDownloadTest {
    @Test
    fun successfulDownloadPairsObjectUrlAndRemovesTemporaryAnchor() {
        val downloadApi = RecordingDownloadApi()
        val host = host(downloadApi)

        host.saveCopy("copy.bin", byteArrayOf(0x00, 0x7f, -0x80, -0x01))

        assertEquals(1, downloadApi.createCalls)
        assertEquals(listOf("blob:hexana-test-1"), downloadApi.revokedUrls)
        assertEquals(1, downloadApi.appendCalls)
        assertEquals(1, downloadApi.clickCalls)
        assertEquals(1, downloadApi.removeCalls)
        assertFalse(downloadApi.anchorAttached)
    }

    @Test
    fun clickFailureStillRevokesObjectUrlOnceAndRemovesAnchor() {
        val downloadApi = RecordingDownloadApi(throwOnClick = true)
        val host = host(downloadApi)

        assertFailsWith<IllegalStateException> {
            host.saveCopy("copy.bin", byteArrayOf(0x00))
        }

        assertEquals(1, downloadApi.createCalls)
        assertEquals(listOf("blob:hexana-test-1"), downloadApi.revokedUrls)
        assertEquals(1, downloadApi.appendCalls)
        assertEquals(1, downloadApi.clickCalls)
        assertEquals(1, downloadApi.removeCalls)
        assertFalse(downloadApi.anchorAttached)
    }

    private fun host(downloadApi: BrowserDownloadApi): BrowserHost = BrowserHost(
        currentFileName = { "module.wasm" },
        currentBytes = { null },
        openModule = { _, _, _ -> },
        navigateToParent = {},
        onScriptFilePicked = { _, _ -> },
        onScriptFileContent = { _, _ -> },
        downloadApi = downloadApi,
    )
}

private class RecordingDownloadApi(
    private val throwOnClick: Boolean = false,
) : BrowserDownloadApi {
    var createCalls = 0
        private set
    val revokedUrls = mutableListOf<String>()
    var appendCalls = 0
        private set
    var clickCalls = 0
        private set
    var removeCalls = 0
        private set
    var anchorAttached = false
        private set

    override fun createObjectUrl(blob: Blob): String {
        createCalls++
        return "blob:hexana-test-$createCalls"
    }

    override fun revokeObjectUrl(objectUrl: String) {
        revokedUrls += objectUrl
    }

    override fun createAnchor(): HTMLAnchorElement =
        document.createElement("a").unsafeCast<HTMLAnchorElement>()

    override fun appendAnchor(anchor: HTMLAnchorElement) {
        appendCalls++
        anchorAttached = true
    }

    override fun clickAnchor(anchor: HTMLAnchorElement) {
        clickCalls++
        if (throwOnClick) error("synthetic click failure")
    }

    override fun removeAnchor(anchor: HTMLAnchorElement) {
        removeCalls++
        anchorAttached = false
    }
}
