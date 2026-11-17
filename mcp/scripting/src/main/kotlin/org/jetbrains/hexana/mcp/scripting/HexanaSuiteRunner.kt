package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.BinaryInputLoader
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.shared.binaryFormatId
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.SCRIPT_CONSOLE_GLOBAL
import org.jetbrains.hexana.shared.scripting.SCRIPT_HEXANA_GLOBAL
import org.jetbrains.hexana.shared.scripting.ScriptConsoleEntry
import org.jetbrains.hexana.shared.scripting.ScriptOutput
import org.jetbrains.hexana.shared.scripting.SuiteArtifactDeclaration
import org.jetbrains.hexana.shared.scripting.SuiteNodeKind
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus
import org.jetbrains.hexana.shared.scripting.scriptBinaryApi
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Runner for the Scripting v2 test-suite profile.
 *
 * A parallel entry point to [HexanaScriptRunner] with a different context
 * profile: ESM module loading through a read-only rooted [SuiteModuleFileSystem],
 * `js.text-encoding` (TextEncoder/TextDecoder), a WinterTC prelude
 * (queueMicrotask, atob/btoa, structuredClone, URL/URLSearchParams), seeded
 * deterministic `crypto`, host-driven `setTimeout`/`clearTimeout`, and the
 * suite registration globals `describe`/`test`/`beforeEach`/`afterEach`.
 *
 * The base profile is untouched: this class deliberately duplicates the sandbox
 * setup instead of widening [HexanaScriptRunner.buildScriptContext].
 */
class HexanaSuiteRunner(
    private val limits: HexanaScriptLimits = HexanaScriptLimits(),
    private val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
) {

    /**
     * Runs one suite file: evaluates it as an ES module (collection pass), then
     * executes the collected test tree.
     *
     * @param suiteFilePath path of the suite `.mjs`/`.js` file
     * @param artifacts host mapping from `hexana.artifact` role names to file paths
     *   (`--artifact role=path` on the CLI)
     * @param libRoot directory containing importable packages (a `node_modules`-shaped
     *   tree); bare import specifiers resolve under it
     * @param seed explicit DRBG seed as a hex string; when null the seed is derived
     *   from the script text and the artifact file contents
     * @param testTimeoutMillis default per-test timeout, overridable per test via
     *   `test(name, {timeout}, fn)`
     * @param testPathFilter when non-null, only the tests whose full path (list of
     *   describe names + test name) appears in this set are executed. Filtered-out
     *   tests appear in the result tree as SKIP with duration 0 so the tree shape
     *   is identical to an unfiltered run. `test.skip` nodes remain SKIP regardless.
     *   Each entry is a list of strings, e.g. `listOf("math", "adds")` for a test
     *   named "adds" inside `describe("math", ...)`. Top-level tests have a
     *   single-element path. null means run all tests (existing callers unchanged).
     * @param onConsole invoked for every console entry as it is emitted
     * @return result with [HexanaScriptResult.suite] carrying the test tree; test
     *   failures keep status SUCCESS, while a collection-time error is SCRIPT_ERROR
     */
    fun runSuite(
        suiteFilePath: String,
        artifacts: Map<String, String> = emptyMap(),
        libRoot: String? = null,
        seed: String? = null,
        testTimeoutMillis: Long = DEFAULT_TEST_TIMEOUT_MILLIS,
        testPathFilter: Set<List<String>>? = null,
        onConsole: ((ScriptConsoleEntry) -> Unit)? = null,
    ): HexanaScriptResult {
        val started = System.nanoTime()
        val suitePath: Path
        val scriptText: String
        val libRootPath: Path?
        try {
            suitePath = allowedFilePolicy.resolveAllowedRegularFile(suiteFilePath, "Suite file")
            val size = Files.size(suitePath)
            if (size > limits.maxScriptFileBytes) {
                return failureResult(
                    HexanaScriptStatus.INVALID_INPUT,
                    HexanaScriptDiagnostic(
                        code = "RESOURCE_LIMIT_EXCEEDED",
                        message = "Suite file exceeds maximum size of ${limits.maxScriptFileBytes} bytes",
                    ),
                    suiteFilePath, started,
                )
            }
            scriptText = Files.readString(suitePath)
            libRootPath = libRoot?.let { allowedFilePolicy.resolveAllowedDirectory(it, "Suite library root") }
        } catch (error: HexanaAnalysisException) {
            return failureResult(
                HexanaScriptStatus.INVALID_INPUT,
                HexanaScriptDiagnostic(
                    code = error.code.name,
                    message = error.message,
                    exceptionClass = error::class.java.name,
                ),
                suiteFilePath, started,
            )
        } catch (error: Exception) {
            return internalError(suiteFilePath, error, started)
        }

        // Validate every artifact path against the policy BEFORE the seed
        // derivation streams file bytes: without this, deriveSeed is a content
        // oracle for files outside the allowed roots. Missing files are skipped
        // here exactly as before (the binder reports them when the role is used).
        val validatedArtifacts = mutableMapOf<String, Path>()
        for ((role, rawArtifactPath) in artifacts) {
            val resolved = try {
                allowedFilePolicy.resolveAllowedRegularFile(rawArtifactPath, "Artifact")
            } catch (error: HexanaAnalysisException) {
                if (error.code == HexanaAnalysisErrorCode.FILE_NOT_FOUND) {
                    continue
                }
                return failureResult(
                    HexanaScriptStatus.SCRIPT_ERROR,
                    HexanaScriptDiagnostic(
                        code = "RUNTIME_ERROR",
                        message = "Artifact \"$role\" ($rawArtifactPath): ${error.message}",
                        exceptionClass = error::class.java.name,
                    ),
                    suiteFilePath, started,
                )
            }
            validatedArtifacts[role] = resolved
        }

        val seedBytes = try {
            if (seed != null) {
                Sha256CounterDrbg.decodeHexSeed(seed)
            } else {
                Sha256CounterDrbg.deriveSeed(scriptText, validatedArtifacts)
            }
        } catch (error: IllegalArgumentException) {
            return failureResult(
                HexanaScriptStatus.INVALID_INPUT,
                HexanaScriptDiagnostic(code = "INVALID_INPUT", message = error.message ?: "Invalid seed"),
                suiteFilePath, started,
            )
        }

        val console = SuiteConsole(onConsole)
        val contextRef = AtomicReference<Context?>()
        val executor = Executors.newSingleThreadExecutor(suiteThreadFactory)
        val future = executor.submit(Callable {
            executeSuite(
                suitePath, scriptText, artifacts, libRootPath, seedBytes,
                testTimeoutMillis, testPathFilter, console, contextRef, started,
            )
        })
        return try {
            future.get(limits.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            contextRef.get()?.close(true)
            future.cancel(true)
            failureResult(
                HexanaScriptStatus.TIMEOUT,
                HexanaScriptDiagnostic(
                    code = "TIMEOUT",
                    message = "Suite timed out after ${limits.timeoutMillis} ms",
                ),
                suitePath.toString(), started, console.entries(),
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            contextRef.get()?.close(true)
            future.cancel(true)
            failureResult(
                HexanaScriptStatus.INTERNAL_ERROR,
                HexanaScriptDiagnostic(code = "INTERRUPTED", message = "Suite execution was interrupted"),
                suitePath.toString(), started, console.entries(),
            )
        } catch (error: ExecutionException) {
            when (val cause = error.cause) {
                is VirtualMachineError -> throw cause
                is LinkageError -> throw cause
                else -> internalError(suitePath.toString(), cause ?: error, started, console.entries())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Declaration-collection mode: evaluates the suite module WITHOUT any role
     * mappings and WITHOUT executing tests, so the IDE can harvest
     * [HexanaScriptResult.artifactDeclarations] (settings-editor role table,
     * "which suites match this binary" scan).
     *
     * `hexana.artifact` returns a benign inert stub for every role (mapped or
     * not; no fail-fast, no file IO), so a plain collection pass runs to
     * completion unless the user's own top-level code throws. On such an error
     * the declarations seen up to that point are still returned (mirroring the
     * 2A flags precedent). [HexanaScriptResult.suite] carries the collected
     * registration tree with every node SKIP and zero durations.
     *
     * @param suiteFilePath path of the suite `.mjs`/`.js` file
     * @param libRoot directory containing importable packages, as in [runSuite]
     */
    fun collectSuiteDeclarations(
        suiteFilePath: String,
        libRoot: String? = null,
    ): HexanaScriptResult {
        val started = System.nanoTime()
        val suitePath: Path
        val scriptText: String
        val libRootPath: Path?
        try {
            suitePath = allowedFilePolicy.resolveAllowedRegularFile(suiteFilePath, "Suite file")
            val size = Files.size(suitePath)
            if (size > limits.maxScriptFileBytes) {
                return failureResult(
                    HexanaScriptStatus.INVALID_INPUT,
                    HexanaScriptDiagnostic(
                        code = "RESOURCE_LIMIT_EXCEEDED",
                        message = "Suite file exceeds maximum size of ${limits.maxScriptFileBytes} bytes",
                    ),
                    suiteFilePath, started,
                )
            }
            scriptText = Files.readString(suitePath)
            libRootPath = libRoot?.let { allowedFilePolicy.resolveAllowedDirectory(it, "Suite library root") }
        } catch (error: HexanaAnalysisException) {
            return failureResult(
                HexanaScriptStatus.INVALID_INPUT,
                HexanaScriptDiagnostic(
                    code = error.code.name,
                    message = error.message,
                    exceptionClass = error::class.java.name,
                ),
                suiteFilePath, started,
            )
        } catch (error: Exception) {
            return internalError(suiteFilePath, error, started)
        }

        val console = SuiteConsole(null)
        val contextRef = AtomicReference<Context?>()
        val executor = Executors.newSingleThreadExecutor(suiteThreadFactory)
        val future = executor.submit(Callable {
            executeCollect(suitePath, scriptText, libRootPath, console, contextRef, started)
        })
        return try {
            future.get(limits.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            contextRef.get()?.close(true)
            future.cancel(true)
            failureResult(
                HexanaScriptStatus.TIMEOUT,
                HexanaScriptDiagnostic(
                    code = "TIMEOUT",
                    message = "Suite timed out after ${limits.timeoutMillis} ms",
                ),
                suitePath.toString(), started, console.entries(),
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            contextRef.get()?.close(true)
            future.cancel(true)
            failureResult(
                HexanaScriptStatus.INTERNAL_ERROR,
                HexanaScriptDiagnostic(code = "INTERRUPTED", message = "Suite execution was interrupted"),
                suitePath.toString(), started, console.entries(),
            )
        } catch (error: ExecutionException) {
            when (val cause = error.cause) {
                is VirtualMachineError -> throw cause
                is LinkageError -> throw cause
                else -> internalError(suitePath.toString(), cause ?: error, started, console.entries())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    // -------------------------------------------------------------------------
    // Execution on the suite executor thread
    // -------------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun executeSuite(
        suitePath: Path,
        scriptText: String,
        artifacts: Map<String, String>,
        libRootPath: Path?,
        seedBytes: ByteArray,
        testTimeoutMillis: Long,
        testPathFilter: Set<List<String>>?,
        console: SuiteConsole,
        contextRef: AtomicReference<Context?>,
        started: Long,
    ): HexanaScriptResult {
        val previousClassLoader = Thread.currentThread().contextClassLoader
        var artifactBinder: SuiteArtifactBinder? = null
        try {
            Thread.currentThread().contextClassLoader = HexanaSuiteRunner::class.java.classLoader
            val suiteDir = suitePath.parent.toRealPath()
            val roots = listOfNotNull(suiteDir, libRootPath)
            val fileSystem = SuiteModuleFileSystem(roots, libRootPath, cwd = suiteDir)
            val context = buildSuiteContext(fileSystem)
            contextRef.set(context)
            context.use { ctx ->
                val registry = SuiteRegistry(rootName = suitePath.fileName.toString())
                val timers = SuiteTimers()
                val drbg = Sha256CounterDrbg(seedBytes)
                val binder = SuiteArtifactBinder(artifacts, limits, allowedFilePolicy)
                artifactBinder = binder

                val bindings = ctx.getBindings("js")
                bindings.putMember(SCRIPT_CONSOLE_GLOBAL, console.proxy())
                bindings.putMember(SCRIPT_HEXANA_GLOBAL, suiteHexanaProxy(binder))
                registry.bindGlobals(bindings)
                timers.bindGlobals(bindings)

                val preludeFn = ctx.eval(
                    Source.newBuilder("js", SuitePrelude.TEXT, SuitePrelude.SOURCE_NAME).buildLiteral()
                )
                preludeFn.execute(cryptoHostBridge(drbg))

                val moduleSource = Source.newBuilder("js", scriptText, suitePath.toString())
                    .uri(suitePath.toUri())
                    .mimeType(ES_MODULE_MIME_TYPE)
                    .build()
                try {
                    ctx.eval(moduleSource)
                } catch (error: PolyglotException) {
                    return failureResult(
                        if (error.isCancelled) HexanaScriptStatus.TIMEOUT else HexanaScriptStatus.SCRIPT_ERROR,
                        error.toSuiteDiagnostic(),
                        suitePath.toString(), started, console.entries(),
                        artifactDeclarations = binder.declarations(),
                    )
                }
                registry.collecting = false

                val suiteTree = SuiteExecutor(ctx, timers, testTimeoutMillis, testPathFilter).executeRoot(registry.root)
                return HexanaScriptResult(
                    status = HexanaScriptStatus.SUCCESS,
                    output = emptyOutput(),
                    diagnostics = emptyList(),
                    binary = null,
                    sourceName = suitePath.fileName.toString(),
                    sourcePath = suitePath.toString(),
                    durationMillis = elapsedMillis(started),
                    console = console.entries(),
                    suite = suiteTree,
                    artifactDeclarations = binder.declarations(),
                )
            }
        } catch (error: PolyglotException) {
            return failureResult(
                if (error.isCancelled) HexanaScriptStatus.TIMEOUT else HexanaScriptStatus.SCRIPT_ERROR,
                error.toSuiteDiagnostic(),
                suitePath.toString(), started, console.entries(),
                artifactDeclarations = artifactBinder?.declarations().orEmpty(),
            )
        } catch (error: Exception) {
            return internalError(
                suitePath.toString(), error, started, console.entries(),
                artifactDeclarations = artifactBinder?.declarations().orEmpty(),
            )
        } finally {
            contextRef.set(null)
            Thread.currentThread().contextClassLoader = previousClassLoader
        }
    }

    /**
     * Collection-mode execution: same context profile and prelude as [executeSuite],
     * but `hexana.artifact` records declarations and returns an inert stub, and no
     * test is executed after the module evaluates.
     */
    private fun executeCollect(
        suitePath: Path,
        scriptText: String,
        libRootPath: Path?,
        console: SuiteConsole,
        contextRef: AtomicReference<Context?>,
        started: Long,
    ): HexanaScriptResult {
        val previousClassLoader = Thread.currentThread().contextClassLoader
        val declarations = mutableListOf<SuiteArtifactDeclaration>()
        try {
            Thread.currentThread().contextClassLoader = HexanaSuiteRunner::class.java.classLoader
            val suiteDir = suitePath.parent.toRealPath()
            val roots = listOfNotNull(suiteDir, libRootPath)
            val fileSystem = SuiteModuleFileSystem(roots, libRootPath, cwd = suiteDir)
            val context = buildSuiteContext(fileSystem)
            contextRef.set(context)
            context.use { ctx ->
                val registry = SuiteRegistry(rootName = suitePath.fileName.toString())
                val timers = SuiteTimers()
                val drbg = Sha256CounterDrbg(Sha256CounterDrbg.deriveSeed(scriptText, emptyMap()))

                val bindings = ctx.getBindings("js")
                bindings.putMember(SCRIPT_CONSOLE_GLOBAL, console.proxy())
                bindings.putMember(SCRIPT_HEXANA_GLOBAL, collectHexanaProxy(declarations))
                registry.bindGlobals(bindings)
                timers.bindGlobals(bindings)

                val preludeFn = ctx.eval(
                    Source.newBuilder("js", SuitePrelude.TEXT, SuitePrelude.SOURCE_NAME).buildLiteral()
                )
                preludeFn.execute(cryptoHostBridge(drbg))

                val moduleSource = Source.newBuilder("js", scriptText, suitePath.toString())
                    .uri(suitePath.toUri())
                    .mimeType(ES_MODULE_MIME_TYPE)
                    .build()
                try {
                    ctx.eval(moduleSource)
                } catch (error: PolyglotException) {
                    return failureResult(
                        if (error.isCancelled) HexanaScriptStatus.TIMEOUT else HexanaScriptStatus.SCRIPT_ERROR,
                        error.toSuiteDiagnostic(),
                        suitePath.toString(), started, console.entries(),
                        artifactDeclarations = declarations.toList(),
                    )
                }
                registry.collecting = false

                return HexanaScriptResult(
                    status = HexanaScriptStatus.SUCCESS,
                    output = emptyOutput(),
                    diagnostics = emptyList(),
                    binary = null,
                    sourceName = suitePath.fileName.toString(),
                    sourcePath = suitePath.toString(),
                    durationMillis = elapsedMillis(started),
                    console = console.entries(),
                    suite = collectedTree(registry.root),
                    artifactDeclarations = declarations.toList(),
                )
            }
        } catch (error: PolyglotException) {
            return failureResult(
                if (error.isCancelled) HexanaScriptStatus.TIMEOUT else HexanaScriptStatus.SCRIPT_ERROR,
                error.toSuiteDiagnostic(),
                suitePath.toString(), started, console.entries(),
                artifactDeclarations = declarations.toList(),
            )
        } catch (error: Exception) {
            return internalError(
                suitePath.toString(), error, started, console.entries(),
                artifactDeclarations = declarations.toList(),
            )
        } finally {
            contextRef.set(null)
            Thread.currentThread().contextClassLoader = previousClassLoader
        }
    }

    /** Maps the collected registration tree to a result tree: every node SKIP, zero durations. */
    private fun collectedTree(group: SuiteGroupNode): SuiteNodeResult = SuiteNodeResult(
        name = group.name,
        kind = SuiteNodeKind.SUITE,
        status = SuiteNodeStatus.SKIP,
        durationMillis = 0,
        children = group.children.map { child ->
            when (child) {
                is SuiteGroupNode -> collectedTree(child)
                is SuiteTestNode -> SuiteNodeResult(
                    name = child.name,
                    kind = SuiteNodeKind.TEST,
                    status = SuiteNodeStatus.SKIP,
                    durationMillis = 0,
                    sourceFile = child.sourceFile,
                    sourceLine = child.sourceLine,
                )
            }
        },
        sourceFile = group.sourceFile,
        sourceLine = group.sourceLine,
    )

    /**
     * `hexana` global for the declaration-collection mode: `artifact` records the
     * declaration and returns a benign inert stub (every property reads as
     * `undefined`); no mapping lookup, no fail-fast, no file IO.
     */
    private fun collectHexanaProxy(declarations: MutableList<SuiteArtifactDeclaration>): ProxyObject =
        object : ProxyObject {
            private val keys = listOf("artifact", "has", "api")

            override fun getMember(key: String): Any? = when (key) {
                "artifact" -> ProxyExecutable { args ->
                    declarations += parseSuiteArtifactDeclaration(args)
                    inertArtifactStub
                }
                "has" -> ProxyExecutable { args ->
                    args.getOrNull(0)?.takeIf { it.isString }?.asString() == "suite"
                }
                "api" -> 2
                else -> null
            }

            override fun getMemberKeys(): Any = ProxyArray.fromList(keys)
            override fun hasMember(key: String): Boolean = key in keys
            override fun putMember(key: String, value: Value) { /* read-only */ }
        }

    /**
     * Builds the suite-profile GraalJS [Context]: the base hardening plus the
     * custom module [fileSystem], ESM namespace eval, and native text encoding.
     */
    internal fun buildSuiteContext(fileSystem: SuiteModuleFileSystem): Context = Context.newBuilder("js")
        .allowAllAccess(false)
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowIO(IOAccess.newBuilder().fileSystem(fileSystem).build())
        .allowCreateProcess(false)
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .allowExperimentalOptions(true)
        .option("engine.WarnInterpreterOnly", "false")
        .option("js.esm-eval-returns-exports", "true")
        .option("js.text-encoding", "true")
        .build()

    // -------------------------------------------------------------------------
    // hexana global (suite variant)
    // -------------------------------------------------------------------------

    private fun suiteHexanaProxy(artifactBinder: SuiteArtifactBinder): ProxyObject = object : ProxyObject {
        private val keys = listOf("artifact", "has", "api")

        override fun getMember(key: String): Any? = when (key) {
            "artifact" -> ProxyExecutable { args -> artifactBinder.resolve(args) }
            "has" -> ProxyExecutable { args ->
                args.getOrNull(0)?.takeIf { it.isString }?.asString() == "suite"
            }
            "api" -> 2
            else -> null
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(keys)
        override fun hasMember(key: String): Boolean = key in keys
        override fun putMember(key: String, value: Value) { /* read-only */ }
    }

    // -------------------------------------------------------------------------
    // crypto host bridge
    // -------------------------------------------------------------------------

    private fun cryptoHostBridge(drbg: Sha256CounterDrbg): ProxyObject = object : ProxyObject {
        private val keys = listOf("randomBytes", "digest")

        override fun getMember(key: String): Any? = when (key) {
            "randomBytes" -> ProxyExecutable { args ->
                val count = args.getOrNull(0)?.takeIf { it.isNumber }?.asInt()
                    ?: throw IllegalArgumentException("randomBytes: count must be a number")
                require(count in 0..MAX_RANDOM_BYTES) { "randomBytes: count out of range: $count" }
                ProxyArray.fromList(drbg.nextBytes(count).map { it.toInt() and 0xFF })
            }
            "digest" -> ProxyExecutable { args ->
                val algorithm = args.getOrNull(0)?.takeIf { it.isString }?.asString()
                if (algorithm !in SUPPORTED_DIGESTS) {
                    throw IllegalArgumentException(
                        "crypto.subtle.digest: unsupported algorithm \"$algorithm\"; " +
                            "supported: ${SUPPORTED_DIGESTS.joinToString(", ")}"
                    )
                }
                val data = args.getOrNull(1)
                    ?: throw IllegalArgumentException("crypto.subtle.digest: data is required")
                val bytes = ByteArray(data.arraySize.toInt()) { i ->
                    data.getArrayElement(i.toLong()).asInt().toByte()
                }
                val digest = MessageDigest.getInstance(algorithm).digest(bytes)
                ProxyArray.fromList(digest.map { it.toInt() and 0xFF })
            }
            else -> null
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(keys)
        override fun hasMember(key: String): Boolean = key in keys
        override fun putMember(key: String, value: Value) { /* read-only */ }
    }

    // -------------------------------------------------------------------------
    // Result helpers
    // -------------------------------------------------------------------------

    private fun failureResult(
        status: HexanaScriptStatus,
        diagnostic: HexanaScriptDiagnostic,
        sourcePath: String,
        started: Long,
        console: List<ScriptConsoleEntry> = emptyList(),
        artifactDeclarations: List<SuiteArtifactDeclaration> = emptyList(),
    ): HexanaScriptResult = HexanaScriptResult(
        status = status,
        output = emptyOutput(),
        diagnostics = listOf(diagnostic),
        binary = null,
        sourceName = Path.of(sourcePath).fileName?.toString() ?: sourcePath,
        sourcePath = sourcePath,
        durationMillis = elapsedMillis(started),
        console = console,
        artifactDeclarations = artifactDeclarations,
    )

    private fun internalError(
        sourcePath: String,
        error: Throwable,
        started: Long,
        console: List<ScriptConsoleEntry> = emptyList(),
        artifactDeclarations: List<SuiteArtifactDeclaration> = emptyList(),
    ): HexanaScriptResult = failureResult(
        HexanaScriptStatus.INTERNAL_ERROR,
        HexanaScriptDiagnostic(
            code = "INTERNAL_ERROR",
            message = error.message ?: error::class.java.name,
            exceptionClass = error::class.java.name,
        ),
        sourcePath, started, console, artifactDeclarations,
    )

    private fun emptyOutput(): HexanaScriptOutput = HexanaScriptOutput(
        lines = emptyList(),
        truncated = false,
        emittedBytes = 0,
        maxLines = limits.maxOutputLines,
        maxBytes = limits.maxOutputBytes,
    )

    private fun PolyglotException.toSuiteDiagnostic(): HexanaScriptDiagnostic {
        val location = sourceLocation
        return HexanaScriptDiagnostic(
            code = when {
                isCancelled -> "TIMEOUT"
                isSyntaxError -> "SYNTAX_ERROR"
                isSandboxDeniedMessage(message) -> "SANDBOX_DENIED"
                else -> "RUNTIME_ERROR"
            },
            message = message ?: "Script error",
            exceptionClass = this::class.java.name,
            sourceName = location?.source?.name,
            line = location?.startLine?.takeIf { it > 0 },
            column = location?.startColumn?.takeIf { it > 0 },
        )
    }

    /**
     * Mirrors HexanaScriptRunner.isSandboxDeniedMessage so a sandbox violation
     * in a suite run reports SANDBOX_DENIED, same as the script profile.
     */
    private fun isSandboxDeniedMessage(message: String?): Boolean {
        val value = message ?: return false
        return value.contains("host class", ignoreCase = true) ||
            value.contains("Java is not defined", ignoreCase = true) ||
            value.contains("Java.type", ignoreCase = true)
    }

    private fun elapsedMillis(started: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    companion object {
        const val DEFAULT_TEST_TIMEOUT_MILLIS: Long = 5_000
        private const val ES_MODULE_MIME_TYPE = "application/javascript+module"
        private const val MAX_RANDOM_BYTES = 1 shl 16
        private val SUPPORTED_DIGESTS = listOf("SHA-256", "SHA-384", "SHA-512")

        private val suiteThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "hexana-suite-runner").apply { isDaemon = true }
        }

        /**
         * Benign inert artifact handle for the declaration-collection mode:
         * every property reads as `undefined`, nothing is executable, writes
         * are ignored. No file IO can be reached through it.
         */
        private val inertArtifactStub: ProxyObject = object : ProxyObject {
            override fun getMember(key: String): Any? = null
            override fun getMemberKeys(): Any = ProxyArray.fromList(emptyList<Any>())
            override fun hasMember(key: String): Boolean = false
            override fun putMember(key: String, value: Value) { /* inert */ }
        }
    }
}

// -----------------------------------------------------------------------------
// Console
// -----------------------------------------------------------------------------

/** Collects console entries from the suite context; single-threaded access. */
internal class SuiteConsole(private val onEntry: ((ScriptConsoleEntry) -> Unit)?) {

    private val collected = java.util.Collections.synchronizedList(mutableListOf<ScriptConsoleEntry>())

    fun entries(): List<ScriptConsoleEntry> = collected.toList()

    fun proxy(): ProxyObject = object : ProxyObject {
        private val keys = listOf("log", "error", "debug")

        override fun getMember(key: String): Any? = when (key) {
            "log" -> executable(ConsoleSeverity.LOG)
            "error" -> executable(ConsoleSeverity.ERROR)
            "debug" -> executable(ConsoleSeverity.DEBUG)
            else -> null
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(keys)
        override fun hasMember(key: String): Boolean = key in keys
        override fun putMember(key: String, value: Value) { /* read-only */ }
    }

    private fun executable(severity: ConsoleSeverity): ProxyExecutable = ProxyExecutable { args ->
        val text = args.joinToString(" ") { v -> formatConsoleArg(v) }
        val entry = ScriptConsoleEntry(severity, text)
        collected += entry
        onEntry?.invoke(entry)
        null
    }
}

// -----------------------------------------------------------------------------
// Call-site stack parser
// -----------------------------------------------------------------------------

/**
 * Result of parsing one GraalJS `Error.stack` frame for navigation.
 *
 * Observed GraalJS 25 format (two variants):
 *   Anonymous/top-level: `    at /abs/path/to/file.mjs:line:col`
 *   Named function:      `    at funcName (/abs/path/to/file.mjs:line:col)`
 *
 * The first line of the stack string is `Error: <message>` (skipped).
 * Prelude-internal frames have source name `hexana:suite-prelude`; they are skipped.
 * Returns null when the string is empty or unparseable.
 */
internal fun parseGraalJSCallSite(stack: String?): Pair<String, Int>? {
    if (stack.isNullOrBlank()) return null
    // Each frame line starts with "    at " (4 spaces + "at ")
    val framePrefix = "    at "
    for (line in stack.lineSequence().drop(1)) {
        if (!line.startsWith(framePrefix)) continue
        val body = line.removePrefix(framePrefix)
        // Skip prelude-internal frames
        if (body.startsWith(SuitePrelude.SOURCE_NAME) || body.contains("(${SuitePrelude.SOURCE_NAME}")) continue

        // Named: "funcName (/path:line:col)" or "funcName (path:line:col)"
        // Anonymous: "/path:line:col"
        val location = if (body.endsWith(")") && body.contains("(")) {
            body.substringAfterLast("(").removeSuffix(")")
        } else {
            body
        }
        // location is "path:line:col"; line and col are decimal integers
        val lastColon = location.lastIndexOf(':')
        if (lastColon < 0) continue
        val beforeCol = location.substring(0, lastColon)
        val secondLastColon = beforeCol.lastIndexOf(':')
        if (secondLastColon < 0) continue
        val filePath = beforeCol.substring(0, secondLastColon)
        val lineStr = beforeCol.substring(secondLastColon + 1)
        val lineNum = lineStr.toIntOrNull() ?: continue
        if (filePath.isBlank()) continue
        return filePath to lineNum
    }
    return null
}

// -----------------------------------------------------------------------------
// Registration (collection pass)
// -----------------------------------------------------------------------------

internal sealed interface SuiteChild

internal class SuiteGroupNode(
    val name: String,
    val parent: SuiteGroupNode?,
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
) : SuiteChild {
    val beforeEach = mutableListOf<Value>()
    val afterEach = mutableListOf<Value>()
    val children = mutableListOf<SuiteChild>()
}

internal class SuiteTestNode(
    val name: String,
    val body: Value,
    val timeoutMillis: Long?,
    val owner: SuiteGroupNode,
    val skipped: Boolean = false,
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
) : SuiteChild

/**
 * Collects `describe`/`test`/`beforeEach`/`afterEach` registrations during
 * module evaluation. `describe` bodies run synchronously at registration time;
 * test bodies run later, after the module finished evaluating.
 */
internal class SuiteRegistry(rootName: String) {

    val root = SuiteGroupNode(rootName, parent = null)
    var collecting = true

    private var current: SuiteGroupNode = root

    fun bindGlobals(bindings: Value) {
        bindings.putMember("describe", describeExecutable)
        // Expose `test` as a callable proxy so that `test.skip` is also accessible.
        bindings.putMember("test", testProxy())
        bindings.putMember("beforeEach", hookExecutable("beforeEach") { it.beforeEach })
        bindings.putMember("afterEach", hookExecutable("afterEach") { it.afterEach })
    }

    private fun testProxy(): Any = object : ProxyObject, ProxyExecutable {
        private val keys = listOf("skip")

        // Called when guest calls `test(name, fn)` directly.
        override fun execute(vararg args: Value): Any? = testExecutable.execute(*args)

        // Called when guest reads `test.skip`.
        override fun getMember(key: String): Any? = when (key) {
            "skip" -> testSkipExecutable
            else -> null
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(keys)
        override fun hasMember(key: String): Boolean = key in keys
        override fun putMember(key: String, value: Value) { /* read-only */ }
    }

    private fun requireCollecting(what: String) {
        check(collecting) { "$what() can only be called while the suite module is loading, not from inside a test" }
    }

    private val describeExecutable = ProxyExecutable { args ->
        requireCollecting("describe")
        val name = args.getOrNull(0)?.takeIf { it.isString }?.asString()
            ?: throw IllegalArgumentException("describe(name, fn): name must be a string")
        val fn = args.getOrNull(1)?.takeIf { it.canExecute() }
            ?: throw IllegalArgumentException("describe(name, fn): fn must be a function")
        // args[2] is the captured stack string injected by the prelude wrapper (optional)
        val callSite = parseGraalJSCallSite(args.getOrNull(2)?.takeIf { it.isString }?.asString())
        val group = SuiteGroupNode(name, current, sourceFile = callSite?.first, sourceLine = callSite?.second)
        current.children += group
        val previous = current
        current = group
        try {
            fn.execute()
        } finally {
            current = previous
        }
        null
    }

    private val testExecutable = ProxyExecutable { args ->
        requireCollecting("test")
        val name = args.getOrNull(0)?.takeIf { it.isString }?.asString()
            ?: throw IllegalArgumentException("test(name, [options,] fn): name must be a string")
        val second = args.getOrNull(1)
        val third = args.getOrNull(2)
        val fourth = args.getOrNull(3)
        val body: Value
        var timeout: Long? = null
        val stackStr: String?
        if (second != null && second.canExecute() && (third == null || third.isString)) {
            // test(name, fn) or test(name, fn, capturedStack)
            body = second
            stackStr = third?.takeIf { it.isString }?.asString()
        } else {
            val options = second?.takeIf { it.hasMembers() }
                ?: throw IllegalArgumentException("test(name, [options,] fn): expected an options object or a function")
            timeout = options.takeIf { it.hasMember("timeout") }?.getMember("timeout")
                ?.takeIf { it.isNumber }?.asLong()
            body = third?.takeIf { it.canExecute() }
                ?: throw IllegalArgumentException("test(name, options, fn): fn must be a function")
            // fourth is the captured stack string (optional)
            stackStr = fourth?.takeIf { it.isString }?.asString()
        }
        val callSite = parseGraalJSCallSite(stackStr)
        current.children += SuiteTestNode(name, body, timeout, current,
            sourceFile = callSite?.first, sourceLine = callSite?.second)
        null
    }

    /** `test.skip(name, [options,] fn)`: registers a test that is never executed. */
    private val testSkipExecutable = ProxyExecutable { args ->
        requireCollecting("test.skip")
        val name = args.getOrNull(0)?.takeIf { it.isString }?.asString()
            ?: throw IllegalArgumentException("test.skip(name, [options,] fn): name must be a string")
        val second = args.getOrNull(1)
        val third = args.getOrNull(2)
        val fourth = args.getOrNull(3)
        val body: Value
        val stackStr: String?
        if (second != null && second.canExecute() && (third == null || third.isString)) {
            body = second
            stackStr = third?.takeIf { it.isString }?.asString()
        } else {
            val options = second?.takeIf { it.hasMembers() }
                ?: throw IllegalArgumentException("test.skip(name, [options,] fn): expected an options object or a function")
            body = third?.takeIf { it.canExecute() }
                ?: throw IllegalArgumentException("test.skip(name, options, fn): fn must be a function")
            stackStr = fourth?.takeIf { it.isString }?.asString()
        }
        val callSite = parseGraalJSCallSite(stackStr)
        current.children += SuiteTestNode(name, body, timeoutMillis = null, owner = current, skipped = true,
            sourceFile = callSite?.first, sourceLine = callSite?.second)
        null
    }

    private fun hookExecutable(what: String, target: (SuiteGroupNode) -> MutableList<Value>): ProxyExecutable =
        ProxyExecutable { args ->
            requireCollecting(what)
            val fn = args.getOrNull(0)?.takeIf { it.canExecute() }
                ?: throw IllegalArgumentException("$what(fn): fn must be a function")
            target(current) += fn
            null
        }
}

// -----------------------------------------------------------------------------
// Timers
// -----------------------------------------------------------------------------

/**
 * Host-side timer registry backing the `setTimeout`/`clearTimeout` shim. Never
 * fires callbacks on its own: the drain loop on the suite executor thread asks
 * for the next due entry and executes it, so all context access stays on one
 * thread.
 */
internal class SuiteTimers {

    internal class Entry(
        val id: Int,
        val callback: Value,
        val args: Array<Value>,
        val dueAtNanos: Long,
    )

    private val entries = mutableListOf<Entry>()
    private var nextId = 1

    fun bindGlobals(bindings: Value) {
        bindings.putMember("setTimeout", ProxyExecutable { args ->
            val callback = args.getOrNull(0)?.takeIf { it.canExecute() }
                ?: throw IllegalArgumentException("setTimeout(fn, delay): fn must be a function")
            val delayMillis = args.getOrNull(1)?.takeIf { it.isNumber }?.asDouble()?.toLong() ?: 0L
            val extraArgs = args.drop(2).toTypedArray()
            val entry = Entry(
                id = nextId++,
                callback = callback,
                args = extraArgs,
                dueAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxOf(0L, delayMillis)),
            )
            entries += entry
            entry.id
        })
        bindings.putMember("clearTimeout", ProxyExecutable { args ->
            val id = args.getOrNull(0)?.takeIf { it.isNumber }?.asInt()
            if (id != null) entries.removeAll { it.id == id }
            null
        })
    }

    /** The entry that is due next (earliest due time, then registration order). */
    fun nextDue(): Entry? = entries.minWithOrNull(compareBy({ it.dueAtNanos }, { it.id }))

    /** Removes [entry] and runs its callback on the calling (executor) thread. */
    fun fire(entry: Entry) {
        entries.remove(entry)
        entry.callback.execute(*entry.args)
    }

    fun clearAll() {
        entries.clear()
    }
}

// -----------------------------------------------------------------------------
// Test execution + drain loop
// -----------------------------------------------------------------------------

/** A test failure: an assertion/throw, a rejection, or a per-test timeout. */
private class SuiteTestFailure(
    override val message: String,
    val stackLine: String?,
) : Exception(message)

/**
 * Executes the collected suite tree. All calls happen on the suite executor
 * thread that owns the GraalJS context.
 *
 * @param testPathFilter when non-null, only tests whose full name path is in this
 *   set are executed; others appear as SKIP with duration 0. Each element is a
 *   list of describe names + test name (root group name excluded).
 */
internal class SuiteExecutor(
    private val context: Context,
    private val timers: SuiteTimers,
    private val defaultTimeoutMillis: Long,
    private val testPathFilter: Set<List<String>>? = null,
) {

    fun executeRoot(root: SuiteGroupNode): SuiteNodeResult = executeGroup(root, emptyList())

    private fun executeGroup(group: SuiteGroupNode, path: List<String>): SuiteNodeResult {
        val started = System.nanoTime()
        val children = group.children.map { child ->
            when (child) {
                is SuiteGroupNode -> executeGroup(child, path + child.name)
                is SuiteTestNode -> {
                    val testPath = path + child.name
                    val filtered = testPathFilter != null && testPath !in testPathFilter
                    when {
                        child.skipped || filtered -> skipResult(child)
                        else -> executeTest(child)
                    }
                }
            }
        }
        val status = if (children.any { it.status == SuiteNodeStatus.FAIL }) {
            SuiteNodeStatus.FAIL
        } else {
            SuiteNodeStatus.PASS
        }
        return SuiteNodeResult(
            name = group.name,
            kind = SuiteNodeKind.SUITE,
            status = status,
            durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            children = children,
            sourceFile = group.sourceFile,
            sourceLine = group.sourceLine,
        )
    }

    private fun skipResult(test: SuiteTestNode): SuiteNodeResult = SuiteNodeResult(
        name = test.name,
        kind = SuiteNodeKind.TEST,
        status = SuiteNodeStatus.SKIP,
        durationMillis = 0,
        sourceFile = test.sourceFile,
        sourceLine = test.sourceLine,
    )

    private fun executeTest(test: SuiteTestNode): SuiteNodeResult {
        val started = System.nanoTime()
        val timeoutMillis = test.timeoutMillis ?: defaultTimeoutMillis
        val deadlineNanos = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val lineage = generateSequence(test.owner) { it.parent }.toList().asReversed()
        val beforeChain = lineage.flatMap { it.beforeEach }
        val afterChain = lineage.asReversed().flatMap { it.afterEach }

        timers.clearAll()
        var failure: SuiteTestFailure? = null
        try {
            for (hook in beforeChain) runCallable(hook, deadlineNanos, timeoutMillis)
            runCallable(test.body, deadlineNanos, timeoutMillis)
        } catch (error: SuiteTestFailure) {
            failure = error
        }
        for (hook in afterChain) {
            try {
                runCallable(hook, deadlineNanos, timeoutMillis)
            } catch (error: SuiteTestFailure) {
                if (failure == null) failure = error
            }
        }
        timers.clearAll()

        return SuiteNodeResult(
            name = test.name,
            kind = SuiteNodeKind.TEST,
            status = if (failure == null) SuiteNodeStatus.PASS else SuiteNodeStatus.FAIL,
            durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            failureMessage = failure?.message,
            failureStackLine = failure?.stackLine,
            sourceFile = test.sourceFile,
            sourceLine = test.sourceLine,
        )
    }

    /** Invokes [fn] and drains until its result settles or the test deadline hits. */
    private fun runCallable(fn: Value, deadlineNanos: Long, timeoutMillis: Long) {
        val result = try {
            fn.execute()
        } catch (error: PolyglotException) {
            if (error.isCancelled) throw error
            throw error.toFailure()
        }
        drain(result, deadlineNanos, timeoutMillis)
    }

    /**
     * The drain loop. Pumps the microtask queue by re-entering the engine with a
     * trivial eval, and fires host timers in due-time order on this thread.
     *
     * - Thenable [result]: loop until it settles. Quiescence (no microtasks left,
     *   no timer due before the deadline) with an unsettled promise means nothing
     *   can ever settle it (reported as the per-test timeout).
     * - Non-thenable [result]: run timers that come due before the deadline until
     *   none are pending, then return.
     */
    private fun drain(result: Value?, deadlineNanos: Long, timeoutMillis: Long) {
        val settle = if (result != null && isThenable(result)) SettleState().also { state ->
            result.invokeMember(
                "then",
                ProxyExecutable { state.settled = true; null },
                ProxyExecutable { args ->
                    state.settled = true
                    state.rejected = true
                    state.reason = args.getOrNull(0)
                    null
                },
            )
        } else {
            null
        }

        while (true) {
            pumpMicrotasks()
            if (settle != null && settle.settled) break
            val next = timers.nextDue()
            if (next == null) {
                if (settle == null) return
                throw timeoutFailure(timeoutMillis)
            }
            val now = System.nanoTime()
            if (now >= deadlineNanos || next.dueAtNanos > deadlineNanos) {
                if (settle == null) return
                throw timeoutFailure(timeoutMillis)
            }
            if (next.dueAtNanos <= now) {
                try {
                    timers.fire(next)
                } catch (error: PolyglotException) {
                    if (error.isCancelled) throw error
                    throw error.toFailure()
                }
            } else {
                Thread.sleep(1) // timer not yet due; yield to reduce CPU spin
            }
        }

        if (settle != null && settle.rejected) throw rejectionFailure(settle.reason)
    }

    private fun pumpMicrotasks() {
        context.eval(PUMP_SOURCE)
    }

    private fun isThenable(value: Value): Boolean = try {
        !value.isNull && value.hasMembers() && value.hasMember("then") && value.canInvokeMember("then")
    } catch (_: Exception) {
        false
    }

    private fun timeoutFailure(timeoutMillis: Long): SuiteTestFailure =
        SuiteTestFailure("Test timed out after $timeoutMillis ms", stackLine = null)

    private fun PolyglotException.toFailure(): SuiteTestFailure = SuiteTestFailure(
        message = message ?: "Test failed",
        stackLine = polyglotStackTrace.firstOrNull { it.isGuestFrame }?.toString(),
    )

    private fun rejectionFailure(reason: Value?): SuiteTestFailure {
        if (reason == null || reason.isNull) {
            return SuiteTestFailure("Test promise rejected", stackLine = null)
        }
        val message = when {
            reason.isString -> reason.asString()
            reason.hasMembers() && reason.hasMember("message") -> {
                val name = reason.takeIf { it.hasMember("name") }?.getMember("name")
                    ?.takeIf { it.isString }?.asString() ?: "Error"
                val text = reason.getMember("message")?.takeIf { it.isString }?.asString() ?: ""
                "$name: $text"
            }
            else -> reason.toString()
        }
        val stackLine = reason.takeIf { it.hasMembers() && it.hasMember("stack") }
            ?.getMember("stack")?.takeIf { it.isString }?.asString()
            ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith("at ") }
        return SuiteTestFailure(message, stackLine)
    }

    private class SettleState {
        var settled = false
        var rejected = false
        var reason: Value? = null
    }

    private companion object {
        /**
         * The no-op eval re-entry drains the GraalJS microtask queue (Promise
         * continuations, queueMicrotask) before returning control;
         * undocumented-but-stable engine behavior, also used by the GraalJS
         * test harness; "(0)" is a side-effect-free expression.
         */
        val PUMP_SOURCE: Source = Source.newBuilder("js", "(0)", "hexana:suite-pump").buildLiteral()
    }
}

// -----------------------------------------------------------------------------
// Artifacts
// -----------------------------------------------------------------------------

/**
 * Parses one `hexana.artifact(role, options)` call into a pure declaration
 * record, without touching the role mapping or any file. Shared by the binder
 * (which records, then resolves) and by the declaration-collection mode (which
 * records and returns an inert stub).
 *
 * @throws IllegalArgumentException when the role argument is not a string
 */
internal fun parseSuiteArtifactDeclaration(args: Array<Value>): SuiteArtifactDeclaration {
    val role = args.getOrNull(0)?.takeIf { it.isString }?.asString()
        ?: throw IllegalArgumentException("hexana.artifact(role, options): role must be a string")
    val options = args.getOrNull(1)?.takeIf { !it.isNull && it.hasMembers() }
    val optional = options?.takeIf { it.hasMember("optional") }?.getMember("optional")
        ?.takeIf { it.isBoolean }?.asBoolean() ?: false
    val formats = buildList {
        options?.takeIf { it.hasMember("formats") }?.getMember("formats")
            ?.takeIf { it.hasArrayElements() }
            ?.let { array -> (0 until array.arraySize).forEach { add(array.getArrayElement(it).asString()) } }
        options?.takeIf { it.hasMember("type") }?.getMember("type")
            ?.takeIf { it.isString }?.asString()?.let { add(it) }
    }
    return SuiteArtifactDeclaration(role = role, formats = formats, optional = optional)
}

/**
 * Resolves `hexana.artifact(role, options)` calls against the host's role→path
 * mapping. Loads each mapped file once (through [BinaryInputLoader], validating
 * each path against [basePolicy] before loading) and returns the same guest shape
 * as the `binary` global.
 *
 * The loader uses the base policy directly, so an artifact path outside the
 * configured allowed roots is rejected at load time (the policy's error names
 * the blocked path). There is no pre-widening: that would bypass the root check
 * and allow --artifact to silently read files outside the sandbox.
 */
internal class SuiteArtifactBinder(
    private val mapping: Map<String, String>,
    limits: HexanaScriptLimits,
    basePolicy: AllowedFilePolicy,
) {

    private val loader = BinaryInputLoader(
        maxInlineInputBytes = limits.maxInlineBinaryBytes,
        maxFileInputBytes = limits.maxFileBinaryBytes,
        allowedFilePolicy = basePolicy,
    )

    /** role → guest binary handle, so repeated `hexana.artifact` calls are stable. */
    private val cache = mutableMapOf<String, Any>()

    /** Every declaration encountered, in call order, recorded before any resolution. */
    private val declarationsList = mutableListOf<SuiteArtifactDeclaration>()

    fun declarations(): List<SuiteArtifactDeclaration> = declarationsList.toList()

    fun resolve(args: Array<Value>): Any? {
        // Record the declaration BEFORE any resolution or failure, so a run that
        // dies on an unmapped role still reports what it saw up to that point.
        val declaration = parseSuiteArtifactDeclaration(args)
        declarationsList += declaration
        val role = declaration.role

        val rawPath = mapping[role]
        if (rawPath == null) {
            if (declaration.optional) return null
            throw IllegalArgumentException(
                "Required artifact role \"$role\" is not mapped. Pass --artifact $role=<path> to map it."
            )
        }
        cache[role]?.let { return it }

        val loaded = try {
            loader.load(BinaryInput.FilePath(rawPath))
        } catch (error: HexanaAnalysisException) {
            throw IllegalArgumentException("Artifact \"$role\" ($rawPath): ${error.message}", error)
        }
        val formatId = binaryFormatId(loaded.provider)
        val allowedFormats = declaration.formats
        if (allowedFormats.isNotEmpty() && formatId !in allowedFormats) {
            throw IllegalArgumentException(
                "Artifact \"$role\" ($rawPath): format \"$formatId\" does not match " +
                    "the declared formats ${allowedFormats.joinToString(", ", "[", "]")}"
            )
        }

        val fileName = loaded.resolvedPath?.let { Path.of(it).fileName?.toString() }
        val guest = ScriptValueGraalProxy.toGuest(
            scriptBinaryApi(loaded.provider, fileName, loaded.resolvedPath)
        ) ?: throw IllegalStateException("Artifact \"$role\": failed to build the binary handle")
        cache[role] = guest
        return guest
    }
}
