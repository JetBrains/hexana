package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.hexana.pcap.CapturedHttpRequest
import org.jetbrains.hexana.pcap.HttpReplay
import org.jetbrains.hexana.pcap.HttpReplayRequest

/**
 * Edits a request captured in a PCAP and sends it again through the host,
 * showing the answer underneath so it can be tweaked and re-sent - the
 * counterpart of the IntelliJ replay window.
 *
 * Unlike the other dialogs, clicking the overlay does not close it: an
 * edited request is easy to lose and slow to retype.
 */
@Composable
fun HttpReplayDialog(captured: CapturedHttpRequest, onClose: () -> Unit) {
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val original = captured.request
    // `null` when the captured body is binary; it is then sent as captured and cannot be edited.
    val originalBodyText = remember(captured) { HttpReplay.bodyAsText(original.body) }

    var method by remember(captured) { mutableStateOf(original.method) }
    var url by remember(captured) { mutableStateOf(original.url) }
    var headers by remember(captured) { mutableStateOf(HttpReplay.formatHeaders(HttpReplay.replayHeaders(original))) }
    var body by remember(captured) { mutableStateOf(originalBodyText.orEmpty()) }
    var error by remember(captured) { mutableStateOf<String?>(null) }
    var status by remember(captured) { mutableStateOf<String?>(null) }
    var response by remember(captured) { mutableStateOf("") }
    var sendJob by remember(captured) { mutableStateOf<Job?>(null) }

    fun edited(): HttpReplayRequest? = try {
        editedReplayRequest(original, originalBodyText, method, url, headers, body)
            .also { error = null }
    } catch (e: IllegalArgumentException) {
        error = e.message
        null
    }

    Div({ classes("hexana-dialog-overlay") }) {
        Div({ classes("hexana-dialog", "hexana-http-replay-dialog") }) {
            Div({ classes("hexana-dialog-title") }) { Text("Replay HTTP Request — Packet #${captured.packetNumber}") }

            Div({ classes("hexana-dialog-body") }) {
                P({ classes("hexana-dialog-hint") }) { Text(captured.origin) }
                if (!original.bodyComplete) {
                    P({ classes("hexana-dialog-hint") }) {
                        Text("The capture holds only part of this request's body; only that part is sent.")
                    }
                }
                Div({ classes("hexana-dialog-field", "hexana-http-replay-request-line") }) {
                    Input(InputType.Text) {
                        classes("hexana-dialog-input", "hexana-http-replay-method")
                        value(method)
                        attr("aria-label", "Method")
                        onInput { method = it.value }
                    }
                    Input(InputType.Text) {
                        classes("hexana-dialog-input")
                        value(url)
                        attr("aria-label", "URL")
                        onInput { url = it.value }
                    }
                }
                Div({ classes("hexana-dialog-field") }) {
                    Label { Text("Headers — one \"Name: value\" per line. Host follows the URL; Content-Length is recomputed.") }
                    TextArea(headers) {
                        classes("hexana-dialog-input", "hexana-http-replay-text")
                        attr("rows", "7")
                        attr("spellcheck", "false")
                        onInput { headers = it.value }
                    }
                }
                Div({ classes("hexana-dialog-field") }) {
                    Label { Text("Body") }
                    if (originalBodyText == null) {
                        P({ classes("hexana-dialog-hint") }) {
                            Text("Binary body (${original.body.size} bytes) is sent as captured.")
                        }
                    } else {
                        TextArea(body) {
                            classes("hexana-dialog-input", "hexana-http-replay-text")
                            attr("rows", "7")
                            attr("spellcheck", "false")
                            onInput { body = it.value }
                        }
                    }
                }
                error?.let { Div({ classes("hexana-dialog-error") }) { Text(it) } }
                status?.let { shown ->
                    Div({ classes("hexana-dialog-field") }) {
                        Label { Text("Response — $shown") }
                        Pre({ classes("hexana-http-replay-response") }) { Text(response) }
                    }
                }
            }

            Div({ classes("hexana-dialog-buttons") }) {
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick { edited()?.let { host.openCapturedRequestFile(captured, it) } }
                }) { Text("Open as .http File") }
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-cancel")
                    onClick {
                        sendJob?.cancel()
                        onClose()
                    }
                }) { Text("Close") }
                Button({
                    classes("hexana-dialog-btn", "hexana-dialog-btn-ok")
                    onClick {
                        val request = edited() ?: return@onClick
                        sendJob?.cancel()
                        status = "sending…"
                        response = ""
                        sendJob = scope.launch {
                            try {
                                val answer = host.sendHttpReplay(
                                    request.method,
                                    request.url,
                                    HttpReplay.sendableHeaders(request.headers),
                                    request.body,
                                ).await()
                                status = "${answer.status} ${answer.statusText}".trim() +
                                    "  ·  ${answer.elapsedMillis} ms  ·  ${answer.body.size} bytes"
                                response = formatReplayResponse(answer)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                status = "failed"
                                response = t.message ?: "The request could not be sent."
                            }
                        }
                    }
                }) { Text("Send") }
            }
        }
    }
}

/**
 * The request as edited in the replay dialog.
 *
 * A textarea normalises line breaks, so an untouched body is sent as the
 * captured bytes rather than as the area's re-encoded text; an edited one is
 * the whole body from then on.
 *
 * @throws IllegalArgumentException with a message for the user when the edit
 *   is not a request that can be sent
 */
internal fun editedReplayRequest(
    original: HttpReplayRequest,
    originalBodyText: String?,
    method: String,
    url: String,
    headers: String,
    body: String,
): HttpReplayRequest {
    val trimmedMethod = method.trim()
    require(trimmedMethod.isNotEmpty() && trimmedMethod.all { it.isLetter() || it == '-' }) { "Enter an HTTP method" }
    val trimmedUrl = url.trim()
    require(trimmedUrl.startsWith("http://", true) || trimmedUrl.startsWith("https://", true)) {
        "Only http and https URLs can be replayed"
    }
    val parsedHeaders = HttpReplay.parseHeaders(headers)
    val edited = originalBodyText != null && body != originalBodyText &&
        body != originalBodyText.replace("\r\n", "\n")
    return HttpReplayRequest(
        method = trimmedMethod.uppercase(),
        url = trimmedUrl,
        httpVersion = original.httpVersion,
        headers = parsedHeaders,
        body = if (edited) body.encodeToByteArray() else original.body,
        bodyComplete = original.bodyComplete || edited,
    )
}

/** Writes [request] - [captured] as captured, or as edited - as an `.http` file through the host. */
fun Host.openCapturedRequestFile(captured: CapturedHttpRequest, request: HttpReplayRequest = captured.request) {
    val bodyFileName = if (HttpReplay.needsBodyFile(request)) captured.bodyFileName else null
    val text = HttpReplay.toHttpClientFile(
        request,
        title = captured.title,
        comments = listOf(captured.origin),
        bodyFilePath = bodyFileName?.let { "./$it" },
    )
    openHttpRequestFile(captured.httpFileName, text, bodyFileName, if (bodyFileName != null) request.body else ByteArray(0))
}

private const val MAX_SHOWN_BODY_CHARS = 512 * 1024

internal fun formatReplayResponse(response: HttpReplayResponse): String = buildString {
    for (header in response.headers) append(header.name).append(": ").append(header.value).append('\n')
    append('\n')
    val text = HttpReplay.bodyAsText(response.body)
    when {
        response.body.isEmpty() -> append("(empty body)")
        text == null -> append("(binary body, ${response.body.size} bytes)")
        text.length > MAX_SHOWN_BODY_CHARS ->
            append(text, 0, MAX_SHOWN_BODY_CHARS).append("\n… (${text.length - MAX_SHOWN_BODY_CHARS} more characters)")
        else -> append(text)
    }
}
