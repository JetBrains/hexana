package org.jetbrains.hexana.io

import java.io.File
import java.io.FileOutputStream

actual fun dump(message: String, outputDescriptor: String) {
    FileOutputStream(File(outputDescriptor)).use { it.write(message.toByteArray()) }
}