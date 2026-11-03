package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.BinaryInputLoader
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.EmptyScriptInvocation
import org.jetbrains.hexana.shared.scripting.FlagType
import org.jetbrains.hexana.shared.scripting.SCRIPT_BINARY_GLOBAL
import org.jetbrains.hexana.shared.scripting.ScriptInvocation
import org.jetbrains.hexana.shared.scripting.ScriptOpenPolicy
import org.jetbrains.hexana.shared.scripting.ScriptOutput
import org.jetbrains.hexana.shared.scripting.ScriptValue
import org.jetbrains.hexana.shared.scripting.SCRIPT_CONSOLE_GLOBAL
import org.jetbrains.hexana.shared.scripting.SCRIPT_HEXANA_GLOBAL
import org.jetbrains.hexana.shared.scripting.scriptBinaryApi
import org.jetbrains.hexana.shared.scripting.scriptGlobals
import org.jetbrains.hexana.shared.scripting.toInfo
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class HexanaScriptRunner private constructor(
    private val limits: HexanaScriptLimits = HexanaScriptLimits(),
    private val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
    private val beforeExecuteInContext: () -> Unit = {},
) {
    constructor(
        limits: HexanaScriptLimits = HexanaScriptLimits(),
        allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
    ) : this(limits, allowedFilePolicy, {})

    internal constructor(beforeExecuteInContext: () -> Unit) : this(
        HexanaScriptLimits(),
        AllowedFilePolicy.allowAll,
        beforeExecuteInContext,
    )

    /**
     * Runs [source] over [binaryProvider].
     *
     * @param fileName file name of the binary, surfaced to the script as
     *   `binary.name` and used to resolve file-name-dependent tabs
     * @param filePath path of the binary on disk, surfaced as `binary.path` and
     *   `binary.dir`. Null when the binary has no file behind it — a ZIP entry or
     *   a module opened from a table — which is what the script then reads.
     * @param onLine invoked for every accepted output line as it is emitted.
     *   Interactive hosts use this to stream output into a tab while the script
     *   is still running; the collected lines are returned regardless.
     */
    fun run(
        source: HexanaScriptSource,
        binaryProvider: BinaryProvider,
        binaryIdentity: BinaryModuleIdentityResult? = null,
        fileName: String? = null,
        filePath: String? = null,
        onLine: ((String) -> Unit)? = null,
        invocation: ScriptInvocation = EmptyScriptInvocation,
    ): HexanaScriptResult {
        val started = System.nanoTime()
        val loadedScript = when (val script = loadScript(source)) {
            is ScriptLoadResult.Loaded -> script.script
            is ScriptLoadResult.Failed -> {
                return script.result.copy(
                    binary = binaryIdentity,
                    durationMillis = elapsedMillis(started),
                )
            }
        }

        return runLoadedScript(
            script = loadedScript,
            binaryProvider = binaryProvider,
            binaryIdentity = binaryIdentity,
            fileName = fileName,
            filePath = filePath,
            onLine = onLine,
            started = started,
            invocation = invocation,
        )
    }

    fun run(
        input: BinaryInput,
        source: HexanaScriptSource,
        invocation: ScriptInvocation = EmptyScriptInvocation,
    ): HexanaScriptResult {
        val started = System.nanoTime()
        val loadedBinary = try {
            BinaryInputLoader(
                maxInlineInputBytes = limits.maxInlineBinaryBytes,
                maxFileInputBytes = limits.maxFileBinaryBytes,
                allowedFilePolicy = allowedFilePolicy,
            ).load(input)
        } catch (error: HexanaAnalysisException) {
            return invalidInputResult(
                sourceName = source.displayName(),
                sourcePath = (source as? HexanaScriptSource.FilePath)?.path,
                diagnostic = error.toDiagnostic(),
                durationMillis = elapsedMillis(started),
            )
        } catch (error: Exception) {
            return internalErrorResult(
                sourceName = source.displayName(),
                sourcePath = (source as? HexanaScriptSource.FilePath)?.path,
                error = error,
                durationMillis = elapsedMillis(started),
            )
        }

        return run(
            source = source,
            binaryProvider = loadedBinary.provider,
            binaryIdentity = loadedBinary.identity,
            // The loader knows the input path, so `binary.name`/`path`/`dir` work
            // here too and do not read as null just because the caller was the
            // CLI. The resolved path is the one worth reporting: a script that
            // joins `binary.dir` with a sibling name must get somewhere real even
            // when the command line said `./app.wasm`.
            filePath = loadedBinary.resolvedPath,
            invocation = invocation,
        )
    }

    private fun runLoadedScript(
        script: LoadedScript,
        binaryProvider: BinaryProvider,
        binaryIdentity: BinaryModuleIdentityResult?,
        fileName: String?,
        filePath: String?,
        onLine: ((String) -> Unit)?,
        started: Long,
        invocation: ScriptInvocation = EmptyScriptInvocation,
    ): HexanaScriptResult {
        val output = ScriptOutputCollector(limits.maxOutputLines, limits.maxOutputBytes, onLine)
        val contextRef = AtomicReference<Context?>()
        val executor = Executors.newSingleThreadExecutor(scriptThreadFactory)
        val future = executor.submit(Callable {
            beforeExecuteInContext()
            executeInContext(
                script, binaryProvider, binaryIdentity, fileName, filePath, output, contextRef, started, invocation,
            )
        })

        return try {
            future.get(limits.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            cancelRunningScript(contextRef, future)
            timeoutResult(script, binaryIdentity, output, started)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelRunningScript(contextRef, future)
            interruptedResult(script, binaryIdentity, output, started)
        } catch (error: ExecutionException) {
            rethrowFatalCause(error.cause)
            internalErrorResult(
                sourceName = script.name,
                sourcePath = script.path,
                binaryIdentity = binaryIdentity,
                output = output.snapshot(),
                error = error.cause ?: error,
                durationMillis = elapsedMillis(started),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun cancelRunningScript(
        contextRef: AtomicReference<Context?>,
        future: java.util.concurrent.Future<HexanaScriptResult>,
    ) {
        contextRef.get()?.close(true)
        future.cancel(true)
    }

    private fun timeoutResult(
        script: LoadedScript,
        binaryIdentity: BinaryModuleIdentityResult?,
        output: ScriptOutputCollector,
        started: Long,
    ): HexanaScriptResult =
        HexanaScriptResult(
            status = HexanaScriptStatus.TIMEOUT,
            output = output.snapshot(),
            diagnostics = output.diagnostics() + HexanaScriptDiagnostic(
                code = "TIMEOUT",
                message = "Script timed out after ${limits.timeoutMillis} ms",
            ),
            binary = binaryIdentity,
            sourceName = script.name,
            sourcePath = script.path,
            durationMillis = elapsedMillis(started),
        )

    private fun interruptedResult(
        script: LoadedScript,
        binaryIdentity: BinaryModuleIdentityResult?,
        output: ScriptOutputCollector,
        started: Long,
    ): HexanaScriptResult =
        HexanaScriptResult(
            status = HexanaScriptStatus.INTERNAL_ERROR,
            output = output.snapshot(),
            diagnostics = output.diagnostics() + HexanaScriptDiagnostic(
                code = "INTERRUPTED",
                message = "Script execution was interrupted",
            ),
            binary = binaryIdentity,
            sourceName = script.name,
            sourcePath = script.path,
            durationMillis = elapsedMillis(started),
        )

    private fun rethrowFatalCause(cause: Throwable?) {
        when (cause) {
            is VirtualMachineError -> throw cause
            is LinkageError -> throw cause
        }
    }

    private fun executeInContext(
        script: LoadedScript,
        binaryProvider: BinaryProvider,
        binaryIdentity: BinaryModuleIdentityResult?,
        fileName: String?,
        filePath: String?,
        output: ScriptOutputCollector,
        contextRef: AtomicReference<Context?>,
        started: Long,
        invocation: ScriptInvocation = EmptyScriptInvocation,
    ): HexanaScriptResult {
        val contributions = GraalScriptContributionCollector()
        contributions.invocation = invocation
        val previousClassLoader = Thread.currentThread().contextClassLoader

        // Load extra input binaries (inputs[1..n]) and extend allowed policy with any
        // path-typed flag values. Done before eval so the policy is ready when the
        // script accesses hexana.inputs or hexana.flags.
        val (extraBinaryValues, effectivePolicy) = loadExtraInputsAndBuildPolicy(invocation, allowedFilePolicy)
        contributions.inputBinaryValues = extraBinaryValues

        return try {
            Thread.currentThread().contextClassLoader = HexanaScriptRunner::class.java.classLoader
            val context = buildScriptContext()
            contextRef.set(context)

            val findings: List<CheckFinding>
            context.use { ctx ->
                val bindings = ctx.getBindings("js")
                // The surface itself lives in plugins-shared so that the IDE, the
                // VS Code webview, and this runtime all bind the same thing.
                val globals = scriptGlobals(
                    provider = binaryProvider,
                    output = ScriptOutput(output::print),
                    fileName = fileName,
                    filePath = filePath,
                    // Inline: this runtime reads the file within the call. Fresh
                    // per run, because the opener memoizes what the script opened
                    // and that must not outlive the run holding it in memory.
                    open = ScriptOpenPolicy.Inline(ScriptBinaryFileOpener(limits, effectivePolicy)),
                )
                for ((name, value) in globals) {
                    bindings.putMember(name, ScriptValueGraalProxy.toGuest(value))
                }
                // Capture the primary binary ScriptValue for inputs[0].
                contributions.primaryBinaryValue = globals[SCRIPT_BINARY_GLOBAL] ?: ScriptValue.Null
                // The v2 globals are host-bound on top of the pinned v1 set:
                // Graal-native proxies receive raw Values for defineSlice and console.
                bindings.putMember(SCRIPT_HEXANA_GLOBAL, contributions.buildGraalHexanaProxy())
                bindings.putMember(SCRIPT_CONSOLE_GLOBAL, contributions.buildGraalConsoleProxy())
                val evalResult = ctx.eval(Source.newBuilder("js", script.text, script.name).build())
                detectAsyncOutputLost(evalResult, output)

                // Resolve flags against the final declarations so usage errors
                // (undeclared flags etc.) are detected even if the script never
                // read hexana.flags.
                contributions.resolveInvocationFlags()

                // Run checks against the primary binary (using the raw Graal binary value).
                val primaryGraalBinary = bindings.getMember(SCRIPT_BINARY_GLOBAL)
                findings = if (contributions.checks.isNotEmpty() && primaryGraalBinary != null) {
                    contributions.runChecks(primaryGraalBinary)
                } else emptyList()
            }

            HexanaScriptResult(
                status = HexanaScriptStatus.SUCCESS,
                output = output.snapshot(),
                diagnostics = output.diagnostics(),
                binary = binaryIdentity,
                sourceName = script.name,
                sourcePath = script.path,
                durationMillis = elapsedMillis(started),
                slices = contributions.slices.map { it.toInfo() },
                console = contributions.console.toList(),
                checks = contributions.checks.map { it.toInfo() },
                providers = contributions.providers.map { it.toInfo() },
                findings = findings,
                usageErrors = contributions.usageErrors.distinct(),
                flagDeclarations = contributions.flagDeclarations.toMap(),
            )
        } catch (error: PolyglotException) {
            val diagnostic = error.toScriptDiagnostic()
            HexanaScriptResult(
                status = if (error.isCancelled) HexanaScriptStatus.TIMEOUT else HexanaScriptStatus.SCRIPT_ERROR,
                output = output.snapshot(),
                diagnostics = output.diagnostics() + diagnostic,
                binary = binaryIdentity,
                sourceName = script.name,
                sourcePath = script.path,
                durationMillis = elapsedMillis(started),
                slices = contributions.slices.map { it.toInfo() },
                console = contributions.console.toList(),
                checks = contributions.checks.map { it.toInfo() },
                providers = contributions.providers.map { it.toInfo() },
                findings = emptyList(),
                usageErrors = contributions.usageErrors.distinct(),
                flagDeclarations = contributions.flagDeclarations.toMap(),
            )
        } catch (error: Exception) {
            internalErrorResult(
                sourceName = script.name,
                sourcePath = script.path,
                binaryIdentity = binaryIdentity,
                output = output.snapshot(),
                error = error,
                durationMillis = elapsedMillis(started),
            )
        } finally {
            contextRef.set(null)
            Thread.currentThread().contextClassLoader = previousClassLoader
        }
    }

    /**
     * Builds a sandboxed GraalJS [Context]. Extracted so [executeInContext] and
     * session mode share identical sandbox settings.
     */
    internal fun buildScriptContext(): Context = Context.newBuilder("js")
        .allowAllAccess(false)
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowIO(IOAccess.NONE)
        .allowCreateProcess(false)
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .allowExperimentalOptions(true)
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    /**
     * If [value] looks like a thenable whose then-callback has not yet fired,
     * emits an ASYNC_OUTPUT_LOST warning diagnostic.
     *
     * GraalJS does not drain microtasks after [Context.eval] in this sandbox
     * configuration (no event loop, no explicit microtask pump), so an
     * immediately-resolved promise also appears as un-settled from the Kotlin
     * side. The detection therefore fires on any thenable — including
     * `Promise.resolve(...)` — which is intentional and documented in the tests.
     *
     * The check is wrapped in a broad try/catch so it can never fail a run.
     */
    internal fun detectAsyncOutputLost(value: org.graalvm.polyglot.Value?, output: ScriptOutputCollector) {
        try {
            if (value != null && value.hasMembers() && value.hasMember("then") && value.canInvokeMember("then")) {
                output.addDiagnostic(
                    HexanaScriptDiagnostic(
                        code = "ASYNC_OUTPUT_LOST",
                        message = "Script returned a Promise or thenable. " +
                            "Async output will not be captured; move async work into a synchronous top-level call.",
                        severity = HexanaScriptDiagnosticSeverity.WARNING,
                    )
                )
            }
        } catch (_: Exception) {
            // Detection must never fail the run.
        }
    }

    private fun loadScript(source: HexanaScriptSource): ScriptLoadResult {
        return try {
            when (source) {
                is HexanaScriptSource.Inline -> loadInlineScript(source)
                is HexanaScriptSource.FilePath -> loadFileScript(source)
            }
        } catch (error: HexanaAnalysisException) {
            ScriptLoadResult.Failed(
                invalidInputResult(
                    sourceName = source.displayName(),
                    sourcePath = (source as? HexanaScriptSource.FilePath)?.path,
                    diagnostic = error.toDiagnostic(),
                )
            )
        } catch (error: Exception) {
            ScriptLoadResult.Failed(
                internalErrorResult(
                    sourceName = source.displayName(),
                    sourcePath = (source as? HexanaScriptSource.FilePath)?.path,
                    error = error,
                )
            )
        }
    }

    private fun loadInlineScript(source: HexanaScriptSource.Inline): ScriptLoadResult {
        val size = source.text.toByteArray(StandardCharsets.UTF_8).size
        if (size > limits.maxInlineScriptBytes) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
                "Inline script exceeds maximum size of ${limits.maxInlineScriptBytes} bytes",
            )
        }
        return ScriptLoadResult.Loaded(LoadedScript(source.text, source.name, path = null))
    }

    private fun loadFileScript(source: HexanaScriptSource.FilePath): ScriptLoadResult {
        val path = allowedFilePolicy.resolveAllowedRegularFile(source.path, "Script file")
        val readResult = try {
            readScriptFileBounded(path, limits.maxScriptFileBytes)
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read script file",
                error,
            )
        }
        val bytes = when (readResult) {
            is ScriptFileReadResult.Bytes -> readResult.bytes
            is ScriptFileReadResult.TooLarge -> throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
                "Script file exceeds maximum size of ${limits.maxScriptFileBytes} bytes",
            )
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw HexanaAnalysisException(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Failed to read script file",
                error,
            )
        }
        return ScriptLoadResult.Loaded(LoadedScript(text, path.fileName.toString(), path.toString()))
    }

    private fun invalidInputResult(
        sourceName: String,
        sourcePath: String?,
        diagnostic: HexanaScriptDiagnostic,
        durationMillis: Long = 0,
    ): HexanaScriptResult =
        HexanaScriptResult(
            status = HexanaScriptStatus.INVALID_INPUT,
            output = emptyOutput(),
            diagnostics = listOf(diagnostic),
            binary = null,
            sourceName = sourceName,
            sourcePath = sourcePath,
            durationMillis = durationMillis,
        )

    private fun internalErrorResult(
        sourceName: String,
        sourcePath: String?,
        binaryIdentity: BinaryModuleIdentityResult? = null,
        output: HexanaScriptOutput = emptyOutput(),
        error: Throwable,
        durationMillis: Long = 0,
    ): HexanaScriptResult =
        HexanaScriptResult(
            status = HexanaScriptStatus.INTERNAL_ERROR,
            output = output,
            diagnostics = listOf(
                HexanaScriptDiagnostic(
                    code = "INTERNAL_ERROR",
                    message = error.message ?: error::class.java.name,
                    exceptionClass = error::class.java.name,
                )
            ),
            binary = binaryIdentity,
            sourceName = sourceName,
            sourcePath = sourcePath,
            durationMillis = durationMillis,
        )

    private fun emptyOutput(): HexanaScriptOutput =
        HexanaScriptOutput(
            lines = emptyList(),
            truncated = false,
            emittedBytes = 0,
            maxLines = limits.maxOutputLines,
            maxBytes = limits.maxOutputBytes,
        )

    private fun elapsedMillis(started: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun HexanaScriptSource.displayName(): String =
        when (this) {
            is HexanaScriptSource.Inline -> name
            is HexanaScriptSource.FilePath -> path
        }

    private fun HexanaAnalysisException.toDiagnostic(): HexanaScriptDiagnostic =
        HexanaScriptDiagnostic(
            code = code.name,
            message = message,
            exceptionClass = this::class.java.name,
        )

    private fun PolyglotException.toScriptDiagnostic(): HexanaScriptDiagnostic {
        val location = sourceLocation
        val code = when {
            isCancelled -> "TIMEOUT"
            isSyntaxError -> "SYNTAX_ERROR"
            isSandboxDeniedMessage(message) -> "SANDBOX_DENIED"
            else -> "RUNTIME_ERROR"
        }
        return HexanaScriptDiagnostic(
            code = code,
            message = message ?: code,
            exceptionClass = this::class.java.name,
            sourceName = location?.source?.name,
            line = location?.startLine?.takeIf { it > 0 },
            column = location?.startColumn?.takeIf { it > 0 },
        )
    }

    private fun isSandboxDeniedMessage(message: String?): Boolean {
        val value = message ?: return false
        return value.contains("host class", ignoreCase = true) ||
            value.contains("Java is not defined", ignoreCase = true) ||
            value.contains("Java.type", ignoreCase = true)
    }

    /**
     * Opens a persistent script session, keeping the GraalJS context alive so
     * that registered slice [rows] functions can be called after the initial eval.
     *
     * @param source script to evaluate
     * @param binaryProvider binary to expose as `binary`
     * @param fileName file name of the binary, surfaced as `binary.name`
     * @param filePath path of the binary on disk, surfaced as `binary.path`
     * @param onLine invoked for every output line as it is emitted during eval
     * @param invocation host invocation context (args/inputs/flags); defaults to the empty/tab shape
     */
    fun openSession(
        source: HexanaScriptSource,
        binaryProvider: BinaryProvider,
        fileName: String? = null,
        filePath: String? = null,
        onLine: ((String) -> Unit)? = null,
        invocation: ScriptInvocation = EmptyScriptInvocation,
    ): HexanaScriptSession {
        val loadedScript = when (val script = loadScript(source)) {
            is ScriptLoadResult.Loaded -> script.script
            is ScriptLoadResult.Failed -> return HexanaScriptSession.dead(script.result)
        }
        return HexanaScriptSession.start(
            runner = this,
            script = loadedScript,
            binaryProvider = binaryProvider,
            fileName = fileName,
            filePath = filePath,
            limits = limits,
            allowedFilePolicy = allowedFilePolicy,
            onLine = onLine,
            invocation = invocation,
        )
    }

    /**
     * Loads extra input binaries from [invocation.extraInputPaths] through the existing
     * [BinaryInputLoader]/[AllowedFilePolicy] path. Returns the list of binary [ScriptValue]s
     * and an extended policy that also allows any path-typed flag values.
     *
     * Each unique path is loaded once. Extra inputs share the [limits.maxOpenedBinaries] quota
     * with scripts opened via `open()`.
     */
    private fun loadExtraInputsAndBuildPolicy(
        invocation: ScriptInvocation,
        basePolicy: AllowedFilePolicy,
    ): Pair<List<ScriptValue>, AllowedFilePolicy> {
        if (invocation.extraInputPaths.isEmpty()) return emptyList<ScriptValue>() to basePolicy
        val loader = BinaryInputLoader(
            maxInlineInputBytes = limits.maxInlineBinaryBytes,
            maxFileInputBytes = limits.maxFileBinaryBytes,
            allowedFilePolicy = basePolicy,
        )
        val values = mutableListOf<ScriptValue>()
        val resolvedPaths = mutableListOf<java.nio.file.Path>()
        for (path in invocation.extraInputPaths) {
            try {
                val loaded = loader.load(BinaryInput.FilePath(path))
                val fileName = loaded.resolvedPath?.let { java.nio.file.Path.of(it).fileName?.toString() }
                val sv = scriptBinaryApi(loaded.provider, fileName, loaded.resolvedPath)
                values.add(sv)
                loaded.resolvedPath?.let { resolvedPaths.add(java.nio.file.Path.of(it)) }
            } catch (error: HexanaAnalysisException) {
                throw IllegalArgumentException("inputs: ${error.message}", error)
            }
        }
        val extendedPolicy = if (resolvedPaths.isNotEmpty()) basePolicy.withAdditionalAllowedFiles(resolvedPaths) else basePolicy
        return values to extendedPolicy
    }

    // Internal helpers exposed to HexanaScriptSession

    internal fun PolyglotException.mapToScriptDiagnostic(): HexanaScriptDiagnostic =
        toScriptDiagnostic()

    private sealed interface ScriptLoadResult {
        data class Loaded(val script: LoadedScript) : ScriptLoadResult
        data class Failed(val result: HexanaScriptResult) : ScriptLoadResult
    }

    internal data class LoadedScript(
        val text: String,
        val name: String,
        val path: String?,
    )

    private companion object {
        val scriptThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "hexana-script-runner").apply {
                isDaemon = true
            }
        }
    }
}

private fun readScriptFileBounded(path: java.nio.file.Path, maxBytes: Int): ScriptFileReadResult {
    var bytes = ByteArray(minOf(maxBytes, 8 * 1024))
    var offset = 0
    Files.newInputStream(path).use { input ->
        while (offset < maxBytes) {
            if (offset == bytes.size) {
                bytes = bytes.copyOf(
                    minOf(maxBytes.toLong(), bytes.size.toLong() * 2L).toInt()
                )
            }
            val count = input.read(bytes, offset, bytes.size - offset)
            when {
                count < 0 -> return ScriptFileReadResult.Bytes(bytes.trimToScriptSize(offset))
                count > 0 -> offset += count
                else -> {
                    val singleByte = input.read()
                    if (singleByte < 0) return ScriptFileReadResult.Bytes(bytes.trimToScriptSize(offset))
                    bytes[offset++] = singleByte.toByte()
                }
            }
        }
        return if (input.read() < 0) {
            ScriptFileReadResult.Bytes(bytes)
        } else {
            ScriptFileReadResult.TooLarge
        }
    }
}

private fun ByteArray.trimToScriptSize(size: Int): ByteArray = if (size == this.size) this else copyOf(size)

private sealed interface ScriptFileReadResult {
    data class Bytes(val bytes: ByteArray) : ScriptFileReadResult
    data object TooLarge : ScriptFileReadResult
}

internal class ScriptOutputCollector(
    private val maxLines: Int,
    private val maxBytes: Int,
    private val onLine: ((String) -> Unit)? = null,
) {
    private val lines = mutableListOf<String>()
    private var emittedBytes = 0
    private var truncated = false

    @Synchronized
    fun print(line: String) {
        if (truncated) return
        if (lines.size >= maxLines) {
            truncated = true
            return
        }

        val separatorBytes = if (lines.isEmpty()) 0 else 1
        val remaining = maxBytes - emittedBytes - separatorBytes
        if (remaining <= 0) {
            truncated = true
            return
        }

        val boundedLine = line.takeUtf8Prefix(remaining)
        if (boundedLine.text.isNotEmpty() || !boundedLine.truncated) {
            lines += boundedLine.text
            emittedBytes += separatorBytes + boundedLine.byteCount
            // Streamed after the limits are applied, so an interactive tab shows
            // exactly the lines the result carries.
            onLine?.invoke(boundedLine.text)
        }
        if (boundedLine.truncated) {
            truncated = true
        }
    }

    @Synchronized
    fun snapshot(): HexanaScriptOutput =
        HexanaScriptOutput(
            lines = lines.toList(),
            truncated = truncated,
            emittedBytes = emittedBytes,
            maxLines = maxLines,
            maxBytes = maxBytes,
        )

    @Synchronized
    fun addDiagnostic(diagnostic: HexanaScriptDiagnostic) {
        extraDiagnostics += diagnostic
    }

    @Synchronized
    fun diagnostics(): List<HexanaScriptDiagnostic> {
        val truncationDiag = if (truncated) {
            listOf(
                HexanaScriptDiagnostic(
                    code = "OUTPUT_TRUNCATED",
                    message = "Script output exceeded the configured output limit",
                    severity = HexanaScriptDiagnosticSeverity.WARNING,
                )
            )
        } else {
            emptyList()
        }
        return truncationDiag + extraDiagnostics
    }

    private val extraDiagnostics = mutableListOf<HexanaScriptDiagnostic>()
}

private data class Utf8Prefix(
    val text: String,
    val byteCount: Int,
    val truncated: Boolean,
)

private fun String.takeUtf8Prefix(maxBytes: Int): Utf8Prefix {
    if (maxBytes <= 0) return Utf8Prefix("", 0, isNotEmpty())
    var used = 0
    var index = 0
    var builder: StringBuilder? = null
    while (index < length) {
        val char = this[index]
        val codePoint: Int
        val charCount: Int
        val replacedSurrogate: Boolean
        when {
            Character.isHighSurrogate(char) && index + 1 < length && Character.isLowSurrogate(this[index + 1]) -> {
                codePoint = Character.toCodePoint(char, this[index + 1])
                charCount = 2
                replacedSurrogate = false
            }
            Character.isSurrogate(char) -> {
                codePoint = REPLACEMENT_CHARACTER_CODE_POINT
                charCount = 1
                replacedSurrogate = true
            }
            else -> {
                codePoint = char.code
                charCount = 1
                replacedSurrogate = false
            }
        }

        val codePointBytes = codePoint.utf8ByteCount()
        if (used + codePointBytes > maxBytes) break

        if (builder != null) {
            builder.appendCodePoint(codePoint)
        } else if (replacedSurrogate) {
            builder = StringBuilder(index + charCount).append(this, 0, index).appendCodePoint(codePoint)
        }
        used += codePointBytes
        index += charCount
    }

    val truncated = index < length
    val text = when {
        builder != null -> builder.toString()
        truncated -> substring(0, index)
        else -> this
    }
    return Utf8Prefix(text, used, truncated)
}

private fun Int.utf8ByteCount(): Int =
    when {
        this <= 0x7F -> 1
        this <= 0x7FF -> 2
        this <= 0xFFFF -> 3
        else -> 4
    }

private const val REPLACEMENT_CHARACTER_CODE_POINT = 0xFFFD
