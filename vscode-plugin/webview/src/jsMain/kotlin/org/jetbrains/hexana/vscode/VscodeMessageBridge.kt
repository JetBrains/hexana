package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.window
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
