package org.jetbrains.hexana.jvm.jit

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration smoke: build the JVMTI agent (via the `buildJitviewAgent`
 * Gradle task), run [JitSampleApp] in a child JVM with the agent
 * attached, and verify the resulting dump parses back via [JitFile] and
 * contains a record for the JIT-warmed [JitSampleApp.jitTarget].
 *
 * The test gracefully `assumeTrue`'s away on hosts where the agent
 * couldn't be built (no cmake / no JAVA_HOME with JVMTI headers) — the
 * rest of the jvmTest suite stays green either way.
 */
class JitDumpIntegrationTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `roundtrips a JIT dump produced by the jitview agent on a sample app`() {
        // No `options` to the agent — fall through to its built-in default
        // filename (`default.jit`, see Agent_OnLoad in agent.c).
        runRoundtrip(agentOptions = null, expectedDumpName = "default.jit")
    }

    /**
     * Locks in the agent's `options` parameter parsing — when the user
     * passes `-agentpath:<lib>=<path>`, the agent must honour that path
     * rather than fall back to the default. This is what the IDE-side
     * `JitviewRunConfigurationExtension` relies on to put the dump in the
     * run's working directory under a user-chosen filename.
     */
    @Test
    fun `agent honours a custom dump filename passed via the options string`() {
        runRoundtrip(agentOptions = "custom-name.jit", expectedDumpName = "custom-name.jit")
    }

    private fun runRoundtrip(agentOptions: String?, expectedDumpName: String) {
        val agent = locateAgentLibrary()
        assumeTrue(
            "JVMTI agent binary not built — skipping. See :jvm-jit:buildJitviewAgent.",
            agent != null,
        )
        agent!!

        val workdir = tempFolder.newFolder("jit-dump-run")
        val (exitCode, stdout, stderr) = runSampleAppWithAgent(agent, workdir, agentOptions)

        assertTrue(
            "Child JVM failed (exit=$exitCode).\nstdout:\n$stdout\nstderr:\n$stderr",
            exitCode == 0,
        )

        val dump = File(workdir, expectedDumpName)
        assertTrue(
            "Agent did not produce $dump (size=${if (dump.exists()) dump.length() else -1}). stderr:\n$stderr",
            dump.exists() && dump.length() > 0,
        )

        val parsed = JitFile.fromFile(dump)
        assertTrue("dump did not parse as a MIN file", parsed != null)
        parsed!!

        assertTrue(
            "expected at least one symbol in the dump, got 0",
            parsed.symbols.isNotEmpty(),
        )

        // The hot sample method should be among the JIT-compiled symbols.
        // Symbol signatures are emitted as the agent's
        // `[<class>:_::<method>:<descriptor>:_]` form.
        val sampleHit = parsed.symbols.firstOrNull {
            val sym = it.sym() ?: return@firstOrNull false
            sym.classSignature.endsWith("JitSampleApp;") && sym.methodName == "jitTarget"
        }
        assertTrue(
            "expected to see JitSampleApp.jitTarget in the dump; saw " +
                parsed.symbols.mapNotNull { it.sym() }.map { "${it.classSignature}.${it.methodName}" }
                    .joinToString(),
            sampleHit != null,
        )
        sampleHit!!

        assertTrue("JIT-compiled method should have non-empty code", sampleHit.codeBlock.isNotEmpty())
        assertTrue("code size should match the sym entry", sampleHit.codeBlock.size.toULong() == sampleHit.codeSize)

        // v2 format: the agent must dump the method's JVM bytecode bytes
        // captured via GetBytecodes alongside the native code.
        assertTrue(
            "expected JVM bytecode to be captured for jitTarget; got ${sampleHit.bytecode.size} bytes",
            sampleHit.bytecode.isNotEmpty(),
        )
    }

    // ------------------------------------------------------------------------

    private fun locateAgentLibrary(): File? {
        val configured = System.getProperty("hexana.jit.agentBuildDir")?.let { File(it) }
            ?: File(System.getProperty("user.dir")).resolve("build/jvm-jit")
        val name = osLibraryName("jitview")
        for (root in listOfNotNull(configured)) {
            val direct = root.resolve(name)
            if (direct.isFile) return direct
            // CMake on multi-config generators may put the artifact in a subdir.
            root.walkTopDown().firstOrNull { it.isFile && it.name == name }?.let { return it }
        }
        return null
    }

    private fun osLibraryName(base: String): String {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            os.contains("mac") || os.contains("darwin") -> "lib$base.dylib"
            os.contains("win") -> "$base.dll"
            else -> "lib$base.so"
        }
    }

    private data class ChildRun(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runSampleAppWithAgent(agent: File, workdir: File, agentOptions: String?): ChildRun {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, "bin/java").let { if (it.exists()) it else File(javaHome, "bin/java.exe") }
        val classpath = System.getProperty("java.class.path")

        val agentArg = buildString {
            append("-agentpath:")
            append(agent.absolutePath)
            if (!agentOptions.isNullOrEmpty()) {
                append('=')
                append(agentOptions)
            }
        }
        val cmd = listOf(
            javaBin.absolutePath,
            agentArg,
            "-cp", classpath,
            JitSampleApp::class.java.name,
        )
        val proc = ProcessBuilder(cmd)
            .directory(workdir)
            .redirectErrorStream(false)
            .start()
        val finishedInTime = proc.waitFor(60, TimeUnit.SECONDS)
        if (!finishedInTime) {
            proc.destroyForcibly()
            return ChildRun(-1, "", "child JVM timed out after 60s")
        }
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        return ChildRun(proc.exitValue(), stdout, stderr)
    }
}
