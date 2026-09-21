package org.jetbrains.hexana.ui.panel

import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic
import org.jetbrains.hexana.mcp.scripting.SliceCellValue
import org.jetbrains.hexana.mcp.scripting.SlicePageResult
import org.jetbrains.hexana.mcp.scripting.SliceRow
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckRegistrationInfo
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.jetbrains.hexana.shared.scripting.SliceColumnType
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import java.security.MessageDigest

/** Maximum accumulated rows before sort is disabled to protect the EDT. */
internal const val SORT_CAP_ROW_COUNT = 100_000

/** Page limit for each fetchPage call. */
internal const val SLICE_FETCH_LIMIT = 500

/**
 * The rendering mode chosen after a script run.
 *
 * [Compound] is selected when the run registered at least one slice.
 * [Legacy] falls through to the existing line-stream rendering.
 */
internal sealed interface ScriptRenderMode {
    data object Legacy : ScriptRenderMode
    data object Compound : ScriptRenderMode
}

/**
 * Stamps a rendered result with the script version and text hash that produced it.
 *
 * Used to detect staleness: if the script file has changed since the last run,
 * the panel shows a stale badge and a Recompute button.
 *
 * The hash is SHA-256 of the script text, hex-encoded. String.hashCode() is
 * 32-bit and has too many collisions across realistic edits; SHA-256 is stable
 * and fast enough for the sizes involved (scripts are at most a few hundred KB).
 */
internal data class ScriptRunStamp(
    /** Script-file version counter at run time. */
    val version: Int,
    /** SHA-256 hex hash of the exact script text that was run. */
    val textHash: String,
)

/** Computes SHA-256 hex of [text]. */
internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Hashes the current text of the script file at [scriptPath], or "" when the
 * file cannot be read. Blocking VFS read: call from Dispatchers.IO.
 *
 * Deliberately not suspend: with no suspension point inside, the catch below
 * can never swallow a CancellationException.
 */
internal fun hashScriptFile(scriptPath: String): String =
    try {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(scriptPath)
        val text = if (vf != null) com.intellij.openapi.vfs.VfsUtil.loadText(vf) else ""
        sha256Hex(text)
    } catch (_: Exception) {
        ""
    }

/**
 * The stale-badge decision: stale once a stamp exists and the current script
 * text hash no longer matches it. An undo back to the computed-from text makes
 * the hashes equal again and clears the badge, regardless of version bumps.
 */
internal fun isScriptStale(currentHash: String, stampHash: String): Boolean =
    stampHash.isNotEmpty() && currentHash != stampHash

/**
 * Accumulated state for one slice tab: rows fetched so far, paging cursor,
 * error state, and whether sorting has been capped.
 *
 * This is a plain (non-Compose) class so that tests can drive it without a
 * Compose runtime.
 */
internal class SliceTabModel(val info: SliceRegistrationInfo) {

    private val _rows = mutableListOf<SliceRow>()

    /** Rows accumulated so far (never shrinks within a run). */
    val rows: List<SliceRow> get() = _rows

    /** Offset to pass to the next fetchPage call, null when all rows are loaded. */
    var nextOffset: Int? = 0
        private set

    /** True when sort must be disabled (accumulated rows exceeded [SORT_CAP_ROW_COUNT]). */
    var sortCapped: Boolean = false
        private set

    /** True when the last page call produced an error and loading has stopped. */
    var hasError: Boolean = false
        private set

    /** True when there are no more pages and no error. */
    val fullyLoaded: Boolean get() = nextOffset == null && !hasError

    /**
     * Applies a successful page result.
     *
     * Appends the new rows to the accumulator, advances [nextOffset], and
     * sets [sortCapped] if the total exceeds the cap.
     */
    fun applyPage(page: org.jetbrains.hexana.mcp.scripting.SlicePage) {
        _rows.addAll(page.rows)
        nextOffset = page.nextOffset
        if (_rows.size > SORT_CAP_ROW_COUNT) sortCapped = true
    }

    /**
     * Applies an error result.
     *
     * Appends a synthetic problem row whose first cell carries the error message,
     * then marks the tab as errored so no further loading is attempted.
     */
    fun applyError(error: HexanaScriptDiagnostic) {
        val problemText = "script error: ${error.message}"
        val cells = buildList {
            add(SliceCellValue(text = problemText))
            repeat((info.columns.size - 1).coerceAtLeast(0)) {
                add(SliceCellValue(text = ""))
            }
        }
        _rows.add(SliceRow(anchor = null, cells = cells))
        hasError = true
    }
}

/** Builds a [SliceTabModel] and applies [result] to it. For tests. */
internal fun buildSliceTabModel(
    info: SliceRegistrationInfo,
    result: SlicePageResult,
): SliceTabModel {
    val model = SliceTabModel(info)
    when (result) {
        is SlicePageResult.Ok -> model.applyPage(result.page)
        is SlicePageResult.Err -> model.applyError(result.error.diagnostic)
    }
    return model
}

/** Generates TableColumn descriptors for a [SliceRegistrationInfo] schema. */
internal fun sliceColumnsForInfo(
    info: SliceRegistrationInfo,
    rows: List<SliceRow>,
): List<org.jetbrains.hexana.ui.components.TableColumn> {
    return info.columns.mapIndexed { colIndex, col ->
        org.jetbrains.hexana.ui.components.TableColumn(
            title = col.name,
            weight = 1f,
        ) { rowIndex ->
            val cell = rows.getOrNull(rowIndex)?.cells?.getOrNull(colIndex)
                ?: SliceCellValue(text = "")
            when (col.type) {
                SliceColumnType.INT,
                SliceColumnType.OFFSET,
                SliceColumnType.FUNCIDX,
                -> {
                    val num = cell.numeric ?: cell.text.toLongOrNull()
                    if (num != null) {
                        org.jetbrains.hexana.ui.components.TableCellValue(num, cell.text)
                    } else {
                        org.jetbrains.hexana.ui.components.TableCellValue(cell.text)
                    }
                }
                SliceColumnType.BOOL,
                SliceColumnType.STRING,
                -> org.jetbrains.hexana.ui.components.TableCellValue(cell.text)
            }
        }
    }
}

/** Returns the index of the first OFFSET column in [info], or null. */
internal fun SliceRegistrationInfo.firstOffsetColumnIndex(): Int? =
    columns.indexOfFirst { it.type == SliceColumnType.OFFSET }.takeIf { it >= 0 }

/** Returns the display name for a slice (strips the "user:" prefix if present). */
internal fun SliceRegistrationInfo.displayName(): String =
    if (name.startsWith("user:")) name.removePrefix("user:") else name

// -------------------------------------------------------------------------
// Mode decision (pure, testable)
// -------------------------------------------------------------------------

/**
 * Chooses [ScriptRenderMode] from the initial result metadata.
 *
 * [Compound] when at least one slice or one check was registered. [Legacy]
 * when neither was registered (v1 scripts with pure print output).
 */
internal fun renderModeFor(
    slices: List<SliceRegistrationInfo>,
    checks: List<CheckRegistrationInfo>,
): ScriptRenderMode = if (slices.isNotEmpty() || checks.isNotEmpty()) {
    ScriptRenderMode.Compound
} else {
    ScriptRenderMode.Legacy
}

// -------------------------------------------------------------------------
// Checks tab model (pure, testable)
// -------------------------------------------------------------------------

/**
 * Column rendering of a [CheckFinding] in the Checks inner tab.
 *
 * @property severity severity label: "ERROR", "WARNING", or "INFO"
 * @property checkId the check id string (e.g. "user:no-imports")
 * @property message the finding message
 * @property anchorText rendered anchor: "func[N]", "0xOFFSET+LEN", or "" for file-level
 */
internal data class CheckFindingRow(
    val severity: String,
    val checkId: String,
    val message: String,
    val anchorText: String,
)

/** Converts a [CheckFinding] to its display row. */
internal fun CheckFinding.toRow(): CheckFindingRow = CheckFindingRow(
    severity = severity.name,
    checkId = checkId,
    message = message,
    anchorText = anchor?.renderAnchor() ?: "",
)

/** Renders a [ScriptAnchor] to a compact string for the Checks tab Anchor column. */
internal fun ScriptAnchor.renderAnchor(): String = when (this) {
    is ScriptAnchor.Function -> "func[$funcidx]"
    is ScriptAnchor.ByteRange -> "0x${offset.toString(16)}+$length"
}

// -------------------------------------------------------------------------
// Problems-tab row model (pure, testable)
// -------------------------------------------------------------------------

/**
 * Columns produced for a [CheckFinding] row merged into the Problems tab.
 *
 * Mirrors the Problems tab column schema: Kind, Location, Offset, Size, Message.
 *
 * @property kind "check:error", "check:warning", or "check:info"
 * @property location the check id (e.g. "user:no-imports")
 * @property offsetText hex offset for ByteRange anchors, "" otherwise
 * @property offsetValue raw offset value for numeric sorting, -1L for non-range rows
 * @property sizeText hex size for ByteRange anchors, "" otherwise
 * @property sizeValue raw size value for numeric sorting, -1L for non-range rows
 * @property message the finding message
 */
internal data class FindingProblemsRow(
    val kind: String,
    val location: String,
    val offsetText: String,
    val offsetValue: Long,
    val sizeText: String,
    val sizeValue: Long,
    val message: String,
)

/** Converts a [CheckFinding] to its Problems-tab row. */
internal fun CheckFinding.toProblemsRow(): FindingProblemsRow {
    val kindLabel = "check:${severity.name.lowercase()}"
    return when (val a = anchor) {
        is ScriptAnchor.Function -> FindingProblemsRow(
            kind = kindLabel,
            location = "func[${a.funcidx}]",
            offsetText = "",
            offsetValue = -1L,
            sizeText = "",
            sizeValue = -1L,
            message = message,
        )
        is ScriptAnchor.ByteRange -> FindingProblemsRow(
            kind = kindLabel,
            location = checkId,
            offsetText = "0x${a.offset.toString(16)}",
            offsetValue = a.offset,
            sizeText = "0x${a.length.toString(16)}",
            sizeValue = a.length,
            message = message,
        )
        null -> FindingProblemsRow(
            kind = kindLabel,
            location = checkId,
            offsetText = "",
            offsetValue = -1L,
            sizeText = "",
            sizeValue = -1L,
            message = message,
        )
    }
}

/**
 * Model for the Checks tab state.
 *
 * Kept as a plain class so tests can drive it without Compose.
 *
 * @param checks static check registration info from [HexanaScriptResult.checks]
 * @param findings findings from [HexanaScriptSession.runChecks], or null while loading
 */
internal class CheckTabModel(
    val checks: List<CheckRegistrationInfo>,
) {
    /** True when this model has checks to show (determines whether the tab is rendered). */
    val hasChecks: Boolean get() = checks.isNotEmpty()

    /** Current findings (null = still loading). */
    var findings: List<CheckFinding>? = null

    /** True when findings have been loaded and are empty (clean run). */
    val isClean: Boolean get() = findings?.isEmpty() == true

    /** Rows derived from the current findings list (empty until loaded). */
    val rows: List<CheckFindingRow> get() = findings?.map { it.toRow() } ?: emptyList()
}
