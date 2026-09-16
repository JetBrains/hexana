package org.jetbrains.hexana.vscode

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.js.Promise

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
     * Whether the host can offer a file picker for an SSLKEYLOGFILE. False in
     * hosts with no file-system access, where the button is not rendered.
     */
    val canPickSslKeyLog: Boolean get() = false

    /**
     * Asks the host to pick an SSLKEYLOGFILE (NSS Key Log Format) for TLS
     * decryption of a pcap capture. The host replies with `ssl-key-log-picked`,
     * or stays silent if the user cancels.
     */
    fun pickSslKeyLog() {}

    /**
     * Whether the host can persist target runtimes to `.hexana/runtimes.json`. False where
     * there is no project directory to hold it — the browser webapp — and the runtime
     * settings dialog is then not offered.
     */
    val canEditRuntimeTargets: Boolean get() = false

    /**
     * Asks the host to write [json] to `.hexana/runtimes.json` in the project, and [schema]
     * to `.hexana/runtimes.schema.json` next to it. The host replies with
     * `runtime-targets-loaded` once the file is on disk, which is also how an edit made
     * outside the editor reaches the UI.
     *
     * @param schema the generated JSON Schema [json] references, so a hand-edit of the
     *   settings file gets completion and validation in whatever editor opens it
     */
    fun saveRuntimeTargets(json: String, schema: String) {}

    /**
     * Creates a runnable script and selects it, so the user never starts from an
     * empty file. The host replies with `script-file-picked`.
     *
     * @param template inline starter source; when null the host copies the
     *   bundled example named [fileName]
     * @param jsConfigContent JavaScript project config to write next to the
     *   script when its folder has none, so the editor resolves the scripting
     *   globals
     */
    fun createScriptFromTemplate(fileName: String, template: String? = null, jsConfigContent: String? = null)

    /**
     * Opens a script for editing, with the scripting type definitions attached so
     * completion and navigation work.
     */
    fun openScriptForEditing(scriptPath: String, jsConfigContent: String? = null)
    fun readScriptFile(scriptPath: String)

    /**
     * Whether the host can read an arbitrary local binary, which is what a script
     * tab's `open(path)` needs. False where there is no file system to reach —
     * the browser webapp — and `open(path)` then reports that.
     */
    val canOpenBinaryFile: Boolean get() = false

    /**
     * Reads the binary at [path] for a running script's `open(path)`.
     *
     * Returns a promise rather than the bytes because the hosts that can do this
     * do it out of process: the VS Code webview asks the extension host, which
     * owns file access. The promise rejects with a message meant for the user,
     * since the script sees it as the error its own `try/catch` receives.
     */
    fun readBinaryFile(path: String): Promise<ByteArray> =
        Promise.reject(UnsupportedOperationException("This host cannot read local files."))
    fun scriptTabAdded(scriptPath: String)
    fun scriptTabRemoved(scriptPath: String)
    fun ready()
}

/**
 * CompositionLocal providing the current [Host]. Must be provided at the root
 * composable via [androidx.compose.runtime.CompositionLocalProvider].
 */
val LocalHost = staticCompositionLocalOf<Host> { error("Host not provided") }