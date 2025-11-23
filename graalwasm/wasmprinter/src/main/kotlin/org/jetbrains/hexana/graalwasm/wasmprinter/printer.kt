package org.jetbrains.hexana.graalwasm.wasmprinter

fun printWat(bytes: ByteArray): String {
    return WasmPrinter().print(bytes)
}
