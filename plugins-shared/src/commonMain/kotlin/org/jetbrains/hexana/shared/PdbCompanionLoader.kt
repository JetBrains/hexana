package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PdbBinaryProvider
import org.jetbrains.hexana.providers.PdbMatch
import org.jetbrains.hexana.providers.PdbSymbolsBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.PortablePdbBinaryProvider
import org.jetbrains.hexana.pdb.PdbParser

/**
 * Finds the `.pdb` a PE image's CodeView record names and attaches it when it matches
 * ([PeBinaryProvider.attachDebugSymbols]). Hosts run this off the UI thread before building
 * tabs: IntelliJ through its [FileSystem], the VS Code webview through its asynchronous file
 * reads, which is why candidate paths and attaching are separate steps.
 */
object PdbCompanionLoader {
    /**
     * Where the PDB may be, best first: next to the image under the recorded name, under the
     * image's own stem, then the recorded path itself (an absolute path on the build machine,
     * which is this machine for a local build). Empty when nothing needs loading: no CodeView
     * record, no path for the image, or debug symbols already present (embedded or attached).
     */
    fun candidatePaths(provider: PeBinaryProvider, binaryPath: String? = provider.sourcePath): List<String> {
        val reference = provider.pdbReference ?: return emptyList()
        if (binaryPath == null || provider.hasDebugSymbols) return emptyList()
        val directory = parentDirectory(binaryPath)
        val fileName = fileName(binaryPath)
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        return listOfNotNull(
            reference.path?.let { join(directory, fileName(it)) },
            join(directory, "$stem.pdb"),
            join(directory, "$fileName.pdb"),
            reference.path?.takeIf { it.startsWith("/") || it.getOrNull(1) == ':' },
        ).distinct()
    }

    /** Attaches [bytes] read from [path]; non-PDB content is [PdbMatch.Unreadable] and changes nothing. */
    fun attach(provider: PeBinaryProvider, path: String, bytes: ByteArray): PdbMatch {
        val buffer = bytes.toCommonByteBuffer()
        val symbols: PdbSymbolsBinaryProvider = when {
            PdbParser.isPdb(buffer) -> PdbBinaryProvider(buffer)
            PortablePdbBinaryProvider.isPortablePdbFile(buffer) -> PortablePdbBinaryProvider(buffer)
            else -> return PdbMatch.Unreadable
        }
        return provider.attachDebugSymbols(symbols, path)
    }

    /** Tries every candidate through [fs]; returns the outcome for the last file found, or null when none exists. */
    fun load(provider: PeBinaryProvider, fs: FileSystem): PdbMatch? {
        var outcome: PdbMatch? = null
        for (path in candidatePaths(provider)) {
            if (!fs.fileExists(path)) continue
            val bytes = fs.readFile(path) ?: continue
            outcome = attach(provider, path, bytes)
            if (outcome == PdbMatch.Matches) break
        }
        return outcome
    }

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun parentDirectory(path: String): String {
        val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (cut <= 0) path.take(cut + 1) else path.substring(0, cut)
    }

    private fun join(directory: String, name: String): String = when {
        directory.isEmpty() -> name
        directory.endsWith('/') || directory.endsWith('\\') -> directory + name
        else -> "$directory${if (directory.contains('\\') && !directory.contains('/')) '\\' else '/'}$name"
    }
}
