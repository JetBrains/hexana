package org.jetbrains.hexana.parsing.common

data class NativeUnresolvedRecord(
    val kind: String,
    val id: ULong? = null,
    val name: String? = null,
    val recordRange: NativeByteRange,
    val contentRange: NativeByteRange? = null,
    val reason: String? = null,
)
