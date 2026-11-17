package org.jetbrains.hexana.tests

actual suspend fun wat2wasm(wat: String): ByteArray =
    org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm(wat)