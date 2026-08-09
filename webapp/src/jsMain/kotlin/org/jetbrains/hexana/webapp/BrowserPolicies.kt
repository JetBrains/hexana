package org.jetbrains.hexana.webapp

/**
 * The landing page intentionally admits every file selected by the browser.
 * Hexana detects the binary format after the file has been read.
 */
internal object BrowserFileAdmissionPolicy {
    fun accepts(fileName: String): Boolean = true
}

/** Browser-only capabilities that differ from the VS Code host. */
internal object BrowserCapabilityPolicy {
    const val canRunExports: Boolean = false
}
