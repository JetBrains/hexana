package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.disassembly.core.Disassembler
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass

/**
 * The two binaries being compared, plus the optional `--emit-symbol-map` sidecars
 * that restore original names on a minified Wasm module.
 *
 * The buffers are shared, never consumed: every reader takes a `duplicate()`.
 */
class BinaryDiffInput(
    val left: CommonByteBuffer,
    val right: CommonByteBuffer,
    val leftName: String,
    val rightName: String,
    val leftSymbolMap: WasmSymbolMap? = null,
    val rightSymbolMap: WasmSymbolMap? = null,
) {
    /** The display title hosts give the diff tab/editor. */
    val title: String get() = "$leftName ↔ $rightName"

    internal val leftType: BinaryProviderType by lazy { WasmBinaryProviderSelector.select(left.duplicate()) }
    internal val rightType: BinaryProviderType by lazy { WasmBinaryProviderSelector.select(right.duplicate()) }
}

/**
 * Declares how one binary format is compared: which tabs the diff view has and what the
 * detail pane below them renders.
 *
 * The diff counterpart of [org.jetbrains.hexana.shared.TableTabsProvider]: implementations
 * live here, UI-free, and every host (IntelliJ, VS Code) renders whatever
 * [BinaryDiffProviderRegistry] returns. A new format, tab, or detail pane therefore appears
 * in every host without host changes.
 */
interface BinaryDiffProvider {
    /** Cheap sniff of both sides. The first accepting provider in the registry wins. */
    fun accepts(input: BinaryDiffInput): Boolean

    fun session(input: BinaryDiffInput, host: BinaryDiffHost = BinaryDiffHost()): BinaryDiffSession
}

/**
 * Capabilities only a host can supply. Everything is optional: a provider degrades
 * when a capability is missing rather than disappearing from that host.
 *
 * @param nativeDisassembler loads the instruction decoder used by the native per-function
 *   diff. Called at most once per session, lazily. `null` means native functions are compared
 *   as raw bytes instead of instructions.
 */
class BinaryDiffHost(
    val nativeDisassembler: (suspend () -> Disassembler)? = null,
)

/**
 * One open diff.
 *
 * @param tabs the tab strip, in order; each tab is computed independently and lazily, so a
 *   slow tab never blocks a fast one
 * @param banner lines shown above every tab (e.g. the Wasm "symbols are minified" notice);
 *   may parse, so hosts call it off the UI thread
 * @param detail the bottom split rendering the item selected in a table; `null` means the
 *   format has no per-item comparison and the host shows no split
 */
class BinaryDiffSession(
    val tabs: List<DiffTab>,
    val banner: () -> List<DiffTextLine> = { emptyList() },
    val detail: DiffDetailSource? = null,
)

/**
 * A diff tab. [compute] does the expensive parse + diff; hosts run it off the UI thread,
 * once per open diff, and keep the result across tab switches. It suspends only to load
 * host capabilities (see [BinaryDiffHost]); the work itself is blocking.
 */
class DiffTab(
    val name: String,
    val loadingMessage: String,
    val compute: suspend (DiffTabContext) -> DiffTabContent,
)

/**
 * What the host lets a diff tab do when a row is clicked.
 *
 * @param select feeds [DiffItemRef] to the session's [BinaryDiffSession.detail] pane
 * @param open opens another diff, or a single binary, in a new tab; `null` when the host
 *   cannot, in which case rows that would navigate do nothing
 */
class DiffTabContext(
    val select: (DiffItemRef?) -> Unit = {},
    val open: ((DiffOpenRequest) -> Unit)? = null,
)

/**
 * Something a diff row asks the host to open.
 *
 * The payload is a factory, not a value: building it can copy megabytes (a submodule is
 * sliced out of its parent and materialized), and the row click that creates the request
 * runs on the UI thread. Hosts call it off that thread.
 */
sealed interface DiffOpenRequest {
    val title: String

    /** Another diff, e.g. a pair of submodules of two components. */
    class Diff(override val title: String, val input: () -> BinaryDiffInput) : DiffOpenRequest

    /** A single binary with nothing to compare against, e.g. an added submodule. */
    class Single(override val title: String, val bytes: () -> CommonByteBuffer) : DiffOpenRequest
}

/**
 * A computed diff tab.
 *
 * @param header summary lines above the table
 * @param table the comparison table; `null` means there is nothing to list and
 *   [emptyMessage] is shown instead
 * @param emptyMessage shown when [table] is `null` (identical inputs, parse failure)
 * @param footer a line below the table, e.g. how many identical rows are hidden
 */
class DiffTabContent(
    val header: List<DiffTextLine> = emptyList(),
    val table: TableTabDescriptor? = null,
    val emptyMessage: String? = null,
    val footer: String? = null,
)

class DiffTextLine(val text: String, val bold: Boolean = false)

/**
 * The item a table row selects for the detail pane — a function today, on each side.
 * A `null` index means the item is absent on that side (added / removed), or that the
 * matcher could not pair it and the detail source should resolve it by [name].
 */
data class DiffItemRef(val name: String, val leftIndex: Int?, val rightIndex: Int?)

/** Renders the selected [DiffItemRef] side by side. [render] may be slow; run it off the UI thread. */
interface DiffDetailSource {
    /** Shown before anything is selected. */
    val placeholder: String

    suspend fun render(ref: DiffItemRef): SideBySideDiff
}

object BinaryDiffProviderRegistry {
    val providers: List<BinaryDiffProvider> by lazy {
        listOf(
            WasmComponentDiffProvider(),
            WasmModuleDiffProvider(),
            ClrDiffProvider(),
            NativeDiffProvider(),
        )
    }

    /** The provider for [input], or `null` when the two sides cannot be compared (e.g. a module and a component). */
    fun forInput(input: BinaryDiffInput): BinaryDiffProvider? = providers.firstOrNull { it.accepts(input) }

    /**
     * Why [input] should not be compared, phrased for the user, or `null` when it can be.
     *
     * One compare action serves every format, so this is where a pair is turned down: two
     * files of different kinds (a module and a component, Wasm and native), and a `.wasm`
     * whose header is not WebAssembly — the native fallback would accept that one and show
     * a truncated or mislabelled module as an empty native diff.
     */
    fun rejectionReason(input: BinaryDiffInput): String? {
        for ((name, type) in listOf(input.leftName to input.leftType, input.rightName to input.rightType)) {
            if (type == BinaryProviderType.Generic && name.endsWith(".wasm", ignoreCase = true)) {
                return "$name is not a WebAssembly binary (it has no `\\0asm` header)."
            }
        }
        if (forInput(input) != null) return null
        return "${input.leftName} is ${kindLabel(input.leftType)} and ${input.rightName} is " +
            "${kindLabel(input.rightType)} — only binaries of the same kind can be compared."
    }

    private fun kindLabel(type: BinaryProviderType): String = when (type) {
        BinaryProviderType.Wasm -> "a WebAssembly module"
        BinaryProviderType.WasmComponent -> "a WebAssembly component"
        BinaryProviderType.Generic -> "not WebAssembly"
    }
}

internal fun DiffClass.label(): String = when (this) {
    DiffClass.ADDED -> "Added"
    DiffClass.REMOVED -> "Removed"
    DiffClass.MODIFIED -> "Modified"
    DiffClass.MOVED -> "Moved"
    DiffClass.IDENTICAL -> "Identical"
}

internal fun DiffClass.toRowTint(): RowTint? = when (this) {
    DiffClass.ADDED -> RowTint.Added
    DiffClass.REMOVED -> RowTint.Removed
    DiffClass.MODIFIED -> RowTint.Modified
    DiffClass.MOVED -> RowTint.Moved
    DiffClass.IDENTICAL -> null
}

internal fun signedSize(delta: Int): String = when {
    delta > 0 -> "+${formatSize(delta)}"
    delta < 0 -> "-${formatSize(-delta)}"
    else -> "0 B"
}

internal fun hiddenFooter(hidden: Int, singular: String, plural: String): String =
    "$hidden identical ${if (hidden == 1) singular else plural} hidden"
