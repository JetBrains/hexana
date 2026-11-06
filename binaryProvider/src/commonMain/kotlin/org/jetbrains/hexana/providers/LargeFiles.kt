package org.jetbrains.hexana.providers

/**
 * The most bytes of one file Hexana loads. [org.jetbrains.hexana.io.CommonByteBuffer] and every
 * parser and hex view address bytes with `Int`, so a larger file is opened as a *partial view*:
 * its first bytes, up to this limit or a smaller host-specific one, with the real size passed to
 * [createBinaryProvider] as `fileSize`.
 *
 * 1 MiB short of 2 GiB rather than `Int.MAX_VALUE`, so `offset + size` arithmetic on a range
 * near the end of the window cannot overflow an `Int`.
 */
const val MAX_BINARY_WINDOW_BYTES: Int = 2_146_435_072
