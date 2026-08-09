package org.jetbrains.hexana.webapp

import kotlinx.browser.document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserPoliciesTest {
    @Test
    fun landingAdmitsFilesRegardlessOfTheirNameOrExtension() {
        assertTrue(BrowserFileAdmissionPolicy.accepts("module.wasm"))
        assertTrue(BrowserFileAdmissionPolicy.accepts("firmware.bin"))
        assertTrue(BrowserFileAdmissionPolicy.accepts("unrecognized.payload"))
    }

    @Test
    fun browserTestsRunWithARealDom() {
        val input = document.createElement("input")
        input.setAttribute("type", "file")

        assertEquals("file", input.getAttribute("type"))
    }

    @Test
    fun browserHostDoesNotExposeExportExecution() {
        val host = BrowserHost(
            currentFileName = { "module.wasm" },
            currentBytes = { null },
            openModule = { _, _, _ -> },
            navigateToParent = {},
            onScriptFilePicked = { _, _ -> },
            onScriptFileContent = { _, _ -> },
        )

        assertFalse(host.canRunExport)
    }

    @Test
    fun publicWebUiCapabilitiesExcludeRemoteSbomChecks() {
        val capabilities = BrowserCapabilityPolicy.createWebUiCapabilities()

        assertNull(capabilities.sbomVulnerabilityCheck)
    }
}
