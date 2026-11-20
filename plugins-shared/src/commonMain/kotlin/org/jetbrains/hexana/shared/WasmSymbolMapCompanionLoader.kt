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
        val candidates = candidatePaths(provider, binaryPath, siblings) + dotnetBuildCandidates(binaryPath, fs)
        for (path in candidates.distinct()) {
            if (!fs.fileExists(path)) continue
            val size = fs.regularFileSize(path) ?: continue
            if (size > MAX_AUTOMATIC_SYMBOL_MAP_SIZE) continue
            val bytes = fs.readFile(path) ?: continue
            if (attach(provider, path, bytes)) return true
        }
        return false
    }

    private const val DOTNET_RUNTIME_MODULE = "dotnet.native"

    /** How far up from a published `_framework` the project directory may be: `bin/<config>/<tfm>/publish/wwwroot/_framework`. */
    private const val MAX_PROJECT_DEPTH = 7

    /**
     * Where the .NET SDK leaves the symbol map of a Blazor WebAssembly app's `dotnet.native.wasm`:
     * `dotnet publish` with `WasmEmitSymbolMap` writes `dotnet.native.js.symbols` to
     * `obj/<config>/<tfm>/wasm/for-publish/` (`for-build/` for `dotnet build`) and does not copy it next to
     * the published module. So for that module only, the directories above it are searched for a project's
     * `obj` directory. [attach] rejects a map of another build: an AOT build's function count changes with
     * every code change.
     */
    fun dotnetBuildCandidates(binaryPath: String, fs: FileSystem): List<String> {
        val fileName = companionFileName(binaryPath)
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        if (stem != DOTNET_RUNTIME_MODULE && stem.substringBeforeLast('.', "") != DOTNET_RUNTIME_MODULE) return emptyList()
        val found = mutableListOf<String>()
        var directory = companionParentDirectory(binaryPath)
        repeat(MAX_PROJECT_DEPTH) {
            val entries = fs.listEntries(directory)
            if (entries.any { it.isDirectory && it.name == "obj" }) {
                val obj = companionJoin(directory, "obj")
                for (config in fs.listEntries(obj).filter { it.isDirectory }) {
                    val configDir = companionJoin(obj, config.name)
                    for (tfm in fs.listEntries(configDir).filter { it.isDirectory }) {
                        val wasm = companionJoin(companionJoin(configDir, tfm.name), "wasm")
                        for (flavor in listOf("for-publish", "for-build")) {
                            found += companionJoin(companionJoin(wasm, flavor), "$DOTNET_RUNTIME_MODULE$SUFFIX")
                        }
                    }
                }
                return found
            }
            val parent = companionParentDirectory(directory)
            if (parent == directory || parent.isEmpty()) return found
            directory = parent
        }
        return found
    }
}
