package org.jetbrains.hexana.nativebin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.SbomLocator
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/**
 * Reads and decompresses the GraalVM Native Image embedded SBOM located by
 * [org.jetbrains.hexana.providers.NativeImageDetector]. The detector (commonMain)
 * only computes the [SbomLocator]; the gunzip + UTF-8 decode lives here, in the
 * JVM layer, so `binaryProvider` stays free of `java.util.zip`.
 *
 * The blob is gzip-compressed CycloneDX JSON. We sniff the gzip magic via
 * [SbomLocator.isGzip] but still guard the inflate against corrupt input.
 */
object SbomExtractor {

    /** Hard cap on the inflated payload to avoid a zip-bomb blowing up the heap. */
    private const val MAX_INFLATED_BYTES = 128 * 1024 * 1024

    /**
     * Returns the decompressed CycloneDX JSON for [locator], or `null` if the
     * locator is out of bounds, oversized, or the gzip stream is corrupt.
     */
    fun extractJson(bytes: CommonByteBuffer, locator: SbomLocator): String? {
        val offset = locator.fileOffset
        val length = locator.length
        if (offset < 0 || length <= 0 || offset + length > bytes.size.toLong()) {
            HexanaLog.LOG.warn("SBOM locator out of bounds: offset=$offset length=$length size=${bytes.size}")
            return null
        }
        if (length > MAX_INFLATED_BYTES) {
            HexanaLog.LOG.warn("SBOM compressed length too large: $length")
            return null
        }

        val raw = bytes.slice(offset.toInt(), length.toInt()).toByteArray()
        val decoded = if (locator.isGzip) gunzip(raw) ?: return null else raw
        return decoded.toString(Charsets.UTF_8)
    }

    private fun gunzip(data: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ArrayList<Byte>(data.size * 4)
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                for (i in 0 until n) out.add(chunk[i])
                if (out.size > MAX_INFLATED_BYTES) {
                    HexanaLog.LOG.warn("SBOM inflated payload exceeded $MAX_INFLATED_BYTES bytes")
                    return null
                }
            }
            out.toByteArray()
        }
    } catch (e: ZipException) {
        HexanaLog.LOG.warn("SBOM gunzip failed", e)
        null
    } catch (e: IOException) {
        HexanaLog.LOG.warn("SBOM read failed", e)
        null
    }

    /**
     * Extracts the SBOM off the EDT and opens it as a read-only `*.cdx.json` tab.
     * On failure a tab is still opened with a short diagnostic so the click always
     * produces visible feedback.
     */
    fun openExtractedSbom(
        project: Project,
        baseFileName: String,
        bytes: CommonByteBuffer,
        locator: SbomLocator,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val json = runCatching { extractJson(bytes, locator) }.getOrNull()
            val content = json ?: "// Failed to extract the embedded SBOM from \"$baseFileName\".\n" +
                "// The symbols were present but the blob could not be read or decompressed.\n"
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val vf = LightVirtualFile("$baseFileName.cdx.json", content).apply { isWritable = false }
                FileEditorManager.getInstance(project).openFile(vf, /* focusEditor = */ true)
            }
        }
    }
}