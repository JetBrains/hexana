package org.jetbrains.hexana.graalwasm.wat2wasm

import java.util.Base64

fun main() {
    val array = wat2wasm("""
        (module
          (func (export "run")
            nop
          )
        )
    """.trimIndent())

    array.joinToString { it.toHexString() }.let {
        println(it)
    }
}