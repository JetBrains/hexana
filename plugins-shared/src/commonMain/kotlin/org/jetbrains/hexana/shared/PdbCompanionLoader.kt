package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PdbBinaryProvider
import org.jetbrains.hexana.providers.PdbMatch
import org.jetbrains.hexana.providers.PdbSymbolsBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.PortablePdbBinaryProvider
import org.jetbrains.hexana.pdb.PdbParser
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds the `.pdb` a PE image's CodeView record names and attaches it when it matches
 * ([PeBinaryProvider.attachDebugSymbols]). Hosts run this off the UI thread before building
 * tabs: IntelliJ through its [FileSystem], the VS Code webview through its asynchronous file
 * reads, which is why candidate paths and attaching are separate steps.
 */
object PdbCompanionLoader {
    /**
     * Largest PDB loaded automatically. The whole file is read into memory, and a PE image controls
     * the name looked up, so the bound keeps a crafted or huge companion from exhausting the heap.
     */
    const val MAX_AUTOMATIC_PDB_SIZE: Long = 512L * 1024 * 1024

    /**
     * Where the PDB may be, best first: next to the image under the recorded file name, then
     * under the image's own name. Only the file-name part of the recorded path is used: the path
     * is chosen by whoever built the image, so it is never followed elsewhere on this machine.
     * Empty when nothing needs loading: no CodeView record, no path for the image, or debug
     * symbols already present (a decodable embedded PDB, or one attached earlier).
     */
    fun candidatePaths(provider: PeBinaryProvider, binaryPath: String? = provider.sourcePath): List<String> {
        val reference = provider.pdbReference ?: return emptyList()
        if (binaryPath == null || provider.debugSymbols != null) return emptyList()
        val directory = parentDirectory(binaryPath)
        val fileName = fileName(binaryPath)
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        return listOfNotNull(
            reference.path?.let(::fileName)?.takeIf { it.isNotEmpty() && it != "." && it != ".." }?.let { join(directory, it) },
            join(directory, "$stem.pdb"),
            join(directory, "$fileName.pdb"),
        ).distinct()
    }

    /**
     * Attaches [bytes] read from [path]. Content that is neither kind of PDB, or that fails to
     * decode, is recorded on [provider] as [PdbMatch.Unreadable] so the Debug Context tab says so.
     */
    fun attach(provider: PeBinaryProvider, path: String, bytes: ByteArray): PdbMatch {
        val outcome = try {
            val buffer = bytes.toCommonByteBuffer()
            val symbols: PdbSymbolsBinaryProvider? = when {
                PdbParser.isPdb(buffer) -> PdbBinaryProvider(buffer)
                PortablePdbBinaryProvider.isPortablePdbFile(buffer) -> PortablePdbBinaryProvider(buffer)
                else -> null
            }
            symbols?.let { provider.attachDebugSymbols(it, path) } ?: PdbMatch.Unreadable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PdbMatch.Unreadable
        }
        if (outcome == PdbMatch.Unreadable) provider.rejectDebugSymbols(path, outcome)
        return outcome
    }

    /**
     * Tries every candidate through [fs]; returns the outcome for the last file found, or null when
     * none exists. Only regular files up to [MAX_AUTOMATIC_PDB_SIZE] are read.
     */
    fun load(provider: PeBinaryProvider, fs: FileSystem): PdbMatch? {
        var outcome: PdbMatch? = null
        for (path in candidatePaths(provider)) {
            if (!fs.fileExists(path)) continue
            val size = fs.regularFileSize(path)
            if (size == null || size > MAX_AUTOMATIC_PDB_SIZE) {
                outcome = if (size == null) PdbMatch.Unreadable else PdbMatch.TooLarge
                provider.rejectDebugSymbols(path, outcome)
                continue
            }
            val bytes = fs.readFile(path)
            if (bytes == null) {
                outcome = PdbMatch.Unreadable
                provider.rejectDebugSymbols(path, outcome)
                continue
            }
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
