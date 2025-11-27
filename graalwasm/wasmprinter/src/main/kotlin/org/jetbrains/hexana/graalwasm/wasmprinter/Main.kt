package org.jetbrains.hexana.graalwasm.wasmprinter


fun main() {

    println(printWat(
        byteArrayOf(
            // a small wasm header for testing
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00
        )
    ).wat)

}
