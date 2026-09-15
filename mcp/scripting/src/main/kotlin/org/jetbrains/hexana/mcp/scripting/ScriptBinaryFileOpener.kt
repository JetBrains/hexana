package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.BinaryInputLoader
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.shared.scripting.ScriptBinaryOpener
import org.jetbrains.hexana.shared.scripting.ScriptOpenedBinary

/**
 * Backs the `open(path)` script global on the JVM.
 *
 * Loading goes through [BinaryInputLoader] and [AllowedFilePolicy] — the same two
 * things that decide which file the caller's own `binary` may come from — so
 * `open` reaches exactly the files this host would already have loaded, and a
 * server configured with allowed roots confines it to those roots. It does not
 * widen the sandbox in any other direction: the GraalJS context keeps
 * `HostAccess.NONE`, no class lookup, no GraalJS IO, and no process, thread or
 * native access, and what reaches the script is parsed binary *data*, not a file
 * handle or arbitrary file text.
 *
 * One instance serves one script run. Results are memoized per path string, so
 * `open` inside a loop costs one parse rather than one per iteration, and
 * [HexanaScriptLimits.maxOpenedBinaries] bounds how many distinct binaries a
 * single run can pull into memory.
 *
 * Not synchronized: a script runs on one thread, and the sandbox denies it the
 * thread creation that would be needed to reach this from another.
 */
internal class ScriptBinaryFileOpener(
    private val limits: HexanaScriptLimits,
    private val allowedFilePolicy: AllowedFilePolicy,
) : ScriptBinaryOpener {

    private val opened = LinkedHashMap<String, ScriptOpenedBinary>()

    override fun open(path: String): ScriptOpenedBinary {
        opened[path]?.let { return it }
        if (opened.size >= limits.maxOpenedBinaries) {
            throw IllegalStateException(
                "open(\"$path\") — a script may open at most ${limits.maxOpenedBinaries} additional " +
                    "binaries; reuse the ones it already opened (opening the same path again is free)."
            )
        }

        val loaded = try {
            BinaryInputLoader(
                maxInlineInputBytes = limits.maxInlineBinaryBytes,
                maxFileInputBytes = limits.maxFileBinaryBytes,
                allowedFilePolicy = allowedFilePolicy,
            ).load(BinaryInput.FilePath(path))
        } catch (error: HexanaAnalysisException) {
            // The loader's messages already name the reason and the remedy
            // ("File not found", "exceeds maximum size of …", "outside allowed
            // roots"); prefixing the call keeps it recognisable as this script's.
            throw IllegalArgumentException("open(\"$path\") — ${error.message}", error)
        }

        val result = ScriptOpenedBinary(
            provider = loaded.provider,
            fileName = loaded.identity.path?.binaryFileName(),
        )
        opened[path] = result
        return result
    }
}

/** Last segment of a path, for either separator, so `open(...).name` reads as a file name. */
private fun String.binaryFileName(): String = substringAfterLast('/').substringAfterLast('\\')
