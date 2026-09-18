package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.scripting.ScriptConsoleEntry
import org.jetbrains.hexana.shared.scripting.ScriptOpenPolicy
import org.jetbrains.hexana.shared.scripting.ScriptValue
import org.jetbrains.hexana.shared.scripting.ScriptOutput
import org.jetbrains.hexana.shared.scripting.SliceColumnType
import org.jetbrains.hexana.shared.scripting.SliceRegistration
import org.jetbrains.hexana.shared.scripting.SCRIPT_BINARY_GLOBAL
import org.jetbrains.hexana.shared.scripting.SCRIPT_CONSOLE_GLOBAL
import org.jetbrains.hexana.shared.scripting.SCRIPT_HEXANA_GLOBAL
import org.jetbrains.hexana.shared.scripting.scriptGlobals
import org.jetbrains.hexana.shared.scripting.toInfo
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A cell value from a slice page, with both its string representation and
 * an optional numeric interpretation for offset/funcidx columns.
 */
data class SliceCellValue(
    val text: String,
    /** Set when the column type is OFFSET or FUNCIDX. */
    val numeric: Long? = null,
)

/** One data row from a slice page. */
data class SliceRow(
    /** Anchor value for the row, e.g. a function index or byte offset. */
    val anchor: Long?,
    /** Cell values in column order. */
    val cells: List<SliceCellValue>,
)

/** A page of rows returned by [HexanaScriptSession.fetchPage]. */
data class SlicePage(
    val rows: List<SliceRow>,
    /** Offset to use for the next page call, or null if no further rows exist. */
    val nextOffset: Int?,
    /** Total row count hint from the script, or null if not provided. */
    val total: Int?,
)

/** Error returned when a page call fails without killing the session. */
data class SlicePageError(
    val diagnostic: HexanaScriptDiagnostic,
)

/** Result of [HexanaScriptSession.fetchPage]. */
sealed interface SlicePageResult {
    data class Ok(val page: SlicePage) : SlicePageResult
    data class Err(val error: SlicePageError) : SlicePageResult
}

/**
 * A persistent script session that keeps the GraalJS context alive so
 * that registered slice `rows` functions can be called after the initial eval.
 *
 * Create via [HexanaScriptRunner.openSession]. Must be closed when done.
 *
 * The session is single-threaded: all JS invocations run on the same executor
 * thread the eval ran on. Never call GraalJS values from other threads.
 */
class HexanaScriptSession private constructor(
    /** Initial run result (includes slices metadata and console from eval). */
    val initialResult: HexanaScriptResult,
    private val contextRef: AtomicReference<Context?>,
    private val executor: java.util.concurrent.ExecutorService?,
    private val contributions: GraalScriptContributionCollector?,
    private val limits: HexanaScriptLimits,
    private val runner: HexanaScriptRunner?,
    /** The `binary` ScriptValue passed as the first arg to slice rows() calls. */
    private val binaryValue: ScriptValue,
) : AutoCloseable {

    private val _closed = AtomicBoolean(false)

    /** True while the context is open and the session can accept page calls. */
    val isAlive: Boolean get() = !_closed.get() && contextRef.get() != null

    /** Live slice registrations (include the callable rows Fn). */
    val registrations: List<SliceRegistration>
        get() = contributions?.slices ?: emptyList()

    /**
     * All console entries emitted so far (eval + any page calls), in order.
     *
     * Safe to call from any thread only AFTER the call that produced the entries
     * has completed (eval or a fetchPage): the future.get() barrier inside those
     * calls publishes the executor thread's writes. Do not call concurrently
     * with an in-flight page call.
     */
    fun consoleSnapshot(): List<ScriptConsoleEntry> =
        contributions?.console?.toList() ?: initialResult.console

    /**
     * Console entries from [fromIndex] onward (inclusive).
     */
    fun consoleSince(fromIndex: Int): List<ScriptConsoleEntry> =
        contributions?.consoleSince(fromIndex) ?: emptyList()

    /**
     * Fetches a page of rows from slice [sliceName].
     *
     * Invokes the slice's `rows` function on the executor thread with
     * `{offset, limit}` as the paging argument. Param defaults are used for
     * all parameters in Phase 1.
     *
     * A guest JS error is returned as [SlicePageResult.Err] without killing the
     * session. A timeout or cancellation marks the session dead.
     *
     * @throws IllegalStateException when the session is already closed
     * @throws IllegalArgumentException when [sliceName] is not registered
     */
    fun fetchPage(sliceName: String, offset: Int, limit: Int): SlicePageResult {
        check(isAlive) { "Session is closed" }
        val registration = registrations.find { it.name == sliceName }
            ?: throw IllegalArgumentException("No slice registered with name \"$sliceName\"")

        val future = executor!!.submit(Callable {
            invokeRowsOnExecutor(registration, offset, limit)
        })

        return try {
            future.get(limits.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            killSession()
            SlicePageResult.Err(
                SlicePageError(
                    HexanaScriptDiagnostic(
                        code = "TIMEOUT",
                        message = "Slice rows() timed out after ${limits.timeoutMillis} ms",
                    )
                )
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            killSession()
            SlicePageResult.Err(
                SlicePageError(
                    HexanaScriptDiagnostic(
                        code = "INTERRUPTED",
                        message = "Slice rows() was interrupted",
                    )
                )
            )
        } catch (error: ExecutionException) {
            // Guest errors are returned as Err; fatal JVM errors re-throw.
            val cause = error.cause ?: error
            when (cause) {
                is VirtualMachineError -> throw cause
                is LinkageError -> throw cause
                else -> SlicePageResult.Err(
                    SlicePageError(
                        HexanaScriptDiagnostic(
                            code = "INTERNAL_ERROR",
                            message = cause.message ?: cause::class.java.name,
                            exceptionClass = cause::class.java.name,
                        )
                    )
                )
            }
        }
    }

    /** Closes the session. Idempotent; safe to call multiple times. */
    override fun close() {
        if (_closed.compareAndSet(false, true)) {
            contextRef.getAndSet(null)?.close(true)
            executor?.shutdownNow()
        }
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private fun killSession() {
        _closed.set(true)
        contextRef.getAndSet(null)?.close(true)
        executor?.shutdownNow()
    }

    /**
     * Called on the executor thread. Invokes the slice rows function via the raw
     * Graal Value (bypassing [ScriptValueGraalProxy.fromGuest], which would flatten
     * the returned JS object to a string) and converts the result to [SlicePageResult].
     */
    private fun invokeRowsOnExecutor(registration: SliceRegistration, offset: Int, limit: Int): SlicePageResult {
        return try {
            val rawFn = contributions?.rawRowsFunctions?.get(registration.name)
                ?: return SlicePageResult.Err(
                    SlicePageError(
                        HexanaScriptDiagnostic(
                            code = "INTERNAL_ERROR",
                            message = "Raw rows function not found for slice \"${registration.name}\"",
                        )
                    )
                )

            // Build paging and params as Graal Values via ScriptValueGraalProxy.toGuest.
            val pagingScriptValue = ScriptValue.Obj(
                members = linkedMapOf(
                    "offset" to { ScriptValue.Num(offset) },
                    "limit" to { ScriptValue.Num(limit) },
                ),
            )
            val paramMembers = linkedMapOf<String, () -> ScriptValue>()
            for (p in registration.params) {
                val captured = p.default
                paramMembers[p.name] = { captured }
            }
            val paramsScriptValue = ScriptValue.Obj(members = paramMembers)

            // Convert ScriptValues to Graal Values. JS signature: rows(binary, params, paging).
            val binaryGraal = ScriptValueGraalProxy.toGuest(binaryValue)
            val paramsGraal = ScriptValueGraalProxy.toGuest(paramsScriptValue)
            val pagingGraal = ScriptValueGraalProxy.toGuest(pagingScriptValue)

            val graalResult = rawFn.execute(binaryGraal, paramsGraal, pagingGraal)
            val page = convertGraalRowsResult(graalResult, registration)
            SlicePageResult.Ok(page)
        } catch (error: PolyglotException) {
            if (error.isCancelled) {
                killSession()
                SlicePageResult.Err(
                    SlicePageError(
                        HexanaScriptDiagnostic(code = "TIMEOUT", message = "Slice rows() was cancelled")
                    )
                )
            } else {
                // Guest error: session stays alive.
                with(runner!!) {
                    SlicePageResult.Err(SlicePageError(error.mapToScriptDiagnostic()))
                }
            }
        } catch (error: Exception) {
            SlicePageResult.Err(
                SlicePageError(
                    HexanaScriptDiagnostic(
                        code = "RUNTIME_ERROR",
                        message = error.message ?: error::class.java.name,
                        exceptionClass = error::class.java.name,
                    )
                )
            )
        }
    }

    /**
     * Converts the raw Graal Value returned by rows() to a [SlicePage].
     *
     * Expected shape: `{ rows: [ { anchor?, cells: [...] } ], nextOffset?, total? }`
     * or just an array of row objects. Unknown keys are ignored.
     */
    private fun convertGraalRowsResult(value: Value, registration: SliceRegistration): SlicePage {
        val isObj = value.hasMembers()
        val graalRows: Value? = when {
            isObj && value.hasMember("rows") -> value.getMember("rows")
            value.hasArrayElements() -> value
            else -> null
        }

        val nextOffset: Int? = if (isObj && value.hasMember("nextOffset")) {
            val v = value.getMember("nextOffset")
            if (v.isNull || !v.isNumber) null else v.asInt()
        } else null

        val total: Int? = if (isObj && value.hasMember("total")) {
            val v = value.getMember("total")
            if (v.isNull || !v.isNumber) null else v.asInt()
        } else null

        val rows: List<SliceRow> = if (graalRows != null && graalRows.hasArrayElements()) {
            (0 until graalRows.arraySize.toInt()).map { i ->
                convertGraalRow(graalRows.getArrayElement(i.toLong()), registration)
            }
        } else {
            emptyList()
        }

        return SlicePage(rows = rows, nextOffset = nextOffset, total = total)
    }

    private fun convertGraalRow(value: Value, registration: SliceRegistration): SliceRow {
        val anchor: Long? = if (value.hasMembers() && value.hasMember("anchor")) {
            val v = value.getMember("anchor")
            if (v.isNumber) v.asLong() else null
        } else null

        val graalCells: Value? = when {
            value.hasMembers() && value.hasMember("cells") -> value.getMember("cells")
            else -> null
        }

        val cells: List<SliceCellValue> = if (graalCells != null && graalCells.hasArrayElements()) {
            (0 until graalCells.arraySize.toInt()).map { colIndex ->
                val colType = registration.columns.getOrNull(colIndex)?.type
                convertGraalCell(graalCells.getArrayElement(colIndex.toLong()), colType)
            }
        } else {
            emptyList()
        }

        return SliceRow(anchor = anchor, cells = cells)
    }

    private fun convertGraalCell(
        value: Value,
        colType: SliceColumnType?,
    ): SliceCellValue {
        val text: String = when {
            value.isNull -> "null"
            value.isString -> value.asString()
            value.isBoolean -> value.asBoolean().toString()
            value.isNumber -> ScriptOutput.formatNumber(value.asDouble())
            else -> value.toString()
        }
        val numeric: Long? = when (colType) {
            SliceColumnType.OFFSET,
            SliceColumnType.FUNCIDX,
            -> if (value.isNumber) value.asLong() else null
            else -> null
        }
        return SliceCellValue(text = text, numeric = numeric)
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    companion object {

        /** Creates a dead session wrapping a failed-load result. */
        internal fun dead(result: HexanaScriptResult): HexanaScriptSession =
            HexanaScriptSession(
                initialResult = result,
                contextRef = AtomicReference(null),
                executor = null,
                contributions = null,
                limits = HexanaScriptLimits(),
                runner = null,
                binaryValue = ScriptValue.Null,
            )

        /**
         * Evaluates [script] in a new context that stays open after eval.
         *
         * The context and executor are owned by the returned session.
         */
        internal fun start(
            runner: HexanaScriptRunner,
            script: HexanaScriptRunner.LoadedScript,
            binaryProvider: BinaryProvider,
            fileName: String?,
            filePath: String?,
            limits: HexanaScriptLimits,
            allowedFilePolicy: AllowedFilePolicy,
            onLine: ((String) -> Unit)?,
        ): HexanaScriptSession {
            val started = System.nanoTime()
            val contributions = GraalScriptContributionCollector()
            val output = ScriptOutputCollector(limits.maxOutputLines, limits.maxOutputBytes, onLine)
            val contextRef = AtomicReference<Context?>()
            val binaryValueRef = AtomicReference<ScriptValue>(ScriptValue.Null)
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "hexana-script-session").apply { isDaemon = true }
            }

            val evalResult: HexanaScriptResult = try {
                executor.submit(Callable {
                    evalInSession(
                        runner, script, binaryProvider, fileName, filePath,
                        limits, allowedFilePolicy, contributions, output, contextRef, binaryValueRef, started,
                    )
                }).get(limits.timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                contextRef.get()?.close(true)
                executor.shutdownNow()
                return dead(
                    HexanaScriptResult(
                        status = HexanaScriptStatus.TIMEOUT,
                        output = output.snapshot(),
                        diagnostics = output.diagnostics() + HexanaScriptDiagnostic(
                            code = "TIMEOUT",
                            message = "Script eval timed out after ${limits.timeoutMillis} ms",
                        ),
                        binary = null,
                        sourceName = script.name,
                        sourcePath = script.path,
                        durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        slices = contributions.slices.map { it.toInfo() },
                        console = contributions.console.toList(),
                    )
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                contextRef.get()?.close(true)
                executor.shutdownNow()
                return dead(
                    HexanaScriptResult(
                        status = HexanaScriptStatus.INTERNAL_ERROR,
                        output = output.snapshot(),
                        diagnostics = output.diagnostics() + HexanaScriptDiagnostic(
                            code = "INTERRUPTED",
                            message = "Script eval was interrupted",
                        ),
                        binary = null,
                        sourceName = script.name,
                        sourcePath = script.path,
                        durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        slices = contributions.slices.map { it.toInfo() },
                        console = contributions.console.toList(),
                    )
                )
            } catch (error: ExecutionException) {
                val cause = error.cause ?: error
                when (cause) {
                    is VirtualMachineError -> throw cause
                    is LinkageError -> throw cause
                }
                executor.shutdownNow()
                return dead(
                    HexanaScriptResult(
                        status = HexanaScriptStatus.INTERNAL_ERROR,
                        output = output.snapshot(),
                        diagnostics = listOf(
                            HexanaScriptDiagnostic(
                                code = "INTERNAL_ERROR",
                                message = cause.message ?: cause::class.java.name,
                                exceptionClass = cause::class.java.name,
                            )
                        ),
                        binary = null,
                        sourceName = script.name,
                        sourcePath = script.path,
                        durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        slices = contributions.slices.map { it.toInfo() },
                        console = contributions.console.toList(),
                    )
                )
            }

            // If eval failed, keep the context closed (it was closed in evalInSession on error).
            return HexanaScriptSession(
                initialResult = evalResult,
                contextRef = contextRef,
                executor = executor,
                contributions = contributions,
                limits = limits,
                runner = runner,
                binaryValue = binaryValueRef.get(),
            )
        }

        private fun evalInSession(
            runner: HexanaScriptRunner,
            script: HexanaScriptRunner.LoadedScript,
            binaryProvider: BinaryProvider,
            fileName: String?,
            filePath: String?,
            limits: HexanaScriptLimits,
            allowedFilePolicy: AllowedFilePolicy,
            contributions: GraalScriptContributionCollector,
            output: ScriptOutputCollector,
            contextRef: AtomicReference<Context?>,
            binaryValueRef: AtomicReference<ScriptValue>,
            started: Long,
        ): HexanaScriptResult {
            val previousClassLoader = Thread.currentThread().contextClassLoader
            return try {
                Thread.currentThread().contextClassLoader = HexanaScriptSession::class.java.classLoader
                val context = runner.buildScriptContext()
                contextRef.set(context)

                // Context stays open (not wrapped in use{}).
                val bindings = context.getBindings("js")
                val globals = scriptGlobals(
                    provider = binaryProvider,
                    output = ScriptOutput(output::print),
                    fileName = fileName,
                    filePath = filePath,
                    open = ScriptOpenPolicy.Inline(ScriptBinaryFileOpener(limits, allowedFilePolicy)),
                )
                for ((name, value) in globals) {
                    bindings.putMember(name, ScriptValueGraalProxy.toGuest(value))
                }
                // Capture the binary ScriptValue for use in session page calls.
                binaryValueRef.set(globals[SCRIPT_BINARY_GLOBAL] ?: ScriptValue.Null)
                // The v2 globals are host-bound on top of the pinned v1 set.
                bindings.putMember(SCRIPT_HEXANA_GLOBAL, contributions.buildGraalHexanaProxy())
                bindings.putMember(SCRIPT_CONSOLE_GLOBAL, contributions.buildGraalConsoleProxy())
                val evalResult = context.eval(Source.newBuilder("js", script.text, script.name).build())
                runner.detectAsyncOutputLost(evalResult, output)

                HexanaScriptResult(
                    status = HexanaScriptStatus.SUCCESS,
                    output = output.snapshot(),
                    diagnostics = output.diagnostics(),
                    binary = null,
                    sourceName = script.name,
                    sourcePath = script.path,
                    durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    slices = contributions.slices.map { it.toInfo() },
                    console = contributions.console.toList(),
                )
            } catch (error: PolyglotException) {
                // On eval error the context is closed; session will be dead.
                contextRef.getAndSet(null)?.close(true)
                with(runner) {
                    val diagnostic = error.mapToScriptDiagnostic()
                    HexanaScriptResult(
                        status = if (error.isCancelled) HexanaScriptStatus.TIMEOUT
                        else HexanaScriptStatus.SCRIPT_ERROR,
                        output = output.snapshot(),
                        diagnostics = output.diagnostics() + diagnostic,
                        binary = null,
                        sourceName = script.name,
                        sourcePath = script.path,
                        durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        slices = contributions.slices.map { it.toInfo() },
                        console = contributions.console.toList(),
                    )
                }
            } catch (error: Exception) {
                contextRef.getAndSet(null)?.close(true)
                HexanaScriptResult(
                    status = HexanaScriptStatus.INTERNAL_ERROR,
                    output = output.snapshot(),
                    diagnostics = listOf(
                        HexanaScriptDiagnostic(
                            code = "INTERNAL_ERROR",
                            message = error.message ?: error::class.java.name,
                            exceptionClass = error::class.java.name,
                        )
                    ),
                    binary = null,
                    sourceName = script.name,
                    sourcePath = script.path,
                    durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    slices = contributions.slices.map { it.toInfo() },
                    console = contributions.console.toList(),
                )
            } finally {
                Thread.currentThread().contextClassLoader = previousClassLoader
            }
        }
    }
}
