package org.jetbrains.hexana.jit

import com.intellij.openapi.application.PathManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Resolves the JVMTI agent shipped inside the plugin (built by the
 * `:jvm-jit:bundleJitviewAgent` gradle task) to an extracted on-disk path
 * suitable for `-agentpath:<...>`.
 *
 * Native agents can't be loaded out of a jar directly — the JVM needs a real
 * file path. We extract the bundled artifact into the IDE's per-installation
 * system cache (`PathManager.getSystemPath()/hexana/native/...`) the first
 * time it's requested, then reuse it. The on-disk filename includes a short
 * content hash so a plugin upgrade overwrites a stale extract instead of
 * silently keeping the old one.
 *
 * Returns `null` when no slice was shipped for the host OS / arch — the
 * agent build runs on whatever host produced the plugin distribution, so a
 * plugin built on macOS won't have a Linux slice and vice versa.
 */
object JitviewAgentLocator {
    /** Resource directory inside the plugin jar where slices are shelved. */
    private const val RESOURCE_PREFIX = "/native"

    /** Universal macOS dylib name (one slice covers x86_64 + arm64). */
    private const val DARWIN_LIB = "libjitview.dylib"

    /** Linux per-arch slice. */
    private const val LINUX_LIB = "libjitview.so"

    /** Windows per-arch slice. */
    private const val WINDOWS_LIB = "jitview.dll"

    @Volatile private var cached: Path? = null

    fun locate(): Path? {
        cached?.let { if (Files.isRegularFile(it)) return it }
        synchronized(this) {
            cached?.let { if (Files.isRegularFile(it)) return it }
            val resource = resourcePathForHost() ?: return null
            val extracted = extract(resource) ?: return null
            cached = extracted
            return extracted
        }
    }

    /**
     * The host-specific `<resource path>` under the plugin jar. Returns
     * `null` if the host's OS / arch isn't a known slice — caller decides
     * what to do (skip / surface a UI error).
     */
    private fun resourcePathForHost(): String? {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val rawArch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val arch = when (rawArch) {
            "x86_64", "amd64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return null
        }
        return when {
            "mac" in os || "darwin" in os -> "$RESOURCE_PREFIX/darwin/$DARWIN_LIB"
            "win" in os -> "$RESOURCE_PREFIX/windows-$arch/$WINDOWS_LIB"
            "linux" in os || "nix" in os -> "$RESOURCE_PREFIX/linux-$arch/$LINUX_LIB"
            else -> null
        }
    }

    private fun extract(resourcePath: String): Path? {
        val classLoader = JitviewAgentLocator::class.java.classLoader
        val bytes = classLoader.getResourceAsStream(resourcePath.trimStart('/'))?.use { it.readBytes() }
            ?: return null
        val cacheRoot = try {
            Path.of(PathManager.getSystemPath()).resolve("hexana/native")
        } catch (_: Throwable) {
            // Fall back to java.io.tmpdir when called outside the IDE
            // (e.g. unit tests).
            Path.of(System.getProperty("java.io.tmpdir")).resolve("hexana-native")
        }
        Files.createDirectories(cacheRoot)

        val hash = sha256Prefix(bytes)
        val fileName = resourcePath.substringAfterLast('/')
        val dot = fileName.lastIndexOf('.')
        val stamped = if (dot < 0) "$fileName-$hash" else fileName.substring(0, dot) + "-$hash" + fileName.substring(dot)
        val out = cacheRoot.resolve(stamped)

        if (Files.isRegularFile(out) && Files.size(out) == bytes.size.toLong()) {
            return out.also { it.toFile().setExecutable(true, false) }
        }

        return try {
            val tmp = Files.createTempFile(cacheRoot, "libjitview-", ".part")
            Files.write(tmp, bytes)
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            out.toFile().setExecutable(true, false)
            out
        } catch (e: IOException) {
            null
        }
    }

    private fun sha256Prefix(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append(((digest[i].toInt() and 0xFF).toString(16)).padStart(2, '0'))
        }
        return sb.toString()
    }
}
