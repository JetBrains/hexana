package org.jetbrains.hexana.webapp

import org.jetbrains.hexana.vscode.WebUiCapabilities

/**
 * The landing page is extension-independent because Hexana detects the binary
 * format after reading. Size admission is enforced before a reader is created.
 */
internal object BrowserFileAdmissionPolicy {
    fun accepts(@Suppress("UNUSED_PARAMETER") fileName: String, fileSizeBytes: Double): Boolean {
        return fileSizeBytes.isFinite() &&
            fileSizeBytes >= 0.0 &&
            fileSizeBytes <= MAX_BROWSER_FILE_SIZE_BYTES
    }
}

/** Browser-only capabilities that differ from the VS Code host. */
internal object BrowserCapabilityPolicy {
    const val canRunExports: Boolean = false

    fun createWebUiCapabilities(): WebUiCapabilities = WebUiCapabilities()
}
