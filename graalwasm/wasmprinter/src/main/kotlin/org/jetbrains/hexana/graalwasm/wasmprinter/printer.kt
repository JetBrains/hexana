package org.jetbrains.hexana.graalwasm.wasmprinter

fun printWat(bytes: ByteArray, foldInstructions: Boolean = true, nameUnnamed: Boolean = true, printOffsets: Boolean = false): WatWithWarnings {
    return WasmPrinter(foldInstructions, nameUnnamed, printOffsets).use { it.print(bytes) }
}
