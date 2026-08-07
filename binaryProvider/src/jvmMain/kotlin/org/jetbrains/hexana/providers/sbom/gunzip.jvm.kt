package org.jetbrains.hexana.providers.sbom

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

internal actual suspend fun gunzip(data: ByteArray): ByteArray? = try {
    GZIPInputStream(ByteArrayInputStream(data)).use { input ->
        val out = ArrayList<Byte>(data.size * 4)
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            for (i in 0 until n) out.add(chunk[i])
            if (out.size > MAX_SBOM_INFLATED_BYTES) return null
        }
        out.toByteArray()
    }
} catch (_: ZipException) {
    null
} catch (_: IOException) {
    null
}