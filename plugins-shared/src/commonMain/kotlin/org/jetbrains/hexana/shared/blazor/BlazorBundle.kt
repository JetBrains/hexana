package org.jetbrains.hexana.shared.blazor

import org.jetbrains.hexana.document.ContentDigest
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.FileSystem
import org.jetbrains.hexana.shared.companionFileName
import org.jetbrains.hexana.shared.companionJoin
import org.jetbrains.hexana.shared.companionParentDirectory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Whether a file still has the hash the boot manifest promises the browser. */
enum class BlazorIntegrity(val label: String) {
    Matches("matches"),
    /** The browser refuses the download ("Failed to find a valid digest in the 'integrity' attribute"). */
    Mismatch("differs from the manifest"),
    Missing("file missing"),
    /** Not checked: too large, no reader, or not asked for. */
    NotChecked(""),
    /** The manifest gives no `sha256-` hash for it. */
    NoHash(""),
}

/** A manifest resource as found in the `_framework` directory. */
data class BlazorBundleFile(
    val resource: BlazorBootResource,
    /** Size on disk, or null when the file is missing. */
    val size: Long?,
    /** Size of the precompressed `<name>.br` the server sends to browsers that accept Brotli. */
    val brotliSize: Long?,
    /** Size of the precompressed `<name>.gz`. */
    val gzipSize: Long?,
    val integrity: BlazorIntegrity,
) {
    /** What a browser downloads for it: the Brotli copy when there is one, else gzip, else the file. */
    val downloadSize: Long? get() = brotliSize ?: gzipSize ?: size
}

/**
 * A Blazor WebAssembly app's `_framework` directory read through its boot manifest: every file the
 * runtime fetches, with sizes and integrity. [currentFileName] is the file the editor shows, so the tab
 * can point at it.
 */
data class BlazorBundle(
    val directory: String,
    /** The manifest's own file (`dotnet.<fp>.js` or `blazor.boot.json`). */
    val configFileName: String,
    val config: BlazorBootConfig,
    val files: List<BlazorBundleFile>,
    val currentFileName: String?,
) {
    val current: BlazorBundleFile? get() = files.firstOrNull { it.resource.fileName == currentFileName }

    companion object {
        /** Largest manifest file read; .NET 10's `dotnet.js` with an embedded manifest is ~60 KB. */
        const val MAX_CONFIG_SIZE: Long = 16L * 1024 * 1024

        /** Largest file hashed for the integrity check; `System.Private.CoreLib` is ~1.6 MB, ICU data ~1 MB. */
        const val MAX_HASHED_FILE_SIZE: Long = 64L * 1024 * 1024

        private val DOTNET_JS = Regex("""dotnet(\.(?!(?:native|runtime)\.)[A-Za-z0-9]+)?\.js""")

        /**
         * The files in [siblingNames] that may hold the manifest, best first: `blazor.boot.json`
         * (.NET 8/9), then `dotnet.js` / `dotnet.<fingerprint>.js` (.NET 10 embeds it there).
         * `dotnet.native[.*].js` and `dotnet.runtime[.*].js` never hold it and do not match, with or
         * without a fingerprint. Ties are broken by name so the order does not depend on directory listing.
         */
        fun configCandidates(siblingNames: Collection<String>): List<String> = buildList {
            if ("blazor.boot.json" in siblingNames) add("blazor.boot.json")
            siblingNames.filter { DOTNET_JS.matches(it) }.sortedWith(compareBy({ it.length }, { it })).forEach(::add)
        }

        /**
         * Puts [config] together with what the directory holds. [sizes] maps file names in the
         * directory to their sizes; [integrity] checks one listed file (return [BlazorIntegrity.NotChecked]
         * to skip). Null when [currentFileName] is given and the manifest does not list it — the file
         * then does not belong to this app build.
         */
        fun assemble(
            directory: String,
            configFileName: String,
            config: BlazorBootConfig,
            sizes: Map<String, Long>,
            currentFileName: String?,
            integrity: (BlazorBootResource) -> BlazorIntegrity,
        ): BlazorBundle? {
            if (currentFileName != null && config.resources.none { it.fileName == currentFileName }) return null
            val files = config.resources.map { resource ->
                val size = sizes[resource.fileName]
                BlazorBundleFile(
                    resource = resource,
                    size = size,
                    brotliSize = sizes["${resource.fileName}.br"],
                    gzipSize = sizes["${resource.fileName}.gz"],
                    integrity = when {
                        size == null -> BlazorIntegrity.Missing
                        resource.integrity?.startsWith(SHA256_PREFIX) != true -> BlazorIntegrity.NoHash
                        else -> integrity(resource)
                    },
                )
            }
            return BlazorBundle(directory, configFileName, config, files, currentFileName)
        }

        /**
         * Reads the bundle of the `.wasm` at [binaryPath] through [fs]: lists its directory, parses the
         * first manifest found and, when [verifyIntegrity], hashes every listed file up to
         * [MAX_HASHED_FILE_SIZE]. Null when the directory has no manifest or it does not list the file.
         * Synchronous and file-system heavy: hosts run it off the UI thread.
         */
        fun load(binaryPath: String, fs: FileSystem, verifyIntegrity: Boolean): BlazorBundle? {
            val directory = companionParentDirectory(binaryPath)
            val entries = fs.listEntries(directory).filter { it.isFile }
            val names = entries.map { it.name }
            // Cheap reject first: a module is only part of a bundle when a manifest sits beside it.
            val candidates = configCandidates(names)
            if (candidates.isEmpty()) return null
            val currentFileName = companionFileName(binaryPath)
            for (candidate in candidates) {
                val path = companionJoin(directory, candidate)
                val size = fs.regularFileSize(path) ?: continue
                if (size > MAX_CONFIG_SIZE) continue
                val config = fs.readFile(path)?.decodeToString()?.let(BlazorBootConfig::parse) ?: continue
                val sizes = names.mapNotNull { name -> fs.regularFileSize(companionJoin(directory, name))?.let { name to it } }.toMap()
                return assemble(directory, candidate, config, sizes, currentFileName) { resource ->
                    if (!verifyIntegrity) return@assemble BlazorIntegrity.NotChecked
                    val filePath = companionJoin(directory, resource.fileName)
                    val fileSize = sizes[resource.fileName] ?: return@assemble BlazorIntegrity.Missing
                    if (fileSize > MAX_HASHED_FILE_SIZE) return@assemble BlazorIntegrity.NotChecked
                    val bytes = fs.readFile(filePath) ?: return@assemble BlazorIntegrity.Missing
                    verify(resource, bytes)
                }
            }
            return null
        }

        /** Checks [bytes] against [resource]'s `sha256-<base64>` integrity value. */
        @OptIn(ExperimentalEncodingApi::class)
        fun verify(resource: BlazorBootResource, bytes: ByteArray): BlazorIntegrity {
            if (resource.integrity?.startsWith(SHA256_PREFIX) != true) return BlazorIntegrity.NoHash
            return try {
                integrityOf(resource, Base64.encode(ContentDigest.sha256(bytes.toCommonByteBuffer())))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BlazorIntegrity.NotChecked
            }
        }

        /** Compares a file's Base64 SHA-256 (computed by whoever read it) with [resource]'s integrity value. */
        fun integrityOf(resource: BlazorBootResource, base64Sha256: String): BlazorIntegrity {
            val expected = resource.integrity?.takeIf { it.startsWith(SHA256_PREFIX) } ?: return BlazorIntegrity.NoHash
            return if (expected == SHA256_PREFIX + base64Sha256) BlazorIntegrity.Matches else BlazorIntegrity.Mismatch
        }

        private const val SHA256_PREFIX = "sha256-"
    }
}
