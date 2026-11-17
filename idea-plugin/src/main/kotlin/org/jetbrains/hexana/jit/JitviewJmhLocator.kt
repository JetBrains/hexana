package org.jetbrains.hexana.jit

import com.intellij.openapi.application.PathManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Resolves the agent jar that `:jvm-jit-jmh` ships as the resource
 * `jmh/jitview-jmh.jar` (see jvm-jit-jmh/build.gradle.kts: profilerJar). It reaches
 * the plugin classpath transitively via `implementation(project(":jvm-jit-jmh"))`,
 * the same way `:jvm-jit` ships its native slice. This extracts it to an on-disk path.
 *
 * That jar is a JVMTI-style `-javaagent` (a shaded jar carrying
 * [org.jetbrains.hexana.jit.jmh.HexanaJmhForkAgent] plus ASM, with a
 * `Premain-Class`/`Agent-Class` manifest). The extracted path is handed to the
 * launched JMH launcher JVM as `-javaagent:<jar>=agent=<libjitview>,dir=<workdir>`;
 * the agent then rewrites JMH's fork command line so each forked benchmark JVM
 * attaches libjitview. We extract it to a stable file under the IDE system cache,
 * exactly mirroring [JitviewAgentLocator]'s extraction contract (content-hashed file
 * name so a plugin upgrade drops a fresh copy rather than reusing a stale one).
 *
 * Returns `null` when the jar wasn't bundled (e.g. a dev build that didn't run the
 * bundle task) — callers skip the JMH-fork instrumentation silently in that case.
 */
object JitviewJmhLocator {
    /** Resource path inside the plugin jar where the profiler jar is shelved. */
    private const val RESOURCE_PATH = "jmh/jitview-jmh.jar"

    @Volatile private var cached: Path? = null

    fun locate(): Path? {
        cached?.let { if (Files.isRegularFile(it)) return it }
        synchronized(this) {
            cached?.let { if (Files.isRegularFile(it)) return it }
            val extracted = extract() ?: return null
            cached = extracted
            return extracted
        }
    }

    private fun extract(): Path? {
        val classLoader = JitviewJmhLocator::class.java.classLoader
        val bytes = classLoader.getResourceAsStream(RESOURCE_PATH)?.use { it.readBytes() }
            ?: return null
        val cacheRoot = try {
            Path.of(PathManager.getSystemPath()).resolve("hexana/jmh")
        } catch (_: Throwable) {
            // Fall back to java.io.tmpdir when called outside the IDE (e.g. unit tests).
            Path.of(System.getProperty("java.io.tmpdir")).resolve("hexana-jmh")
        }
        Files.createDirectories(cacheRoot)

        val hash = sha256Prefix(bytes)
        val out = cacheRoot.resolve("jitview-jmh-$hash.jar")
        if (Files.isRegularFile(out) && Files.size(out) == bytes.size.toLong()) {
            return out
        }

        return try {
            val tmp = Files.createTempFile(cacheRoot, "jitview-jmh-", ".part")
            Files.write(tmp, bytes)
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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
