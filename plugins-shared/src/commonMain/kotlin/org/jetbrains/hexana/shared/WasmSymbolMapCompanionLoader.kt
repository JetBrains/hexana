package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds the Emscripten symbol map (`--emit-symbol-map`) published next to a stripped Wasm module and
 * attaches it ([WasmBinaryProvider.attachSymbolMap]), so its functions have names. The case it is
 * built for is .NET's `dotnet.native.wasm` (Blazor WebAssembly's Mono runtime, which has no name
 * section) and its `dotnet.native.js.symbols`; any Emscripten build with a symbol map benefits.
 *
 * Like [PdbCompanionLoader], hosts run this off the UI thread before building tabs; candidate paths
 * and attaching are separate steps for the VS Code webview's asynchronous reads.
 */
object WasmSymbolMapCompanionLoader {
    /** Largest map loaded automatically; `dotnet.native.js.symbols` is about 400 KB. */
    const val MAX_AUTOMATIC_SYMBOL_MAP_SIZE: Long = 64L * 1024 * 1024

    private const val SUFFIX = ".js.symbols"

    /**
     * Where the map may be, best first: `foo.js.symbols` next to `foo.wasm`, then — for a name the
     * .NET SDK fingerprinted (`dotnet.native.a6k1euv12n.wasm`) — the unfingerprinted name and any
     * fingerprinted one in [siblingNames]. Attaching checks the map against the module, so a name only
     * nominates. Empty when the module has its own function names or no path.
     */
    fun candidatePaths(provider: WasmBinaryProvider, binaryPath: String?, siblingNames: List<String> = emptyList()): List<String> {
        if (binaryPath == null || provider.wasmModule == null) return emptyList()
        if (provider.symbolMapPath != null || provider.nameSection?.functionSubsection != null) return emptyList()
        val directory = companionParentDirectory(binaryPath)
        val fileName = companionFileName(binaryPath)
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        val unfingerprinted = stem.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        val names = buildList {
            add("$stem$SUFFIX")
            add("$fileName.symbols")
            if (unfingerprinted != null) {
                add("$unfingerprinted$SUFFIX")
                siblingNames.filter { name ->
                    name.startsWith("$unfingerprinted.") && name.endsWith(SUFFIX) &&
                        name.removePrefix("$unfingerprinted.").removeSuffix(SUFFIX).let { it.isNotEmpty() && '.' !in it }
                }.sorted().forEach(::add)
            }
        }
        return names.distinct().map { companionJoin(directory, it) }
    }

    /** Parses [bytes] as a symbol map and attaches it; false when it is not one or does not fit the module. */
    fun attach(provider: WasmBinaryProvider, path: String, bytes: ByteArray): Boolean = try {
        provider.attachSymbolMap(WasmSymbolMap.parse(bytes.decodeToString()), path)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Tries every candidate through [fs]; true when a map was attached. */
    fun load(provider: WasmBinaryProvider, binaryPath: String, fs: FileSystem): Boolean {
        // Listing the directory only pays off for a module that still needs names.
        if (candidatePaths(provider, binaryPath).isEmpty()) return false
        val siblings = fs.listEntries(companionParentDirectory(binaryPath)).filter { it.isFile }.map { it.name }
        for (path in candidatePaths(provider, binaryPath, siblings)) {
            if (!fs.fileExists(path)) continue
            val size = fs.regularFileSize(path) ?: continue
            if (size > MAX_AUTOMATIC_SYMBOL_MAP_SIZE) continue
            val bytes = fs.readFile(path) ?: continue
            if (attach(provider, path, bytes)) return true
        }
        return false
    }
}
