package org.jetbrains.hexana.webapp

import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader

/**
 * Opens a native file picker dialog and calls [onFileLoaded] with the chosen
 * file's name and raw bytes when the user selects a file.
 */
fun openFilePicker(onFileLoaded: (name: String, bytes: ByteArray) -> Unit) {
    val input = document.createElement("input").unsafeCast<HTMLInputElement>()
    input.type = "file"
    input.onchange = {
        val file = input.files?.item(0)
        if (file != null) readFile(file, onFileLoaded)
    }
    input.click()
}

/**
 * Reads [file] as an ArrayBuffer and delivers the result to [onFileLoaded]
 * as a ByteArray.
 */
fun readFile(file: File, onFileLoaded: (name: String, bytes: ByteArray) -> Unit) {
    val reader = FileReader()
    reader.onload = { event ->
        val result = event.target.asDynamic().result
        val uint8 = js("new Uint8Array(result)").unsafeCast<dynamic>()
        val length = uint8.length as Int
        val bytes = ByteArray(length) { i -> (uint8[i] as Number).toByte() }
        onFileLoaded(file.name, bytes)
    }
    reader.readAsArrayBuffer(file)
}