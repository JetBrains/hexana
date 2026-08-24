package org.jetbrains.hexana.vscode

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Abstracts the boundary between the host environment (e.g. VS Code extension)
 * and the host-agnostic Compose-for-Web UI in [web-ui].
 *
 * Each method corresponds to one outbound message the UI sends to the host.
 */
interface Host {
    /** Whether the host can run Wasm exports. False in the browser webapp. */
    val canRunExport: Boolean get() = true
    fun showWat(watText: String, warning: String?)
    fun runExport(
        exportName: String,
        invokeArgs: String,
        programArgs: String,
        isComponent: Boolean,
        wasmBytes: ByteArray?,
        runtime: String?,
        action: String?,
    )
    fun tabSelected(tabName: String)
    fun updateRuntimeSetting(runtime: String)
    fun navigateToParent()
    fun saveAs()
    fun openZipEntry(
        name: String,
        dataOffset: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        compressionMethod: Int,
    )
    fun openTarEntry(name: String, dataOffset: Long, size: Long)
    fun overwriteFile(bytes: ByteArray)
    fun saveCopy(outputName: String, bytes: ByteArray)
    fun openUrl(url: String)
    fun openModule(moduleIndex: Int, fileName: String, data: ByteArray)
    /**
     * Opens a bundled scripting reference. With [raw] set, opens the
     * format-specific type dump for `binary.raw` instead of the curated API.
     */
    fun openDts(raw: Boolean = false)
    fun openHttpBody(fileName: String, bodyBytes: ByteArray, httpContentType: String? = null, httpContentEncoding: String? = null)
    fun openTextFile(fileName: String, content: String)
    fun pickScriptFile()

    /**
     * Creates a runnable script and selects it, so the user never starts from an
     * empty file. The host replies with `script-file-picked`.
     *
     * @param template inline starter source; when null the host copies the
     *   bundled example named [fileName]
     */
    fun createScriptFromTemplate(fileName: String, template: String? = null)
    fun readScriptFile(scriptPath: String)
    fun scriptTabAdded(scriptPath: String)
    fun scriptTabRemoved(scriptPath: String)
    fun ready()
}

/**
 * CompositionLocal providing the current [Host]. Must be provided at the root
 * composable via [androidx.compose.runtime.CompositionLocalProvider].
 */
val LocalHost = staticCompositionLocalOf<Host> { error("Host not provided") }