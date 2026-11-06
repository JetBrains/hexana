package org.jetbrains.hexana.vscode

import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.window
import org.jetbrains.compose.web.css.selectors.CSSSelector.PseudoClass.host
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.pcap.HttpHeader
import org.jetbrains.hexana.shared.WasmImportStubGenerator
import org.jetbrains.hexana.shared.componentImportNames
import org.jetbrains.hexana.shared.isRuntimeImport
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import kotlin.js.Promise

/**
 * Decoded message received from the VS Code extension host via `postMessage`.
 */
data class VscodeMessage(
    val type: String,
    val fileName: String = "",
    val filePath: String = "",
    /** Correlates a reply with the request that asked for it. */
    val requestId: String = "",
    val data: ByteArray? = null,
    val wasmRuntime: String? = null,
    val scriptPath: String = "",
    val scriptContent: String = "",
    val scriptPaths: List<String> = emptyList(),
    val leftData: ByteArray? = null,
    val leftName: String = "",
    val rightData: ByteArray? = null,
    val rightName: String = "",
    /** Which view a diff opens on: `"hex"` (default) or `"hexana"`. */
    val diffMode: String = "",
    val message: String = "",
    val parentData: ByteArray? = null,
    val moduleIndex: Int = -1,
    val showSemanticHighlighting: Boolean? = null,
    val settingKey: String = "",
    val settingValue: Boolean = false,
    val parentFileName: String? = null,
    val decompressed: Boolean = false,
    /** `load-wasm`: size on disk when [data] is only the head of a file too large to load, else `null`. */
    val fileSize: Long? = null,
    /** The host found an Android SDK with the emulator, and the file is an on-disk APK. */
    val canRunApk: Boolean = false,
    val keyLogPath: String = "",
    val keyLogContent: String = "",
    /** Raw `features.json`: the host's ETag-validated cache, or the snapshot it ships. */
    val featureCatalogJson: String = "",
    /** Raw `.hexana/runtimes.json`; blank when the workspace declares no runtimes. */
    val runtimeTargetsJson: String = "",
    /** Raw `.hexana/file_associations.json`; blank when no file has a parser chosen. */
    val fileAssociationsJson: String = "",
    /**
     * `load-wasm`: the key the open file is stored under in [fileAssociationsJson], or blank
     * when it cannot carry a parser choice (not on disk, or outside the workspace).
     */
    val fileAssociationKey: String = "",
    /**
     * `load-wasm` / `provider-scripts-loaded`: the scripts in `.hexana/providers/` governing
     * the file, or `null` when the message does not carry them.
     */
    val providerScripts: List<ProviderScriptSource>? = null,
    /** The scripts exist but were not sent because the workspace is not trusted. */
    val providerScriptsUntrusted: Boolean = false,
    /** `provider-script-created`: the id of the provider the new script registers. */
    val providerId: String = "",
    /** `select-table-row`: the table tab to select a row of. */
    val tabName: String = "",
    /** `select-table-row`: the top-level descriptor row to select. */
    val rowIndex: Int = -1,
)

/**
 * Reference to the VS Code webview API acquired once at startup.
 */
private val vscodeApi: dynamic by lazy {
    js("acquireVsCodeApi()")
}

/**
 * Posts a message back to the VS Code extension host.
 */
fun postMessageToHost(type: String) {
    val msg = js("{}")
    msg.type = type
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to run an exported Wasm function.
 *
 * [exportName] is the function name to invoke (e.g. `"add"` or `"_start"`).
 * [invokeArgs] is the optional wasmtime `--invoke` argument string.
 * [programArgs] is the optional program arguments string.
 * [isComponent] indicates whether this is a component-model binary.
 * [wasmBytes] when provided, is used to generate import stubs and detect
 * component imports so the extension host can resolve dependencies and
 * compose components before running.
 * [runtime] when provided, overrides the configured runtime so the
 * extension host can skip the native picker dialog.
 * [action] when provided (`"run"` or `"debug"`), tells the extension host
 * which action the user chose in the webview dialog.
 */
fun postRunExportToHost(
    exportName: String,
    invokeArgs: String = "",
    programArgs: String = "",
    isComponent: Boolean = false,
    wasmBytes: ByteArray? = null,
    runtime: String? = null,
    action: String? = null,
) {
    val msg = js("{}")
    msg.type = "run-export"
    msg.exportName = exportName
    msg.invokeArgs = invokeArgs
    msg.programArgs = programArgs
    msg.isComponent = isComponent
    if (runtime != null) {
        msg.runtime = runtime
    }
    if (action != null) {
        msg.action = action
    }

    if (wasmBytes != null) {
        if (!isComponent) {
            // Core module: generate stub modules for non-function imports
            val wasmBuffer = wasmBytes.toCommonByteBuffer()
            val hasImports = WasmImportStubGenerator.hasImports(wasmBuffer)
            msg.hasImports = hasImports
            if (hasImports && WasmImportStubGenerator.hasNonFunctionImports(wasmBuffer)) {
                val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasmBuffer)
                val jsStubs = js("[]")
                for (stub in stubs) {
                    val jsStub = js("{}")
                    jsStub.moduleName = stub.moduleName
                    val jsBytes = js("[]")
                    for (b in stub.wasmBytes) {
                        jsBytes.push(b.toInt() and 0xFF)
                    }
                    jsStub.wasmBytes = jsBytes
                    jsStubs.push(jsStub)
                }
                msg.stubs = jsStubs
            }
        } else {
            // Component model: collect non-runtime import names for dependency resolution
            val importNames = componentImportNames(wasmBytes)
                .filter { !isRuntimeImport(it) }
            if (importNames.isNotEmpty()) {
                val jsNames = js("[]")
                for (name in importNames) {
                    jsNames.push(name)
                }
                msg.componentImports = jsNames
            }
        }
    }

    vscodeApi.postMessage(msg)
}

/**
 * Notifies the extension host that the user selected a tab in the analysis
 * panel, so the host can log an analytics event.
 *
 * [tabName] is the display label of the selected tab (e.g. "Functions").
 */
fun postTabSelectedToHost(tabName: String) {
    val msg = js("{}")
    msg.type = "tab-selected"
    msg.tabName = tabName
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open the WAT text in a native VS Code editor
 * tab (Monaco) with syntax highlighting and full editor features.
 *
 * [watText] is the WAT text produced by the wasm-to-wat converter.
 * [warning] is an optional conversion warning to display to the user.
 */
fun postShowWatToHost(watText: String, warning: String? = null) {
    val msg = js("{}")
    msg.type = "show-wat"
    msg.watText = watText
    if (warning != null) {
        msg.warning = warning
    }
    vscodeApi.postMessage(msg)
}

/**
 * Tells the extension host to update the `hexana.wasmRuntime` VS Code
 * setting to [runtime], so the user's choice persists across sessions.
 */
fun postUpdateRuntimeSettingToHost(runtime: String) {
    val msg = js("{}")
    msg.type = "update-runtime-setting"
    msg.runtime = runtime
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to navigate back to the parent component that
 * contains this submodule.
 */
fun postNavigateToParent() {
    val msg = js("{}")
    msg.type = "navigate-to-parent"
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to show a Save As dialog for the current
 * submodule / archive entry content.
 */
fun postSaveAsToHost() {
    val msg = js("{}")
    msg.type = "save-as"
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open a ZIP entry in a new editor tab.
 *
 * The extension host reads the compressed bytes from the archive document,
 * decompresses them, and opens the result with the appropriate editor.
 */
fun postOpenZipEntryToHost(
    name: String,
    dataOffset: Long,
    compressedSize: Long,
    uncompressedSize: Long,
    compressionMethod: Int,
) {
    val msg = js("{}")
    msg.type = "open-zip-entry"
    msg.name = name
    msg.dataOffset = dataOffset.toDouble()
    msg.compressedSize = compressedSize.toDouble()
    msg.uncompressedSize = uncompressedSize.toDouble()
    msg.compressionMethod = compressionMethod
    vscodeApi.postMessage(msg)
}

/**
 * Like [postOpenZipEntryToHost], and then has the host select top-level row [rowIndex] of table
 * tab [tabName] in the entry's editor — reusing that editor when it is already open.
 */
fun postRevealZipEntryRowToHost(
    name: String,
    dataOffset: Long,
    compressedSize: Long,
    uncompressedSize: Long,
    compressionMethod: Int,
    tabName: String,
    rowIndex: Int,
) {
    val msg = js("{}")
    msg.type = "open-zip-entry"
    msg.name = name
    msg.dataOffset = dataOffset.toDouble()
    msg.compressedSize = compressedSize.toDouble()
    msg.uncompressedSize = uncompressedSize.toDouble()
    msg.compressionMethod = compressionMethod
    msg.selectTabName = tabName
    msg.selectRowIndex = rowIndex
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open a TAR entry in a new editor tab.
 *
 * TAR entries are stored uncompressed, so the extension host simply
 * slices the raw bytes from the archive document.
 */
fun postOpenTarEntryToHost(
    name: String,
    dataOffset: Long,
    size: Long,
) {
    val msg = js("{}")
    msg.type = "open-tar-entry"
    msg.name = name
    msg.dataOffset = dataOffset.toDouble()
    msg.size = size.toDouble()
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to overwrite the original file with [bytes].
 */
fun postOverwriteFileToHost(bytes: ByteArray) {
    val msg = js("{}")
    msg.type = "overwrite-file"
    val jsArray = js("[]").unsafeCast<dynamic>()
    for (i in bytes.indices) {
        jsArray.push(bytes[i].toInt() and 0xFF)
    }
    msg.data = jsArray
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to save [bytes] as a sibling copy of the
 * original file with the given [outputName].
 */
fun postSaveCopyToHost(outputName: String, bytes: ByteArray) {
    val msg = js("{}")
    msg.type = "save-copy"
    msg.outputName = outputName
    val jsArray = js("[]").unsafeCast<dynamic>()
    for (i in bytes.indices) {
        jsArray.push(bytes[i].toInt() and 0xFF)
    }
    msg.data = jsArray
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open an external URL in the user's default browser.
 */
fun postOpenUrlToHost(url: String) {
    val msg = js("{}")
    msg.type = "open-url"
    msg.url = url
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open a nested module in a new editor tab.
 *
 * [moduleIndex] is the zero-based index of the module inside the component,
 * [fileName] is the suggested tab title, and [data] is the raw byte content
 * of the nested core module.
 */
fun postOpenModuleToHost(moduleIndex: Int, fileName: String, data: ByteArray) {
    val msg = js("{}")
    msg.type = "open-module"
    msg.moduleIndex = moduleIndex
    msg.fileName = fileName
    val jsArray = js("[]").unsafeCast<dynamic>()
    for (i in data.indices) {
        jsArray.push(data[i].toInt() and 0xFF)
    }
    msg.data = jsArray
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open an HTTP response body extracted from a
 * PCAP packet in a new editor tab.
 *
 * [fileName] is the suggested tab title (e.g. "http-body-packet-3.json"),
 * [data] is the raw body bytes, and [contentType] is the HTTP Content-Type
 * header value (if available) so the host can choose the right language mode.
 */
fun postOpenHttpBodyToHost(fileName: String, data: ByteArray, contentType: String?, contentEncoding: String?) {
    val msg = js("{}")
    msg.type = "open-http-body"
    msg.fileName = fileName
    msg.contentType = contentType ?: ""
    msg.contentEncoding = contentEncoding ?: ""
    val jsArray = js("[]").unsafeCast<dynamic>()
    for (i in data.indices) {
        jsArray.push(data[i].toInt() and 0xFF)
    }
    msg.data = jsArray
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open a pre-built scripting reference in a new
 * VS Code editor tab: the curated `hexana-scripting.d.ts`, or with [raw] set the
 * format-specific `hexana-scripting-raw.d.ts` for `binary.raw`.
 */
fun postOpenDtsToHost(raw: Boolean = false) {
    val msg = js("{}")
    msg.type = "open-dts"
    msg.raw = raw
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to create a runnable script — either from the inline
 * [template] or by copying the bundled example named [fileName] — open it in an
 * editor, and reply with `script-file-picked`.
 */
fun postCreateScriptFromTemplateToHost(fileName: String, template: String?, jsConfigContent: String?) {
    val msg = js("{}")
    msg.type = "create-script-from-template"
    msg.fileName = fileName
    if (template != null) msg.template = template
    if (jsConfigContent != null) msg.jsConfigContent = jsConfigContent
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open a script for editing with the scripting type
 * definitions attached, writing [jsConfigContent] next to it when its folder has
 * no JavaScript project config.
 */
fun postOpenScriptForEditingToHost(scriptPath: String, jsConfigContent: String?) {
    val msg = js("{}")
    msg.type = "open-script-for-editing"
    msg.scriptPath = scriptPath
    if (jsConfigContent != null) msg.jsConfigContent = jsConfigContent
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to show a file picker for `.js` script files.
 * The host will respond with a `script-file-picked` message containing
 * the file path and content.
 */
fun postPickScriptFileToHost() {
    val msg = js("{}")
    msg.type = "pick-script-file"
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to show a file picker for an SSLKEYLOGFILE.
 * The host will respond with an `ssl-key-log-picked` message carrying the
 * file path and its text content, or stay silent if the user cancels.
 */
fun postPickSslKeyLogToHost() {
    val msg = js("{}")
    msg.type = "pick-ssl-key-log"
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to write [json] to `.hexana/runtimes.json` in the workspace, and
 * [schema] to `.hexana/runtimes.schema.json` next to it.
 * The host replies with `runtime-targets-loaded` once the file is on disk.
 */
fun postSaveRuntimeTargetsToHost(json: String, schema: String) {
    val msg = js("{}")
    msg.type = "save-runtime-targets"
    msg.json = json
    msg.schema = schema
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to write [json] to `.hexana/file_associations.json` in the
 * workspace, and [schema] next to it. The host replies with `file-associations-loaded`.
 */
fun postSaveFileAssociationsToHost(json: String, schema: String) {
    val msg = js("{}")
    msg.type = "save-file-associations"
    msg.json = json
    msg.schema = schema
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open `.hexana/file_associations.json` in a text editor, writing
 * [json] and [schema] first when the file does not exist yet.
 */
fun postOpenFileAssociationsFileToHost(json: String, schema: String) {
    val msg = js("{}")
    msg.type = "open-file-associations-file"
    msg.json = json
    msg.schema = schema
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to create a provider script from [template] (placeholders unfilled),
 * with the scripting types and [jsConfig] beside it. The host replies with `provider-script-created`.
 */
fun postCreateProviderScriptToHost(template: String, jsConfig: String) {
    val msg = js("{}")
    msg.type = "create-provider-script"
    msg.template = template
    msg.jsConfigContent = jsConfig
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open a text document in a new VS Code editor tab.
 *
 * [fileName] is used as the suggested file name (e.g. "foo.cdx.json").
 * [content] is the raw text content of the document.
 */
fun postOpenTextFileToHost(fileName: String, content: String) {
    val msg = js("{}")
    msg.type = "open-text-file"
    msg.fileName = fileName
    msg.content = content
    vscodeApi.postMessage(msg)
}

/**
 * [payload] as bytes, or null when it is absent or is not the array-like the
 * extension host is supposed to post.
 *
 * The message bus is untyped, so a wrong payload shape can only be caught here.
 * This reports it by returning null instead of throwing, because it runs inside
 * the inbound message listener: an exception here escapes the listener, and
 * anything waiting on that message — an `open(path)` holding a script
 * suspended, for one — would wait forever.
 */
internal fun messageBytesOrNull(payload: dynamic): ByteArray? {
    if (payload == null) return null
    // The host posts a Uint8Array, which arrives as this webview's own copy: viewed in place
    // as the Int8Array a Kotlin/JS ByteArray is, rather than copied byte by byte — the copy
    // doubled the memory of a large file and took seconds over hundreds of megabytes.
    // Tested on an expression, not on `payload`: a smart cast would re-type the dynamic payload
    // for the array-like path below and break its dynamic `length` access.
    if (payload.unsafeCast<Any>() is Uint8Array) {
        val view = payload.unsafeCast<Uint8Array>()
        return Int8Array(view.buffer, view.byteOffset, view.byteLength).unsafeCast<ByteArray>()
    }
    val length = (payload.length as? Number)?.toInt() ?: return null
    if (length < 0) return null
    val bytes = ByteArray(length)
    for (index in 0 until length) {
        val element = (payload[index] as? Number) ?: return null
        bytes[index] = element.toByte()
    }
    return bytes
}

/**
 * How long a script waits for one `open(path)` before it gives up.
 *
 * A script tab shows "Running script" for as long as the run is unfinished, and
 * a suspended `open` keeps the run unfinished, so a reply that never comes is a
 * spinner that never stops. Deadline included, the worst case is an error the
 * script author can act on. It is generous because the budget covers reading up
 * to 100 MiB off a disk that may be a network mount.
 */
private const val SCRIPT_OPEN_TIMEOUT_MS = 120_000

/**
 * Pending `open(path)` reads a script tab is waiting on.
 *
 * The webview cannot read files, so `open(path)` becomes a round-trip to the
 * extension host: this holds the resolver for each in-flight request until
 * `binary-file-content` comes back. Ids are local and monotonic, and the reply is
 * matched on the request rather than on the path, because a script may open the
 * same path more than once.
 */
private object ScriptBinaryReads {
    /** One read waiting for its reply, and the deadline that gives up on it. */
    private class PendingRead(val answer: (Result<ByteArray>) -> Unit) {
        var deadline: Int = -1
    }

    private var nextId = 0
    private val pending = mutableMapOf<String, PendingRead>()

    fun request(path: String): Promise<ByteArray> {
        val requestId = "script-open-${nextId++}"
        return Promise { resolve, reject ->
            val read = PendingRead { result ->
                result.fold({ bytes -> resolve(bytes) }, { error -> reject(error) })
            }
            pending[requestId] = read
            val msg = js("({})")
            msg.type = "read-binary-file"
            msg.requestId = requestId
            msg.path = path
            vscodeApi.postMessage(msg)
            read.deadline = window.setTimeout({
                settle(
                    requestId,
                    Result.failure(RuntimeException(
                        "open(\"$path\") — the editor did not answer within " +
                            "${SCRIPT_OPEN_TIMEOUT_MS / 1000} seconds.",
                    )),
                )
            }, SCRIPT_OPEN_TIMEOUT_MS)
        }
    }

    /** Answers the request [requestId], if it is still waiting. */
    fun complete(requestId: String, bytes: ByteArray?, error: String?) {
        val reason = error?.takeIf { it.isNotBlank() }
        settle(
            requestId,
            if (bytes != null && reason == null) {
                Result.success(bytes)
            } else {
                Result.failure(RuntimeException(reason ?: "The file could not be read."))
            },
        )
    }

    /**
     * Fails every waiting read with [reason]. Called when a reply arrives that
     * cannot be decoded: the request id may be the unreadable part, so there is
     * no safe way to tell which read it belonged to, and failing all of them is
     * the only answer that cannot leave a script suspended forever.
     */
    fun failAll(reason: String) {
        val waiting = pending.keys.toList()
        for (requestId in waiting) {
            settle(requestId, Result.failure(RuntimeException(reason)))
        }
    }

    /** Answers [requestId] once; later answers, including the deadline, are dropped. */
    private fun settle(requestId: String, result: Result<ByteArray>) {
        val read = pending.remove(requestId) ?: return
        if (read.deadline >= 0) window.clearTimeout(read.deadline)
        read.answer(result)
    }
}

/**
 * Replayed HTTP requests waiting for the extension host's answer. The host
 * owns the network - the webview's CSP and CORS would stop the request - and
 * answers each `http-replay-send` with exactly one `http-replay-response`.
 * The deadline outlasts the host's own timeout, so it only fires when the
 * reply itself got lost.
 */
private object HttpReplaySends {
    private var nextId = 0
    private val pending = mutableMapOf<String, (Result<HttpReplayResponse>) -> Unit>()

    fun request(method: String, url: String, headers: List<HttpHeader>, body: ByteArray): Promise<HttpReplayResponse> {
        val requestId = "http-replay-${nextId++}"
        return Promise { resolve, reject ->
            var deadline = -1
            pending[requestId] = { result ->
                if (deadline >= 0) window.clearTimeout(deadline)
                result.fold({ resolve(it) }, { reject(it) })
            }
            val msg = js("({})")
            msg.type = "http-replay-send"
            msg.requestId = requestId
            msg.method = method
            msg.url = url
            val jsHeaders = js("[]").unsafeCast<dynamic>()
            for (header in headers) jsHeaders.push(arrayOf(header.name, header.value))
            msg.headers = jsHeaders
            val jsBody = js("[]").unsafeCast<dynamic>()
            for (b in body) jsBody.push(b.toInt() and 0xFF)
            msg.body = jsBody
            vscodeApi.postMessage(msg)
            deadline = window.setTimeout({
                settle(requestId, Result.failure(RuntimeException("The editor did not answer.")))
            }, HTTP_REPLAY_TIMEOUT_MS)
        }
    }

    /** Settles the request a `http-replay-response` [data] answers. */
    fun complete(data: dynamic) {
        val requestId = (data.requestId as? String) ?: return
        val error = (data.error as? String)?.takeIf { it.isNotBlank() }
        val result = if (error != null) {
            Result.failure(RuntimeException(error))
        } else {
            val headers = mutableListOf<HttpHeader>()
            val jsHeaders = data.headers
            val count = (jsHeaders?.length as? Number)?.toInt() ?: 0
            for (i in 0 until count) {
                val pair = jsHeaders[i]
                headers += HttpHeader((pair[0] as? String).orEmpty(), (pair[1] as? String).orEmpty())
            }
            Result.success(
                HttpReplayResponse(
                    status = (data.status as? Number)?.toInt() ?: 0,
                    statusText = (data.statusText as? String).orEmpty(),
                    headers = headers,
                    body = messageBytesOrNull(data.body) ?: ByteArray(0),
                    bodyTruncated = data.bodyTruncated == true,
                    elapsedMillis = (data.elapsedMs as? Number)?.toLong() ?: 0L,
                ),
            )
        }
        settle(requestId, result)
    }

    private fun settle(requestId: String, result: Result<HttpReplayResponse>) {
        pending.remove(requestId)?.invoke(result)
    }
}

/** Longer than the host's own 60-second replay timeout. */
private const val HTTP_REPLAY_TIMEOUT_MS = 75_000

/**
 * Asks the extension host to write a replayed request as an `.http` file -
 * and its body next to it, when [bodyFileName] is set - and open it for the
 * REST Client extension.
 */
fun postOpenHttpRequestFileToHost(fileName: String, text: String, bodyFileName: String?, body: ByteArray) {
    val msg = js("{}")
    msg.type = "open-http-request-file"
    msg.fileName = fileName
    msg.text = text
    msg.bodyFileName = bodyFileName ?: ""
    val jsArray = js("[]").unsafeCast<dynamic>()
    for (b in body) jsArray.push(b.toInt() and 0xFF)
    msg.body = jsArray
    vscodeApi.postMessage(msg)
}

/** Answers a pending `open(path)`; called from the inbound message listener. */
internal fun completeScriptBinaryRead(requestId: String, bytes: ByteArray?, error: String?) =
    ScriptBinaryReads.complete(requestId, bytes, error)

/**
 * Asks the extension host to re-read a script file's content.
 * The host will respond with a `script-file-content` message.
 */
fun postReadScriptFileToHost(scriptPath: String) {
    val msg = js("{}")
    msg.type = "read-script-file"
    msg.scriptPath = scriptPath
    vscodeApi.postMessage(msg)
}

/**
 * Notifies the extension host that a script tab was added, so it can
 * persist the script path.
 */
fun postScriptTabAddedToHost(scriptPath: String) {
    val msg = js("{}")
    msg.type = "script-tab-added"
    msg.scriptPath = scriptPath
    vscodeApi.postMessage(msg)
}

/**
 * Notifies the extension host that a script tab was removed, so it can
 * update the persisted script paths.
 */
fun postScriptTabRemovedToHost(scriptPath: String) {
    val msg = js("{}")
    msg.type = "script-tab-removed"
    msg.scriptPath = scriptPath
    vscodeApi.postMessage(msg)
}

/**
 * [Host] implementation that delegates all calls to the VS Code extension host
 * via [vscodeApi.postMessage].
 */
class VscodeHost(override val canRunApk: Boolean = false) : Host {
    override fun showWat(watText: String, warning: String?) = postShowWatToHost(watText, warning)
    override fun runExport(
        exportName: String, invokeArgs: String, programArgs: String,
        isComponent: Boolean, wasmBytes: ByteArray?, runtime: String?, action: String?,
    ) = postRunExportToHost(exportName, invokeArgs, programArgs, isComponent, wasmBytes, runtime, action)
    override fun tabSelected(tabName: String) = postTabSelectedToHost(tabName)
    override fun updateRuntimeSetting(runtime: String) = postUpdateRuntimeSettingToHost(runtime)
    override fun navigateToParent() = postNavigateToParent()
    override fun saveAs() = postSaveAsToHost()
    override fun openZipEntry(
        name: String, dataOffset: Long, compressedSize: Long, uncompressedSize: Long, compressionMethod: Int,
    ) = postOpenZipEntryToHost(name, dataOffset, compressedSize, uncompressedSize, compressionMethod)
    override fun openTarEntry(name: String, dataOffset: Long, size: Long) = postOpenTarEntryToHost(name, dataOffset, size)
    override val canRevealZipEntryRow: Boolean get() = true
    override fun revealZipEntryRow(
        name: String, dataOffset: Long, compressedSize: Long, uncompressedSize: Long, compressionMethod: Int,
        tabName: String, rowIndex: Int,
    ) = postRevealZipEntryRowToHost(
        name, dataOffset, compressedSize, uncompressedSize, compressionMethod, tabName, rowIndex,
    )
    override fun overwriteFile(bytes: ByteArray) = postOverwriteFileToHost(bytes)
    override fun saveCopy(outputName: String, bytes: ByteArray) = postSaveCopyToHost(outputName, bytes)
    override fun openUrl(url: String) = postOpenUrlToHost(url)
    override fun openModule(moduleIndex: Int, fileName: String, data: ByteArray) =
        postOpenModuleToHost(moduleIndex, fileName, data)
    override fun openDts(raw: Boolean) = postOpenDtsToHost(raw)
    override fun openHttpBody(fileName: String, bodyBytes: ByteArray, httpContentType: String?, httpContentEncoding: String?) =
        postOpenHttpBodyToHost(fileName, bodyBytes, httpContentType, httpContentEncoding)
    override fun openTextFile(fileName: String, content: String) = postOpenTextFileToHost(fileName, content)
    override fun pickScriptFile() = postPickScriptFileToHost()
    override val canPickSslKeyLog: Boolean get() = true
    override fun pickSslKeyLog() = postPickSslKeyLogToHost()
    override fun runApk() = postMessageToHost("run-apk")
    override val canEditRuntimeTargets: Boolean get() = true
    override fun saveRuntimeTargets(json: String, schema: String) =
        postSaveRuntimeTargetsToHost(json, schema)
    override val canEditFileAssociations: Boolean get() = true
    override fun saveFileAssociations(json: String, schema: String) =
        postSaveFileAssociationsToHost(json, schema)
    override fun openFileAssociationsFile(json: String, schema: String) =
        postOpenFileAssociationsFileToHost(json, schema)
    override fun createProviderScript(template: String, jsConfig: String) =
        postCreateProviderScriptToHost(template, jsConfig)
    override fun createScriptFromTemplate(fileName: String, template: String?, jsConfigContent: String?) =
        postCreateScriptFromTemplateToHost(fileName, template, jsConfigContent)
    override fun openScriptForEditing(scriptPath: String, jsConfigContent: String?) =
        postOpenScriptForEditingToHost(scriptPath, jsConfigContent)
    override fun readScriptFile(scriptPath: String) = postReadScriptFileToHost(scriptPath)
    override val canOpenBinaryFile: Boolean get() = true
    override fun readBinaryFile(path: String): Promise<ByteArray> = ScriptBinaryReads.request(path)
    override val canReplayHttp: Boolean get() = true
    override fun sendHttpReplay(method: String, url: String, headers: List<HttpHeader>, body: ByteArray) =
        HttpReplaySends.request(method, url, headers, body)
    override fun openHttpRequestFile(fileName: String, text: String, bodyFileName: String?, body: ByteArray) =
        postOpenHttpRequestFileToHost(fileName, text, bodyFileName, body)
    override fun scriptTabAdded(scriptPath: String) = postScriptTabAddedToHost(scriptPath)
    override fun scriptTabRemoved(scriptPath: String) = postScriptTabRemovedToHost(scriptPath)
    override fun ready() = postMessageToHost("ready")
}

/**
 * The [VscodeMessage] an inbound message from the extension host stands for.
 *
 * The bus is untyped in both directions, so every field name the host posts is
 * read here and nowhere else.
 */
internal fun decodeVscodeMessage(type: String, data: dynamic): VscodeMessage =
    when (type) {
        "load-wasm" -> {
            val byteArray = messageBytesOrNull(data.data)
            VscodeMessage(
                type = type,
                fileName = (data.fileName as? String) ?: "",
                filePath = (data.filePath as? String) ?: "",
                data = byteArray,
                wasmRuntime = (data.wasmRuntime as? String),
                showSemanticHighlighting = (data.showSemanticHighlighting as? Boolean),
                parentFileName = (data.parentFileName as? String),
                decompressed = (data.decompressed as? Boolean) ?: false,
                fileSize = (data.fileSize as? Number)?.toDouble()?.toLong(),
                canRunApk = (data.canRunApk as? Boolean) ?: false,
                featureCatalogJson = (data.featureCatalogJson as? String) ?: "",
                runtimeTargetsJson = (data.runtimeTargetsJson as? String) ?: "",
                fileAssociationsJson = (data.fileAssociationsJson as? String) ?: "",
                fileAssociationKey = (data.fileAssociationKey as? String) ?: "",
                providerScripts = decodeProviderScripts(data.providerScripts),
                providerScriptsUntrusted = (data.providerScriptsUntrusted as? Boolean) ?: false,
            )
        }
        "provider-scripts-loaded" -> {
            VscodeMessage(
                type = type,
                providerScripts = decodeProviderScripts(data.providerScripts) ?: emptyList(),
                providerScriptsUntrusted = (data.providerScriptsUntrusted as? Boolean) ?: false,
            )
        }
        "select-table-row" -> {
            VscodeMessage(
                type = type,
                tabName = (data.tabName as? String) ?: "",
                rowIndex = (data.rowIndex as? Number)?.toInt() ?: -1,
            )
        }
        "update-setting" -> {
            VscodeMessage(
                type = type,
                settingKey = (data.key as? String) ?: "",
                settingValue = (data.value as? Boolean) ?: false,
            )
        }
        "load-diff" -> {
            VscodeMessage(
                type = type,
                leftData = messageBytesOrNull(data.leftData),
                leftName = (data.leftName as? String) ?: "",
                rightData = messageBytesOrNull(data.rightData),
                rightName = (data.rightName as? String) ?: "",
                diffMode = (data.mode as? String) ?: "",
            )
        }
        "load-native-diff" -> {
            VscodeMessage(
                type = type,
                leftData = messageBytesOrNull(data.leftData),
                leftName = (data.leftName as? String) ?: "",
                rightData = messageBytesOrNull(data.rightData),
                rightName = (data.rightName as? String) ?: "",
                diffMode = (data.mode as? String) ?: "",
            )
        }
        "load-expired" -> {
            VscodeMessage(
                type = type,
                message = (data.message as? String) ?: "",
            )
        }
        "load-nested-module" -> {
            VscodeMessage(
                type = type,
                parentData = messageBytesOrNull(data.parentData),
                moduleIndex = (data.moduleIndex as? Number)?.toInt() ?: -1,
                fileName = (data.fileName as? String) ?: "",
                wasmRuntime = (data.wasmRuntime as? String),
            )
        }
        "script-file-picked", "script-file-content" -> {
            VscodeMessage(
                type = type,
                scriptPath = (data.scriptPath as? String) ?: "",
                scriptContent = (data.scriptContent as? String) ?: "",
            )
        }
        "binary-file-content" -> {
            val payload = data.data
            val bytes = messageBytesOrNull(payload)
            val reported = (data.error as? String)?.takeIf { it.isNotBlank() }
            // A payload that arrived but did not decode has to become
            // an error the script can see. Reporting nothing would
            // leave the `open(path)` that asked for it suspended.
            val undecodable = payload != null && bytes == null
            VscodeMessage(
                type = type,
                requestId = (data.requestId as? String) ?: "",
                filePath = (data.path as? String) ?: "",
                data = bytes,
                message = reported
                    ?: if (undecodable) {
                        "the editor sent the file in a form this view cannot read."
                    } else {
                        ""
                    },
            )
        }
        "ssl-key-log-picked" -> {
            VscodeMessage(
                type = type,
                keyLogPath = (data.keyLogPath as? String) ?: "",
                keyLogContent = (data.keyLogContent as? String) ?: "",
            )
        }
        "runtime-targets-loaded" -> {
            VscodeMessage(
                type = type,
                runtimeTargetsJson = (data.runtimeTargetsJson as? String) ?: "",
            )
        }
        "feature-catalog-loaded" -> {
            VscodeMessage(
                type = type,
                featureCatalogJson = (data.featureCatalogJson as? String) ?: "",
            )
        }
        "file-associations-loaded" -> {
            VscodeMessage(
                type = type,
                fileAssociationsJson = (data.fileAssociationsJson as? String) ?: "",
            )
        }
        "provider-script-created" -> VscodeMessage(type = type, providerId = (data.providerId as? String) ?: "")
        "open-runtime-targets-dialog" -> VscodeMessage(type = type)
        "open-file-associations-dialog" -> VscodeMessage(type = type)
        "restore-script-tabs" -> {
            val jsPaths = data.scriptPaths
            val paths = if (jsPaths != null) {
                val len = jsPaths.length as Int
                (0 until len).map { i -> jsPaths[i] as String }
            } else emptyList()
            VscodeMessage(
                type = type,
                scriptPaths = paths,
            )
        }
        else -> VscodeMessage(type = type)
    }

/** Reads the `[{ path, text }]` array the host sends; `null` when the field is absent. */
private fun decodeProviderScripts(value: dynamic): List<ProviderScriptSource>? {
    if (value == null || value == undefined || !(js("Array").isArray(value) as Boolean)) return null
    return (0 until (value.length as Int)).mapNotNull { i ->
        val entry: dynamic = value[i]
        val path = entry?.path as? String
        val text = entry?.text as? String
        if (path != null && text != null) ProviderScriptSource(path, text) else null
    }
}

/**
 * Composable side-effect that listens for messages from the VS Code extension
 * host and converts them to [VscodeMessage] instances.
 *
 * On first composition it sends a `"ready"` signal so the host knows the
 * webview is prepared to receive the binary payload.
 */
@Composable
fun VscodeMessageListener(onMessage: (VscodeMessage) -> Unit) {
    DisposableEffect(Unit) {
        val handler: (Event) -> Unit = { event ->
            val messageEvent = event as? MessageEvent
            val data = messageEvent?.data?.asDynamic()
            if (data != null && data.type == "http-replay-response") {
                // Answers a promise rather than changing UI state, so it never
                // needs to reach the composable message handler.
                HttpReplaySends.complete(data)
            } else if (data != null && data.type != undefined) {
                val type = data.type as String
                // A message whose payload we did not expect can fail to
                // decode. Whatever that costs, it must not cost a script its
                // answer: an `open(path)` waiting on a reply whose handler
                // threw would hold the script suspended for good.
                val msg = try {
                    decodeVscodeMessage(type, data)
                } catch (failure: Throwable) {
                    ScriptBinaryReads.failAll(
                        "the editor's reply could not be read " +
                            "(${failure.message ?: "unknown error"}).",
                    )
                    throw failure
                }
                onMessage(msg)
            }
        }

        window.addEventListener("message", handler)

        // Signal readiness to the extension host.
        postMessageToHost("ready")

        onDispose {
            window.removeEventListener("message", handler)
        }
    }
}
