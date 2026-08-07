package org.jetbrains.hexana.nativebin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.SbomLocator
import org.jetbrains.hexana.providers.sbom.extractSbomJson

/**
 * Opens a GraalVM Native Image embedded SBOM in the IDE editor. Extraction is
 * delegated to [org.jetbrains.hexana.providers.sbom.extractSbomJson] in the
 * shared layer so the browser webapp can call the same code.
 */
object SbomExtractor {

    /**
     * Returns the decompressed CycloneDX JSON for [locator], or `null` if the
     * locator is out of bounds, oversized, or the gzip stream is corrupt.
     * Delegates to the shared [org.jetbrains.hexana.providers.sbom.extractSbomJson].
     */
    suspend fun extractJson(bytes: CommonByteBuffer, locator: SbomLocator): String? =
        extractSbomJson(bytes, locator)

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
            val json = runBlocking { runCatching { extractJson(bytes, locator) }.getOrNull() }
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