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
    val data: ByteArray? = null,
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
 */
fun postRunExportToHost(
    exportName: String,
    invokeArgs: String = "",
    programArgs: String = "",
    isComponent: Boolean = false,
    wasmBytes: ByteArray? = null,
) {
    val msg = js("{}")
    msg.type = "run-export"
    msg.exportName = exportName
    msg.invokeArgs = invokeArgs
    msg.programArgs = programArgs
    msg.isComponent = isComponent

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
                            data = byteArray,
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
