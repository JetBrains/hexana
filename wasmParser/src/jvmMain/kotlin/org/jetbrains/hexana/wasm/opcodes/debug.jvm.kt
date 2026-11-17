package org.jetbrains.hexana.wasm.opcodes

internal actual fun currentThreadTag(): String =
    "${Thread.currentThread().id}/${Thread.currentThread().name}"
