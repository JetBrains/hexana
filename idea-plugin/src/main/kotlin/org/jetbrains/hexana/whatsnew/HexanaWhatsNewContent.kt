package org.jetbrains.hexana.whatsnew

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

private const val WHATS_NEW_RESOURCE_ROOT = "whatsnew"
private const val WHATS_NEW_INDEX = "index.html"
private const val WHATS_NEW_MANIFEST = "manifest.txt"

private val LOG = logger<HexanaWhatsNewContent>()

internal data class HexanaWhatsNewContent(
    val version: String,
    val resourceVersion: String,
    val rootDirectory: Path,
    val indexFile: Path,
) {
    fun dispose() {
        runCatching {
            NioFiles.deleteRecursively(rootDirectory)
        }.onFailure {
            LOG.warn("Cannot delete Hexana what's new temporary directory", it)
        }
    }
}

internal object HexanaWhatsNewContentService {
    fun createContent(version: String): HexanaWhatsNewContent? {
        val resourceVersion = resolveWhatsNewResourceVersion(version) ?: return null
        val resourceRoot = "$WHATS_NEW_RESOURCE_ROOT/$resourceVersion"
        val manifestEntries = getManifestEntries(resourceRoot) ?: return null
        val rootDirectory = FileUtil.createTempDirectory("hexana-whats-new-", null).toPath()

        return try {
            copyResourceFile("$resourceRoot/$WHATS_NEW_INDEX", rootDirectory.resolve(WHATS_NEW_INDEX))
            for (asset in manifestEntries) {
                copyResourceFile("$resourceRoot/$asset", rootDirectory.resolve(asset))
            }

            HexanaWhatsNewContent(
                version = version,
                resourceVersion = resourceVersion,
                rootDirectory = rootDirectory,
                indexFile = rootDirectory.resolve(WHATS_NEW_INDEX),
            )
        } catch (e: Exception) {
            runCatching { NioFiles.deleteRecursively(rootDirectory) }
            LOG.warn("Cannot prepare Hexana what's new content", e)
            null
        }
    }
}

internal fun resolveWhatsNewResourceVersion(version: String): String? {
    if (hasCompleteWhatsNewResource(version)) {
        return version
    }
    return null
}

private fun hasCompleteWhatsNewResource(resourceVersion: String): Boolean {
    val resourceRoot = "$WHATS_NEW_RESOURCE_ROOT/$resourceVersion"
    val manifestEntries = getManifestEntries(resourceRoot) ?: return false
    return hasResource("$resourceRoot/$WHATS_NEW_INDEX") &&
        manifestEntries.all { hasResource("$resourceRoot/$it") }
}

private fun getManifestEntries(resourceRoot: String): List<String>? {
    val manifestPath = "$resourceRoot/$WHATS_NEW_MANIFEST"
    val stream = HexanaWhatsNewContentService::class.java.classLoader.getResourceAsStream(manifestPath) ?: return null
    return stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }
}

private fun copyResourceFile(resourcePath: String, target: Path) {
    val classLoader = HexanaWhatsNewContentService::class.java.classLoader
    val source = classLoader.getResourceAsStream(resourcePath) ?: error("Missing resource: $resourcePath")
    target.parent?.createDirectories()
    source.use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

private fun hasResource(resourcePath: String): Boolean {
    return HexanaWhatsNewContentService::class.java.classLoader.getResource(resourcePath) != null
}
