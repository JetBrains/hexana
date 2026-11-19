package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PdbBinaryProvider
import org.jetbrains.hexana.providers.PdbMatch
import org.jetbrains.hexana.providers.PdbSymbolsBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.PortablePdbBinaryProvider
import org.jetbrains.hexana.providers.WebcilAssembly
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
        val directory = companionParentDirectory(binaryPath)
        val fileName = companionFileName(binaryPath)
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        return listOfNotNull(
            reference.path?.let(::companionFileName)?.takeIf { it.isNotEmpty() && it != "." && it != ".." }?.let { companionJoin(directory, it) },
            companionJoin(directory, "$stem.pdb"),
            companionJoin(directory, "$fileName.pdb"),
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

    /**
     * Where the Portable PDB of a Blazor WebAssembly assembly may be, best first. The SDK fingerprints
     * both files independently (`app.ol0snvssxq.wasm` next to `app.en9mhn99g3.pdb`), so beyond the
     * recorded name (`app.pdb`) and the module's own stem, [siblingNames] — the directory listing,
     * when the host can list it — supplies `<recorded stem>.<fingerprint>.pdb`. The GUID check in
     * [attach] decides; a name only nominates. Empty when the module needs nothing.
     */
    fun candidatePaths(assembly: WebcilAssembly, binaryPath: String?, siblingNames: List<String> = emptyList()): List<String> {
        val reference = assembly.pdbReference ?: return emptyList()
        if (binaryPath == null || assembly.portablePdb != null) return emptyList()
        val directory = companionParentDirectory(binaryPath)
        val fileName = companionFileName(binaryPath)
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        val recorded = reference.path?.let(::companionFileName)?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        val recordedStem = recorded?.substringBeforeLast('.', missingDelimiterValue = recorded)
        val fingerprinted = recordedStem?.let { prefix ->
            siblingNames.filter { name ->
                name.startsWith("$prefix.") && name.endsWith(".pdb", ignoreCase = true) &&
                    // Exactly one segment between them: `app.<fingerprint>.pdb`, not `app.Extra.<fp>.pdb`.
                    name.removePrefix("$prefix.").dropLast(4).let { it.isNotEmpty() && '.' !in it }
            }.sorted()
        }.orEmpty()
        return (listOfNotNull(recorded?.let { companionJoin(directory, it) }, companionJoin(directory, "$stem.pdb")) +
            fingerprinted.map { companionJoin(directory, it) }).distinct()
    }

    /** Attaches [bytes] as the Portable PDB of [assembly]; anything else is [PdbMatch.Unreadable]. */
    fun attach(assembly: WebcilAssembly, bytes: ByteArray): PdbMatch = try {
        val buffer = bytes.toCommonByteBuffer()
        when {
            PortablePdbBinaryProvider.isPortablePdbFile(buffer) -> assembly.attachDebugSymbols(PortablePdbBinaryProvider(buffer))
            PdbParser.isPdb(buffer) -> PdbMatch.WrongKind
            else -> PdbMatch.Unreadable
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PdbMatch.Unreadable
    }

    /** [load] for a Blazor WebAssembly assembly at [binaryPath]; lists its directory for fingerprinted names. */
    fun load(assembly: WebcilAssembly, binaryPath: String, fs: FileSystem): PdbMatch? {
        if (assembly.pdbReference == null || assembly.portablePdb != null) return null
        val siblings = fs.listEntries(companionParentDirectory(binaryPath)).filter { it.isFile }.map { it.name }
        var outcome: PdbMatch? = null
        for (path in candidatePaths(assembly, binaryPath, siblings)) {
            if (!fs.fileExists(path)) continue
            val size = fs.regularFileSize(path)
            if (size == null || size > MAX_AUTOMATIC_PDB_SIZE) {
                outcome = if (size == null) PdbMatch.Unreadable else PdbMatch.TooLarge
                continue
            }
            outcome = fs.readFile(path)?.let { attach(assembly, it) } ?: PdbMatch.Unreadable
            if (outcome == PdbMatch.Matches) break
        }
        return outcome
    }
}
