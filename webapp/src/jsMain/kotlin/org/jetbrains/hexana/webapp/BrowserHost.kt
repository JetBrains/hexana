package org.jetbrains.hexana.webapp

import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.hexana.vscode.Host
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

private const val STORAGE_PREFIX = "hexana."

internal interface BrowserDownloadApi {
    fun createObjectUrl(blob: Blob): String

    fun revokeObjectUrl(objectUrl: String)

    fun createAnchor(): HTMLAnchorElement

    fun appendAnchor(anchor: HTMLAnchorElement)

    fun clickAnchor(anchor: HTMLAnchorElement)

    fun removeAnchor(anchor: HTMLAnchorElement)
}

private object DomBrowserDownloadApi : BrowserDownloadApi {
    override fun createObjectUrl(blob: Blob): String = URL.createObjectURL(blob)

    override fun revokeObjectUrl(objectUrl: String) {
        URL.revokeObjectURL(objectUrl)
    }

    override fun createAnchor(): HTMLAnchorElement =
        document.createElement("a").unsafeCast<HTMLAnchorElement>()

    override fun appendAnchor(anchor: HTMLAnchorElement) {
        document.body?.appendChild(anchor)
    }

    override fun clickAnchor(anchor: HTMLAnchorElement) {
        anchor.click()
    }

    override fun removeAnchor(anchor: HTMLAnchorElement) {
        anchor.parentNode?.removeChild(anchor)
    }
}

/**
 * [Host] implementation for the standalone browser webapp.
 *
 * File-system operations (saveAs, saveCopy, overwriteFile) trigger browser
 * downloads via the Blob + `<a download>` pattern. openModule / navigateToParent
 * are wired into the composable navigation stack via callbacks. Script-file
 * picking uses an `<input type=file>` element.
 *
 * [canRunExport] is false: running exports requires the VS Code extension.
 */
internal class BrowserHost(
    private val currentFileName: () -> String,
    private val currentBytes: () -> ByteArray?,
    private val openModule: (moduleIndex: Int, fileName: String, data: ByteArray) -> Unit,
    private val navigateToParent: () -> Unit,
    private val onScriptFilePicked: (path: String, content: String) -> Unit,
    private val onScriptFileContent: (path: String, content: String) -> Unit,
    private val downloadApi: BrowserDownloadApi = DomBrowserDownloadApi,
) : Host {
    private var scriptFileLoader: BrowserFileLoader? = null

    override val canRunExport: Boolean get() = BrowserCapabilityPolicy.canRunExports

    override fun showWat(watText: String, warning: String?) {
        downloadText(
            text = watText,
            name = currentFileName().substringAfterLast('/').substringAfterLast('\\')
                .let { base ->
                    val dot = base.lastIndexOf('.')
                    if (dot < 0) "$base.wat" else base.substring(0, dot) + ".wat"
                },
            mimeType = "text/plain",
        )
    }

    override fun runExport(
        exportName: String,
        invokeArgs: String,
        programArgs: String,
        isComponent: Boolean,
        wasmBytes: ByteArray?,
        runtime: String?,
        action: String?,
    ) {
        // canRunExport == false; the UI should not reach this path.
    }

    override fun tabSelected(tabName: String) {
        // No-op: analytics event only relevant for VS Code.
    }

    override fun updateRuntimeSetting(runtime: String) {
        localStorage("wasmRuntime", runtime)
    }

    override fun navigateToParent() = navigateToParent.invoke()

    override fun saveAs() {
        val bytes = currentBytes() ?: return
        downloadBytes(bytes, currentFileName().substringAfterLast('/').substringAfterLast('\\'))
    }

    override fun openZipEntry(
        name: String,
        dataOffset: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        compressionMethod: Int,
    ) {
        // v1 no-op: opening ZIP entries requires native decompression or extension-host help.
    }

    override fun openTarEntry(name: String, dataOffset: Long, size: Long) {
        // v1 no-op: opening TAR entries requires native decompression or extension-host help.
    }

    override fun overwriteFile(bytes: ByteArray) {
        // No FS write access in the browser — fall back to a download under the same name.
        downloadBytes(bytes, currentFileName().substringAfterLast('/').substringAfterLast('\\'))
    }

    override fun saveCopy(outputName: String, bytes: ByteArray) {
        downloadBytes(bytes, outputName)
    }

    override fun openUrl(url: String) {
        window.open(url, "_blank")
    }

    override fun openModule(moduleIndex: Int, fileName: String, data: ByteArray) {
        openModule.invoke(moduleIndex, fileName, data)
    }

    override fun openDts() {
        // VS Code-specific (opens in Monaco editor); no-op in the browser.
    }

    override fun openHttpBody(fileName: String, bodyBytes: ByteArray, httpContentType: String?) {
        // todo
    }

    override fun openTextFile(fileName: String, content: String) {
        downloadText(text = content, name = fileName, mimeType = "application/json")
    }

    override fun pickScriptFile() {
        openFilePicker(accept = ".js") { file ->
            if (file != null) {
                loadScriptFile(file) { name, content -> onScriptFilePicked(name, content) }
            }
        }
    }

    override fun readScriptFile(scriptPath: String) {
        // In the browser the "path" is just the file name used as a key;
        // we cannot re-read it from disk. The content was captured at pick time.
        // Trigger a re-pick so the user can refresh the file.
        openFilePicker(accept = ".js") { file ->
            if (file != null) {
                loadScriptFile(file) { _, content -> onScriptFileContent(scriptPath, content) }
            }
        }
    }

    override fun scriptTabAdded(scriptPath: String) {
        val existing = window.localStorage.getItem("${STORAGE_PREFIX}scriptPaths") ?: ""
        val paths = if (existing.isBlank()) mutableListOf() else existing.split("|").toMutableList()
        if (!paths.contains(scriptPath)) {
            paths.add(scriptPath)
            window.localStorage.setItem("${STORAGE_PREFIX}scriptPaths", paths.joinToString("|"))
        }
    }

    override fun scriptTabRemoved(scriptPath: String) {
        val existing = window.localStorage.getItem("${STORAGE_PREFIX}scriptPaths") ?: ""
        val paths = existing.split("|").filter { it != scriptPath }
        window.localStorage.setItem("${STORAGE_PREFIX}scriptPaths", paths.joinToString("|"))
    }

    override fun ready() {
        // No extension host to notify.
    }

    fun dispose() {
        scriptFileLoader?.dispose()
        scriptFileLoader = null
    }

    // -------------------------------------------------------------------------

    private fun localStorage(key: String, value: String) {
        window.localStorage.setItem("$STORAGE_PREFIX$key", value)
    }

    private fun loadScriptFile(file: org.w3c.files.File, onLoaded: (String, String) -> Unit) {
        scriptFileLoader?.dispose()
        lateinit var loader: BrowserFileLoader
        loader = BrowserFileLoader(
            onStatusChanged = { status ->
                if (status != BrowserFileLoadStatus.Reading && scriptFileLoader === loader) {
                    scriptFileLoader = null
                }
            },
            onFileLoaded = { loadedFile, bytes ->
                if (scriptFileLoader === loader) scriptFileLoader = null
                onLoaded(loadedFile.name, bytes.decodeToString())
            },
        )
        scriptFileLoader = loader
        loader.select(file)
    }

    private fun downloadBytes(bytes: ByteArray, name: String) {
        // Kotlin/JS ByteArray is Int8Array at runtime; reinterpret as Uint8Array without copying.
        val int8 = bytes.unsafeCast<Int8Array>()
        val uint8 = Uint8Array(int8.buffer, int8.byteOffset, bytes.size)
        val blob = Blob(arrayOf(uint8), BlobPropertyBag(type = "application/octet-stream"))
        downloadBlob(blob, name)
    }

    private fun downloadText(text: String, name: String, mimeType: String) {
        val blob = Blob(arrayOf(text), BlobPropertyBag(type = mimeType))
        downloadBlob(blob, name)
    }

    private fun downloadBlob(blob: Blob, name: String) {
        val objectUrl = downloadApi.createObjectUrl(blob)
        try {
            triggerDownload(objectUrl, name)
        } finally {
            downloadApi.revokeObjectUrl(objectUrl)
        }
    }

    private fun triggerDownload(objectUrl: String, name: String) {
        val anchor = downloadApi.createAnchor()
        try {
            anchor.href = objectUrl
            anchor.download = name
            downloadApi.appendAnchor(anchor)
            downloadApi.clickAnchor(anchor)
        } finally {
            downloadApi.removeAnchor(anchor)
        }
    }
}
