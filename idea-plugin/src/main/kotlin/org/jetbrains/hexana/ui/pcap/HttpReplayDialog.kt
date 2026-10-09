package org.jetbrains.hexana.ui.pcap

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.pcap.HttpReplay
import org.jetbrains.hexana.pcap.HttpReplayRequest
import org.jetbrains.hexana.ui.decompressHttpBody
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Edits a request captured in a PCAP and sends it again, showing the answer
 * underneath so the request can be tweaked and re-sent in a loop.
 *
 * Modeless, so the Packets table stays usable while it is open - comparing the
 * replayed response with the captured one is the usual next step.
 */
internal class HttpReplayDialog(
    private val project: Project,
    private val captured: HttpReplayRequest,
    private val packetNumber: Int,
    /** Lines describing where the request came from, shown under the title. */
    private val origin: List<String>,
) : DialogWrapper(project, true, IdeModalityType.MODELESS) {

    private val methodField = ComboBox(arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")).apply {
        isEditable = true
        selectedItem = captured.method
    }
    private val urlField = JBTextField(captured.url)
    private val headersArea = monospacedArea(HttpReplay.formatHeaders(HttpReplay.replayHeaders(captured)), rows = 8)

    /** `null` when the captured body is binary; it is then sent as captured and cannot be edited. */
    private val capturedBodyText: String? = HttpReplay.bodyAsText(captured.body)
    private val bodyArea = monospacedArea(capturedBodyText.orEmpty(), rows = 8).apply {
        if (capturedBodyText == null) {
            isEditable = false
            text = "Binary body (${captured.body.size} bytes) is sent as captured."
        }
    }
    private val responseArea = monospacedArea("", rows = 14).apply { isEditable = false }
    private val statusLabel = JLabel(" ")

    private var sendJob: Job? = null

    private val openInHttpClientAction = object : AbstractAction("Open in HTTP Client") {
        override fun actionPerformed(e: ActionEvent?) {
            val request = editedRequest() ?: return
            HexanaService.getInstance().coroutineScope.launch {
                HttpClientReplayExport.open(project, request, packetNumber, origin)
            }
        }
    }

    init {
        title = "Replay HTTP Request — Packet #$packetNumber"
        setOKButtonText("Send")
        setCancelButtonText("Close")
        init()
    }

    override fun getDimensionServiceKey(): String = "Hexana.HttpReplayDialog"

    override fun getPreferredFocusedComponent(): JComponent = urlField

    override fun createLeftSideActions(): Array<Action> =
        if (HttpClientReplayExport.isAvailable) arrayOf(openInHttpClientAction) else emptyArray()

    override fun createCenterPanel(): JComponent = panel {
        for (line in origin) row { comment(line) }
        if (!captured.bodyComplete) {
            row { comment("The capture holds only part of this request's body; only that part is sent.") }
        }
        row("Request:") {
            cell(methodField)
            cell(urlField).align(AlignX.FILL).resizableColumn()
        }
        row {
            label("Headers:")
            comment("One \"Name: value\" per line. Host follows the URL; Content-Length is recomputed.")
        }
        row { cell(JBScrollPane(headersArea)).align(Align.FILL) }.resizableRow()
        row { label("Body:") }
        row { cell(JBScrollPane(bodyArea)).align(Align.FILL) }.resizableRow()
        row { label("Response:"); cell(statusLabel) }
        row { cell(JBScrollPane(responseArea)).align(Align.FILL) }.resizableRow()
    }.apply { preferredSize = JBUI.size(760, 720) }

    /** Sends instead of closing; the dialog stays open for the next attempt. */
    override fun doOKAction() {
        val request = editedRequest() ?: return
        sendJob?.cancel()
        statusLabel.text = "Sending…"
        responseArea.text = ""
        val ui = Dispatchers.EDT + ModalityState.any().asContextElement()
        sendJob = HexanaService.getInstance().coroutineScope.launch(Dispatchers.IO) {
            val shown = try {
                val headers = request.headers.filterNot { HttpReplaySender.isDropped(it.name) }
                val response = HttpReplaySender.send(request.method, request.url, headers, request.body)
                "${response.version} ${response.statusCode}  ·  ${response.elapsedMillis} ms  ·  " +
                    "${response.body.size} bytes" to formatResponse(response)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                HexanaLog.LOG.info("HTTP replay of packet #$packetNumber failed", t)
                "Failed" to (t.message ?: t.javaClass.simpleName)
            }
            withContext(ui) {
                statusLabel.text = shown.first
                responseArea.text = shown.second
                responseArea.caretPosition = 0
            }
        }
    }

    override fun dispose() {
        sendJob?.cancel()
        super.dispose()
    }

    /** The request as currently edited, or `null` after reporting what is wrong with it. */
    private fun editedRequest(): HttpReplayRequest? {
        setErrorText(null)
        val method = (methodField.editor.item as? String ?: methodField.selectedItem as? String).orEmpty().trim()
        if (method.isEmpty() || method.any { !it.isLetter() && it != '-' }) {
            setErrorText("Enter an HTTP method", methodField)
            return null
        }
        val headers = try {
            HttpReplay.parseHeaders(headersArea.text)
        } catch (e: IllegalArgumentException) {
            setErrorText(e.message, headersArea)
            return null
        }
        // A text area may normalise line breaks, so an untouched body is sent as
        // the captured bytes rather than as the area's re-encoded text.
        val edited = capturedBodyText != null && bodyArea.text != capturedBodyText &&
            bodyArea.text != capturedBodyText.replace("\r\n", "\n")
        val body = if (edited) bodyArea.text.encodeToByteArray() else captured.body
        return HttpReplayRequest(
            method = method.uppercase(),
            url = urlField.text.trim(),
            httpVersion = captured.httpVersion,
            headers = headers,
            body = body,
            // Whatever the user leaves in the editor is the whole body now.
            bodyComplete = captured.bodyComplete || edited,
        )
    }

    private fun formatResponse(response: HttpReplayResponse): String = buildString {
        for (header in response.headers) append(header.name).append(": ").append(header.value).append('\n')
        append('\n')
        val encoding = response.headers.firstOrNull { it.name.equals("content-encoding", true) }?.value
        val body = decompressHttpBody(response.body, encoding)
        val text = HttpReplay.bodyAsText(body)
        when {
            body.isEmpty() -> append("(empty body)")
            text == null -> append("(binary body, ${body.size} bytes)")
            text.length > MAX_SHOWN_BODY_CHARS ->
                append(text, 0, MAX_SHOWN_BODY_CHARS).append("\n… (${text.length - MAX_SHOWN_BODY_CHARS} more characters)")
            else -> append(text)
        }
    }

    private companion object {
        const val MAX_SHOWN_BODY_CHARS = 512 * 1024

        fun monospacedArea(text: String, rows: Int) = JBTextArea(text, rows, 80).apply {
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        }
    }
}
