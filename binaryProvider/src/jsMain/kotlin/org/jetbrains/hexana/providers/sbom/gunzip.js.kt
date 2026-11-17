package org.jetbrains.hexana.providers.sbom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * Browser-native gzip decompressor (Streams API). Declared as an external class
 * instead of a `js()` block: `js()` string literals that reference enclosing
 * function parameters break under the production (optimizing) Kotlin/JS compile,
 * which renames the parameters while the literal keeps the original names
 * ("ReferenceError: input is not defined").
 */
private external class DecompressionStream(format: String) {
    val readable: dynamic
    val writable: dynamic
}

/**
 * Browser-native gzip decompression via the Streams API [DecompressionStream].
 * The inflate cap is enforced while pumping (resolves to null when exceeded),
 * so a decompression bomb never accumulates past [MAX_SBOM_INFLATED_BYTES].
 */
internal actual suspend fun gunzip(data: ByteArray): ByteArray? {
    // Kotlin/JS ByteArray is an Int8Array at runtime; reinterpret without copying.
    val int8 = data.unsafeCast<Int8Array>()
    val input = Uint8Array(int8.buffer, int8.byteOffset, data.size)

    return try {
        val ds = DecompressionStream("gzip")
        val writer = ds.writable.getWriter()
        val reader = ds.readable.getReader()

        // Promises are eager: the write starts now and proceeds while the loop
        // below drains the readable side. Awaiting the write BEFORE pumping
        // deadlocks — it only resolves once the output queue is consumed.
        val wrote = writer.write(input)
            .then { writer.close() }
            .unsafeCast<Promise<Unit>>()

        val chunks = ArrayList<Uint8Array>()
        var totalSize = 0
        while (true) {
            val r = reader.read().unsafeCast<Promise<dynamic>>().await()
            if (r.done == true) break
            val chunk = r.value.unsafeCast<Uint8Array>()
            totalSize += chunk.length
            if (totalSize > MAX_SBOM_INFLATED_BYTES) {
                reader.cancel()
                return null
            }
            chunks.add(chunk)
        }
        // Surface a rejected write (corrupt input) as an exception.
        wrote.await()

        val out = Uint8Array(totalSize)
        var offset = 0
        for (chunk in chunks) {
            out.asDynamic().set(chunk, offset)
            offset += chunk.length
        }
        // The output buffer is freshly allocated above, so viewing it is safe.
        Int8Array(out.buffer, out.byteOffset, out.byteLength).unsafeCast<ByteArray>()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }
}