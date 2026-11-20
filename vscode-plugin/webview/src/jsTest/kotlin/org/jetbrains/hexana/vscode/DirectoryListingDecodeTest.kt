package org.jetbrains.hexana.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The webview half of `list-directory` / `directory-listing`. The extension host's
 * `directoryListing.ts` (and its unit test) produce the payload; this pins what this side reads from it.
 */
class DirectoryListingDecodeTest {
    @Test
    fun decodesEntriesAsTheHostPostsThem() {
        val data: dynamic = js(
            """({ type: "directory-listing", requestId: "list-directory-0", path: "/fw", entries: [
                { name: "app.abc.wasm", isFile: true, isDirectory: false, size: 17173, sha256: "q1w2e3==" },
                { name: "sub", isFile: false, isDirectory: true },
                { name: "gone.js", isFile: true, isDirectory: false }
            ] })""",
        )
        assertEquals(
            listOf(
                HostDirectoryEntry("app.abc.wasm", isFile = true, isDirectory = false, size = 17173L, sha256 = "q1w2e3=="),
                HostDirectoryEntry("sub", isFile = false, isDirectory = true, size = null, sha256 = null),
                HostDirectoryEntry("gone.js", isFile = true, isDirectory = false, size = null, sha256 = null),
            ),
            decodeDirectoryListing(data),
        )
    }

    @Test
    fun skipsMalformedEntriesAndRejectsAReplyWithoutEntries() {
        val data: dynamic = js("""({ entries: [ null, { isFile: true }, { name: 7 }, { name: "ok", isFile: true } ] })""")
        assertEquals(listOf("ok"), decodeDirectoryListing(data).map { it.name })
        assertFailsWith<RuntimeException> { decodeDirectoryListing(js("({ error: '' })")) }
    }
}
