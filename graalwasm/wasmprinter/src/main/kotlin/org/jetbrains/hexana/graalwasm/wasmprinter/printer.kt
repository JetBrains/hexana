package org.jetbrains.hexana.graalwasm.wasmprinter

fun printWat(bytes: ByteArray): WatWithWarnings {
    return WasmPrinter().print(bytes)
}
