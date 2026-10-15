package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.awaitAnimationFrame
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ZipBinaryProvider
import org.jetbrains.hexana.shared.ApkDexClasses
import org.jetbrains.hexana.shared.DEX_CLASSES_TAB_NAME
import org.jetbrains.hexana.shared.apkClassesDescriptor
import org.jetbrains.hexana.shared.collectApkDexClasses
import org.jetbrains.hexana.zip.ZipEntry
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * The APK tab listing the classes of every DEX file in the archive.
 *
 * Tab content composes only while its tab is active, so nothing is read before the tab is
 * first opened. DEX entries are inflated by the browser's own streams, off the page's thread,
 * and the collection yields between entries, so the webview keeps painting — a spinner and
 * which DEX is being read — while it works. The result goes to [loaded], which the caller keeps
 * beyond this tab's composition, so switching away and back does not read the DEX files again.
 *
 * "Reveal in DEX" is offered where [Host.canRevealZipEntryRow] holds: it opens the class's DEX
 * entry, or brings it forward when already open, with the class selected on its Classes tab.
 */
@Composable
internal fun ApkClassesTabContent(
    provider: ZipBinaryProvider,
    loaded: MutableState<Result<ApkDexClasses>?>,
    selection: HexSelectionState,
    host: Host,
) {
    var classes by loaded
    var progress by remember(provider) { mutableStateOf<String?>(null) }
    LaunchedEffect(provider) {
        if (classes != null) return@LaunchedEffect
        classes = try {
            val archive = provider.bytes
            Result.success(
                collectApkDexClasses(
                    provider,
                    onProgress = { index, total, entry ->
                        progress = "Reading ${entry.name} (${index + 1} of $total)…"
                    },
                    pause = ::awaitPaint,
                ) { entry -> inflateZipEntry(entry, archive) },
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    val result = classes
    if (result == null) {
        LoadingIndicator(progress ?: "Loading classes…")
        return
    }
    val ready = result.getOrElse { failure ->
        Div({ classes("empty-hint") }) {
            Text("Failed to read the classes of this APK: ${failure.message ?: failure::class.simpleName}")
        }
        return
    }

    val descriptor = remember(ready, host) {
        apkClassesDescriptor(
            classes = ready,
            onRevealInDex = if (host.canRevealZipEntryRow) {
                { row ->
                    val entry = row.entry
                    host.revealZipEntryRow(
                        name = entry.name,
                        dataOffset = entry.dataOffset,
                        compressedSize = entry.compressedSize,
                        uncompressedSize = entry.uncompressedSize,
                        compressionMethod = entry.compressionMethod,
                        tabName = DEX_CLASSES_TAB_NAME,
                        rowIndex = row.classDefIndex,
                    )
                }
            } else null,
        )
    }
    TableTabContent(descriptor, selection)
}

/**
 * Suspends until the browser has painted a frame, so the progress label shows and the page
 * stays responsive between DEX entries. A coroutine resumed from an animation frame callback
 * still runs before that frame's paint, hence the timer after it.
 */
private suspend fun awaitPaint() {
    window.awaitAnimationFrame()
    Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }.await()
}

/**
 * The uncompressed bytes of [entry] in [archive], or `null` for a compression method other
 * than stored or deflate, or for data that does not inflate to the declared size.
 */
internal suspend fun inflateZipEntry(entry: ZipEntry, archive: CommonByteBuffer): CommonByteBuffer? {
    val offset = entry.dataOffset.toInt()
    val compressedSize = entry.compressedSize.toInt()
    if (offset < 0 || compressedSize < 0 || offset + compressedSize > archive.limit()) return null
    val compressed = archive.slice(offset, compressedSize)
    return when (entry.compressionMethod) {
        0 -> compressed
        8 -> inflateRaw(compressed.toByteArray(), entry.uncompressedSize)?.toCommonByteBuffer()
        else -> null
    }
}

/**
 * The browser's raw-deflate decompressor (Streams API). An external class rather than a `js()`
 * block, which the optimizing Kotlin/JS compile breaks by renaming the parameters it names.
 */
private external class DecompressionStream(format: String) {
    val readable: dynamic
    val writable: dynamic
}

/**
 * Inflates raw deflate [data], stopping as soon as the output passes [expectedSize]. Cancelled —
 * the tab was left mid-read — it cancels the stream too, so the browser stops inflating.
 */
private suspend fun inflateRaw(data: ByteArray, expectedSize: Long): ByteArray? {
    // Kotlin/JS ByteArray is an Int8Array at runtime; view it without copying.
    val int8 = data.unsafeCast<Int8Array>()
    val input = Uint8Array(int8.buffer, int8.byteOffset, data.size)
    var reader: dynamic = null
    return try {
        val stream = DecompressionStream("deflate-raw")
        val writer = stream.writable.getWriter()
        reader = stream.readable.getReader()
        // Not awaited before reading: the write only settles once its output is drained. It
        // rejects once the readable side is cancelled; that outcome is handled below.
        val wrote = writer.write(input).then { writer.close() }.unsafeCast<Promise<Unit>>()
        wrote.catch { }

        val chunks = ArrayList<Uint8Array>()
        var total = 0L
        while (true) {
            val read = reader.read().unsafeCast<Promise<dynamic>>().await()
            if (read.done == true) break
            val chunk = read.value.unsafeCast<Uint8Array>()
            total += chunk.length
            if (total > expectedSize) {
                cancelReader(reader)
                return null
            }
            chunks += chunk
        }
        wrote.await()
        if (total != expectedSize) return null

        val out = Uint8Array(total.toInt())
        var at = 0
        for (chunk in chunks) {
            out.asDynamic().set(chunk, at)
            at += chunk.length
        }
        Int8Array(out.buffer, out.byteOffset, out.byteLength).unsafeCast<ByteArray>()
    } catch (c: CancellationException) {
        if (reader != null) cancelReader(reader)
        throw c
    } catch (_: Throwable) {
        null
    }
}

/**
 * Cancels a stream [reader] without awaiting it. Cancelling the readable side of a transform
 * stream errors its writable side too, which rejects the pending write and frees its buffers.
 */
private fun cancelReader(reader: dynamic) {
    reader.cancel().unsafeCast<Promise<Unit>>().catch { }
}
