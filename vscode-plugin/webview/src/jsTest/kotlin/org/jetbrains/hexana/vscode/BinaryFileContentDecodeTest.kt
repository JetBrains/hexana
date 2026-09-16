package org.jetbrains.hexana.vscode

import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The webview half of the `read-binary-file` / `binary-file-content` pair that
 * answers `open(path)` in a script tab.
 *
 * These pin the untyped seam: the extension host posts plain JS, so nothing but
 * these tests says that what it posts is what this side reads. They exist
 * because a payload the decode could not read used to throw out of the inbound
 * message listener, which left the `open(path)` that asked for it suspended and
 * the script tab reporting "Running script" with no way out.
 */
class BinaryFileContentDecodeTest {
    private fun jsObject(): dynamic = js("({})")

    private fun bytesPayload(vararg values: Int): Uint8Array {
        val array = Uint8Array(values.size)
        values.forEachIndexed { index, value -> array.asDynamic()[index] = value }
        return array
    }

    @Test
    fun decodesATypedArrayPayload() {
        assertContentEquals(
            byteArrayOf(0, 97, -1),
            messageBytesOrNull(bytesPayload(0, 97, 255)),
        )
    }

    @Test
    fun decodesAPlainArrayPayload() {
        assertContentEquals(byteArrayOf(1, 2, 3), messageBytesOrNull(js("[1, 2, 3]")))
    }

    @Test
    fun decodesAnEmptyPayload() {
        assertContentEquals(byteArrayOf(), messageBytesOrNull(bytesPayload()))
    }

    @Test
    fun reportsAMissingPayloadRatherThanThrowing() {
        assertNull(messageBytesOrNull(null))
        assertNull(messageBytesOrNull(undefined))
    }

    /**
     * A Node `Buffer` posted without conversion arrives shaped like this. The
     * extension host must convert it — see `toWebviewBytes` in
     * `vscode-plugin/src/scriptBinaryRead.ts` — and if it ever stops doing so,
     * this side has to report that, not throw.
     */
    @Test
    fun reportsASerializedNodeBufferRatherThanThrowing() {
        assertNull(messageBytesOrNull(js("({ type: 'Buffer', data: [1, 2, 3] })")))
    }

    @Test
    fun reportsAPayloadOfNonNumbersRatherThanThrowing() {
        assertNull(messageBytesOrNull(js("['a', 'b']")))
    }

    @Test
    fun readsTheFieldNamesTheExtensionHostPosts() {
        val message = jsObject()
        message.type = "binary-file-content"
        message.requestId = "script-open-7"
        message.path = "/tmp/other.wasm"
        message.data = bytesPayload(0, 97, 115, 109)

        val decoded = decodeVscodeMessage("binary-file-content", message)

        assertEquals("script-open-7", decoded.requestId)
        assertEquals("/tmp/other.wasm", decoded.filePath)
        assertContentEquals(byteArrayOf(0, 97, 115, 109), decoded.data)
        assertEquals("", decoded.message)
    }

    @Test
    fun carriesTheFailureTheExtensionHostReports() {
        val message = jsObject()
        message.type = "binary-file-content"
        message.requestId = "script-open-1"
        message.path = "/tmp/missing.wasm"
        message.error = "open(\"/tmp/missing.wasm\") — File not found"

        val decoded = decodeVscodeMessage("binary-file-content", message)

        assertNull(decoded.data)
        assertEquals("open(\"/tmp/missing.wasm\") — File not found", decoded.message)
    }

    /**
     * The case that hung: bytes arrived, but not in a form this side could read.
     * It has to come out as a failure the script's own `catch` can report.
     */
    @Test
    fun turnsAnUnreadablePayloadIntoAFailure() {
        val message = jsObject()
        message.type = "binary-file-content"
        message.requestId = "script-open-2"
        message.path = "/tmp/other.wasm"
        message.data = js("({ type: 'Buffer', data: [0, 97] })")

        val decoded = decodeVscodeMessage("binary-file-content", message)

        assertNull(decoded.data)
        assertTrue(decoded.message.isNotBlank(), "an unreadable payload must report something")
        assertContains(decoded.message, "cannot read")
    }
}
