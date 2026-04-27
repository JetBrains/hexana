package org.jetbrains.hexana.wasm.opcodes

internal expect fun currentThreadTag(): String

internal fun Any.bufferTag(): String = "@${hashCode().toString(16)}"
