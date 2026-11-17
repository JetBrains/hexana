package org.jetbrains.hexana.vscode

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The webview half of `select-table-row`, which the extension host posts to the
 * editor of an archive entry after "Reveal in DEX" in the archive's editor.
 * The bus is untyped, so this pins the field names `WasmEditorProvider.ts` posts.
 */
class SelectTableRowDecodeTest {

    @Test
    fun readsTheTabAndRowTheExtensionHostPosts() {
        val message = js("{}")
        message.type = "select-table-row"
        message.tabName = "Classes"
        message.rowIndex = 42

        val decoded = decodeVscodeMessage("select-table-row", message)

        assertEquals("Classes", decoded.tabName)
        assertEquals(42, decoded.rowIndex)
    }

    @Test
    fun aMalformedRequestDecodesToNoRow() {
        val message = js("{}")
        message.type = "select-table-row"
        message.rowIndex = "not a number"

        val decoded = decodeVscodeMessage("select-table-row", message)

        assertEquals("", decoded.tabName)
        assertEquals(-1, decoded.rowIndex)
    }
}
