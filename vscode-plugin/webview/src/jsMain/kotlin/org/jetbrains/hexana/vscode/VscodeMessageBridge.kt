package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.window
import org.jetbrains.hexana.shared.WasmImportStubGenerator
import org.jetbrains.hexana.shared.componentImportNames
import org.jetbrains.hexana.shared.isRuntimeImport
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event

/**
 * Decoded message received from the VS Code extension host via `postMessage`.
 */
data class VscodeMessage(
    val type: String,
    val fileName: String = "",
    val filePath: String = "",
    val data: ByteArray? = null,
    val wasmRuntime: String? = null,
    val scriptPath: String = "",
    val scriptContent: String = "",
    val scriptPaths: List<String> = emptyList(),
    val leftData: ByteArray? = null,
    val leftName: String = "",
    val rightData: ByteArray? = null,
    val rightName: String = "",
    val message: String = "",
    val parentData: ByteArray? = null,
    val moduleIndex: Int = -1,
    val showSemanticHighlighting: Boolean? = null,
    val settingKey: String = "",
    val settingValue: Boolean = false,
    val parentFileName: String? = null,
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
            val hasImports = WasmImportStubGenerator.hasImports(wasmBytes)
            msg.hasImports = hasImports
            if (hasImports && WasmImportStubGenerator.hasNonFunctionImports(wasmBytes)) {
                val stubs = WasmImportStubGenerator.generateNonFunctionStubs(wasmBytes)
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
fun postOpenHttpBodyToHost(fileName: String, data: ByteArray, contentType: String?) {
    val msg = js("{}")
    msg.type = "open-http-body"
    msg.fileName = fileName
    msg.contentType = contentType ?: ""
    val jsArray = js("[]").unsafeCast<dynamic>()
    for (i in data.indices) {
        jsArray.push(data[i].toInt() and 0xFF)
    }
    msg.data = jsArray
    vscodeApi.postMessage(msg)
}

/**
 * Asks the extension host to open the pre-built `hexana-scripting.d.ts`
 * API reference in a new VS Code editor tab.
 */
fun postOpenDtsToHost() {
    val msg = js("{}")
    msg.type = "open-dts"
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
class VscodeHost : Host {
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
    override fun overwriteFile(bytes: ByteArray) = postOverwriteFileToHost(bytes)
    override fun saveCopy(outputName: String, bytes: ByteArray) = postSaveCopyToHost(outputName, bytes)
    override fun openUrl(url: String) = postOpenUrlToHost(url)
    override fun openModule(moduleIndex: Int, fileName: String, data: ByteArray) =
        postOpenModuleToHost(moduleIndex, fileName, data)
    override fun openDts() = postOpenDtsToHost()
    override fun openHttpBody(fileName: String, bodyBytes: ByteArray, httpContentType: String?) =
        postOpenHttpBodyToHost(fileName, bodyBytes, httpContentType)
    override fun openTextFile(fileName: String, content: String) = postOpenTextFileToHost(fileName, content)
    override fun pickScriptFile() = postPickScriptFileToHost()
    override fun readScriptFile(scriptPath: String) = postReadScriptFileToHost(scriptPath)
    override fun scriptTabAdded(scriptPath: String) = postScriptTabAddedToHost(scriptPath)
    override fun scriptTabRemoved(scriptPath: String) = postScriptTabRemovedToHost(scriptPath)
    override fun ready() = postMessageToHost("ready")
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
            if (data != null && data.type != undefined) {
                val type = data.type as String
                val msg = when (type) {
                    "load-wasm" -> {
                        val jsArray = data.data
                        val byteArray = if (jsArray != null) {
                            val length = jsArray.length as Int
                            ByteArray(length) { i -> (jsArray[i] as Number).toByte() }
                        } else null
                        VscodeMessage(
                            type = type,
                            fileName = (data.fileName as? String) ?: "",
                            filePath = (data.filePath as? String) ?: "",
                            data = byteArray,
                            wasmRuntime = (data.wasmRuntime as? String),
                            showSemanticHighlighting = (data.showSemanticHighlighting as? Boolean),
                            parentFileName = (data.parentFileName as? String),
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
                        fun toByteArray(jsArr: dynamic): ByteArray? {
                            if (jsArr == null) return null
                            val len = jsArr.length as Int
                            return ByteArray(len) { i -> (jsArr[i] as Number).toByte() }
                        }
                        VscodeMessage(
                            type = type,
                            leftData = toByteArray(data.leftData),
                            leftName = (data.leftName as? String) ?: "",
                            rightData = toByteArray(data.rightData),
                            rightName = (data.rightName as? String) ?: "",
                        )
                    }
                    "load-native-diff" -> {
                        fun toByteArray(jsArr: dynamic): ByteArray? {
                            if (jsArr == null) return null
                            val len = jsArr.length as Int
                            return ByteArray(len) { i -> (jsArr[i] as Number).toByte() }
                        }
                        VscodeMessage(
                            type = type,
                            leftData = toByteArray(data.leftData),
                            leftName = (data.leftName as? String) ?: "",
                            rightData = toByteArray(data.rightData),
                            rightName = (data.rightName as? String) ?: "",
                        )
                    }
                    "load-expired" -> {
                        VscodeMessage(
                            type = type,
                            message = (data.message as? String) ?: "",
                        )
                    }
                    "load-nested-module" -> {
                        fun toByteArray(jsArr: dynamic): ByteArray? {
                            if (jsArr == null) return null
                            val len = jsArr.length as Int
                            return ByteArray(len) { i -> (jsArr[i] as Number).toByte() }
                        }
                        VscodeMessage(
                            type = type,
                            parentData = toByteArray(data.parentData),
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
