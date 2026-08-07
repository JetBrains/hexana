package org.jetbrains.hexana.webapp

import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.hexana.vscode.Host
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

private const val STORAGE_PREFIX = "hexana."

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
class BrowserHost(
    private val currentFileName: () -> String,
    private val currentBytes: () -> ByteArray?,
    private val openModule: (moduleIndex: Int, fileName: String, data: ByteArray) -> Unit,
    private val navigateToParent: () -> Unit,
    private val onScriptFilePicked: (path: String, content: String) -> Unit,
    private val onScriptFileContent: (path: String, content: String) -> Unit,
) : Host {

    override val canRunExport: Boolean get() = false

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

    override fun pickScriptFile() {
        val input = document.createElement("input").unsafeCast<HTMLInputElement>()
        input.type = "file"
        input.accept = ".js"
        input.onchange = {
            val file = input.files?.item(0)
            if (file != null) {
                readFile(file) { name, bytes ->
                    val text = bytes.decodeToString()
                    onScriptFilePicked(name, text)
                }
            }
        }
        input.click()
    }

    override fun readScriptFile(scriptPath: String) {
        // In the browser the "path" is just the file name used as a key;
        // we cannot re-read it from disk. The content was captured at pick time.
        // Trigger a re-pick so the user can refresh the file.
        val input = document.createElement("input").unsafeCast<HTMLInputElement>()
        input.type = "file"
        input.accept = ".js"
        input.onchange = {
            val file = input.files?.item(0)
            if (file != null) {
                readFile(file) { _, bytes ->
                    onScriptFileContent(scriptPath, bytes.decodeToString())
                }
            }
        }
        input.click()
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

    // -------------------------------------------------------------------------

    private fun localStorage(key: String, value: String) {
        window.localStorage.setItem("$STORAGE_PREFIX$key", value)
    }

    private fun downloadBytes(bytes: ByteArray, name: String) {
        val uint8 = js("new Uint8Array(bytes.length)")
        for (i in bytes.indices) {
            uint8[i] = bytes[i].toInt() and 0xFF
        }
        val blob = Blob(arrayOf(uint8), BlobPropertyBag(type = "application/octet-stream"))
        triggerDownload(URL.createObjectURL(blob), name)
    }

    private fun downloadText(text: String, name: String, mimeType: String) {
        val blob = Blob(arrayOf(text), BlobPropertyBag(type = mimeType))
        triggerDownload(URL.createObjectURL(blob), name)
    }

    private fun triggerDownload(objectUrl: String, name: String) {
        val a = document.createElement("a").unsafeCast<HTMLAnchorElement>()
        a.href = objectUrl
        a.download = name
        document.body?.appendChild(a)
        a.click()
        document.body?.removeChild(a)
        URL.revokeObjectURL(objectUrl)
    }
}