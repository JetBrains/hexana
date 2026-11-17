package org.jetbrains.hexana.codec

/** Thrown when decompression fails or a codec is not supported on this platform. */
class CodecException(message: String) : Exception(message)
