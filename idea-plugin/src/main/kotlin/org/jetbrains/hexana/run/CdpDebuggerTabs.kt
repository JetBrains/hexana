package org.jetbrains.hexana.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.providers.format.WatSymbolNames
import org.jetbrains.hexana.providers.format.watFor
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.parseCodeEntry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

// ---------------------------------------------------------------------------
// Wasm Stack panel
// ---------------------------------------------------------------------------

/**
 * Table model for the "Wasm Stack" debugger tab.
 *
 * Rows are populated from V8's operand-stack object (property "stack" with
 * className "Stack") inside the local CDP scope of a paused wasm call frame.
 * Each row shows the stack slot index, its wasm type (e.g. "i32"), and its
 * resolved numeric value.
 */
internal class WasmStackTableModel : AbstractTableModel() {

    private val COLUMNS = arrayOf("#", "Type", "Value")

    data class Row(val index: Int, val type: String, val value: String)

    private var rows: List<Row> = emptyList()

    fun update(newRows: List<Row>) {
        rows = newRows
        fireTableDataChanged()
    }

    fun clear() {
        rows = emptyList()
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = COLUMNS.size
    override fun getColumnName(column: Int): String = COLUMNS[column]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.index
            1 -> row.type
            2 -> row.value
            else -> ""
        }
    }
}

/**
 * Creates the wasm operand-stack panel containing a [JBTable] that shows the
 * stack slots (index, type, value) for the currently paused frame.
 */
internal fun buildWasmStackPanel(model: WasmStackTableModel): JPanel {
    val table = JBTable(model).apply {
        setShowGrid(true)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 40
        columnModel.getColumn(1).preferredWidth = 60
        columnModel.getColumn(2).preferredWidth = 200
    }
    val panel = JPanel(java.awt.BorderLayout())
    panel.add(JBScrollPane(table), java.awt.BorderLayout.CENTER)
    return panel
}

/**
 * Combined "Wasm" debugger tab content: the disassembly of the paused function
 * on the left, the operand stack on the right.
 */
internal fun buildCombinedWasmPanel(
    stackModel: WasmStackTableModel,
    disassemblyPanel: WasmDisassemblyPanel,
): JComponent {
    val splitter = OnePixelSplitter(false, 0.68f)
    splitter.firstComponent = disassemblyPanel.component
    splitter.secondComponent = buildWasmStackPanel(stackModel)
    return splitter
}

// ---------------------------------------------------------------------------
// Wasm Disassembly panel
// ---------------------------------------------------------------------------

/**
 * Table model for the "Wasm Disassembly" debugger tab.
 *
 * Each row shows a module-absolute hex byte offset and the corresponding
 * WAT-style instruction text. The row matching the current pause offset is
 * selected and scrolled into view by [WasmDisassemblyPanel.highlight].
 */
internal class WasmDisassemblyTableModel : AbstractTableModel() {

    private val COLUMNS = arrayOf("Offset", "Instruction")

    data class Row(val offset: Int, val instruction: String)

    private var rows: List<Row> = emptyList()

    fun update(newRows: List<Row>) {
        rows = newRows
        fireTableDataChanged()
    }

    fun clear() {
        rows = emptyList()
        fireTableDataChanged()
    }

    fun rowCount(): Int = rows.size
    fun offsetAt(index: Int): Int = rows[index].offset

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = COLUMNS.size
    override fun getColumnName(column: Int): String = COLUMNS[column]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            // Singleton opcodes have no recorded byte offset (see buildRows).
            0 -> if (row.offset >= 0) "0x${row.offset.toString(16)}" else ""
            1 -> row.instruction
            else -> ""
        }
    }
}

/**
 * Panel wrapping the "Wasm Disassembly" [JBTable], with a [highlight] method
 * that selects and scrolls to the row whose offset range covers [pauseOffset].
 */
internal class WasmDisassemblyPanel(val model: WasmDisassemblyTableModel) {

    val table = JBTable(model).apply {
        setShowGrid(true)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 90
        columnModel.getColumn(1).preferredWidth = 400
    }

    val component: JPanel = JPanel(java.awt.BorderLayout()).also {
        it.add(JBScrollPane(table), java.awt.BorderLayout.CENTER)
    }

    /**
     * Select and scroll to the row whose offset is the largest not exceeding
     * [pauseOffset]. Rows without a recorded offset (singleton opcodes, offset -1)
     * are skipped; a linear scan is fine at pause frequency for one function body.
     */
    fun highlight(pauseOffset: Int) {
        var best = -1
        for (i in 0 until model.rowCount()) {
            val offset = model.offsetAt(i)
            if (offset in 0..pauseOffset) best = i
            if (offset > pauseOffset) break
        }
        if (best < 0) return
        table.setRowSelectionInterval(best, best)
        table.scrollRectToVisible(table.getCellRect(best, 0, true))
    }
}

// ---------------------------------------------------------------------------
// Wasm binary disassembly engine
// ---------------------------------------------------------------------------

/**
 * Lazily parses wasm function bodies on first access and caches the resulting
 * [WasmDisassemblyTableModel.Row] lists by function index (code-section local).
 *
 * Design constraints (50MB / 14k-function modules):
 * - The code-section index ([CodeEntryOffset] list) is scanned once at init.
 * - Individual function bodies are parsed on demand via [parseCodeEntry].
 * - Results are cached in [cache] so repeated stepping inside one function
 *   is a simple lookup.
 *
 * @param wasmFilePath absolute path to the wasm file; if null all queries return empty
 */
internal class WasmDisassemblySource(wasmFilePath: String?) {

    private val LOG = Logger.getInstance(WasmDisassemblySource::class.java)

    /** Sorted list of (codeSection-local) function offsets, index = local func idx (0-based). */
    private val offsets: List<CodeEntryOffset>?

    /** The raw bytes, kept only as long as [offsets] is non-null and the cache may need fills. */
    private val wasmBytes: ByteArray?

    /** Cached disassembly rows per code-section-local function index. */
    private val cache = ConcurrentHashMap<Int, List<WasmDisassemblyTableModel.Row>>()

    /**
     * Number of imported functions (code-section indices are shifted by this value to get
     * the module-level function index; for offset look-ups we only need local indices).
     */
    private val importFuncCount: Int

    /**
     * Function names from the name custom section, keyed by GLOBAL function
     * index (imports first, then defined functions). Null when the module has
     * no name section (stripped builds) — calls then render numerically.
     */
    private val funcNames: Map<Int, String>?

    /** Symbolic operand resolver passed to [watFor], same contract as the WAT view. */
    private val watSymbols = WatSymbolNames { funcIdx -> funcNames?.get(funcIdx) }

    init {
        var tempOffsets: List<CodeEntryOffset>? = null
        var tempBytes: ByteArray? = null
        var tempImportCount = 0
        var tempFuncNames: Map<Int, String>? = null
        if (wasmFilePath != null) {
            try {
                val file = File(wasmFilePath)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    val buf = bytes.toCommonByteBuffer()
                    val result = WasmParser().parse(buf.duplicate()) as? WasmParseSuccess
                    val module = result?.wasmModule as? WasmModule
                    if (module != null) {
                        val codeSection = module.codeSection
                        if (codeSection != null) {
                            tempOffsets = with(codeSection) { buf.duplicate().scanOffsets() }
                        }
                        // Count imported functions to translate module-absolute offsets
                        val importSection = module.importSection
                        if (importSection != null) {
                            val imports = with(importSection) { buf.duplicate().getImportRecords() }
                            tempImportCount = imports.count { it.type is WasmTypeIndexExternalType }
                        }
                        tempFuncNames = with(module) {
                            getCustomSectionByName("name")?.let { customSection ->
                                with(customSection) { buf.duplicate().parseAsNameSection() }
                            }
                        }?.functionSubsection?.namemap
                        tempBytes = bytes
                    }
                }
            } catch (e: Exception) {
                LOG.warn("WasmDisassemblySource: failed to init from $wasmFilePath", e)
            }
        }
        offsets = tempOffsets
        wasmBytes = tempBytes
        importFuncCount = tempImportCount
        funcNames = tempFuncNames
    }

    /** Returns true if the wasm binary was successfully indexed. */
    fun isAvailable(): Boolean = offsets != null

    /**
     * Returns disassembly rows for the function containing [moduleAbsoluteOffset], or null
     * if the offset is not in the code section or the binary is unavailable.
     *
     * Also returns the code-section-local function index via [localFuncIndexOut] (if provided)
     * so the caller can use it for cache look-up checks.
     */
    fun rowsForOffset(moduleAbsoluteOffset: Int): Pair<List<WasmDisassemblyTableModel.Row>, Int>? {
        val offs = offsets ?: return null
        val bytes = wasmBytes ?: return null
        if (offs.isEmpty()) return null

        // Binary-search for the code entry whose range covers moduleAbsoluteOffset.
        // Each CodeEntryOffset.offset is the start of the LEB128-size prefix (the full entry).
        var lo = 0; var hi = offs.size - 1; var localIdx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entry = offs[mid]
            val entryEnd = entry.offset + entry.size
            when {
                moduleAbsoluteOffset < entry.offset -> hi = mid - 1
                moduleAbsoluteOffset >= entryEnd -> lo = mid + 1
                else -> { localIdx = mid; break }
            }
        }
        if (localIdx < 0) return null

        val rows = cache.getOrPut(localIdx) {
            buildRows(bytes, offs[localIdx], localIdx)
        }
        return rows to localIdx
    }

    private fun buildRows(bytes: ByteArray, entry: CodeEntryOffset, localIdx: Int): List<WasmDisassemblyTableModel.Row> {
        return try {
            val buf = bytes.toCommonByteBuffer()
            val record = buf.parseCodeEntry(entry.offset)
            val rows = mutableListOf<WasmDisassemblyTableModel.Row>()
            appendRows(record.opCodes, 0, rows)
            rows
        } catch (e: Exception) {
            LOG.debug("WasmDisassemblySource: failed to parse func localIdx=$localIdx", e)
            emptyList()
        }
    }

    /**
     * Emits one row per instruction using the same [watFor] mnemonic renderer as
     * the virtualized WAT view, recursing into block bodies (the parser nests
     * them inside Block/Loop/If/Else and TryTable rather than keeping the list
     * flat) with two-space indentation and a synthetic `end` row per block.
     *
     * Opcodes with immediates carry their real byte offset; singleton
     * zero-operand opcodes are shared object instances whose inherited offset
     * defaults to 0 and means nothing — they get a blank offset cell.
     */
    private fun appendRows(ops: List<OpCode>, depth: Int, rows: MutableList<WasmDisassemblyTableModel.Row>) {
        val indent = "  ".repeat(depth)
        for (op in ops) {
            val offset = (op as? OpCodeWithOffset)?.offset?.takeIf { it > 0 } ?: -1
            val text = if (op is UnparsedOpCode) "<unparsed: ${op.e.message}>" else watFor(op, watSymbols)
            rows.add(WasmDisassemblyTableModel.Row(offset, indent + text))
            when (op) {
                is OpCodes.Flow.Block -> { // also Loop, If, Else (subclasses)
                    appendRows(op.value, depth + 1, rows)
                    rows.add(WasmDisassemblyTableModel.Row(-1, indent + "end"))
                }
                is OpCodes.Flow.TryTable -> {
                    appendRows(op.code, depth + 1, rows)
                    rows.add(WasmDisassemblyTableModel.Row(-1, indent + "end"))
                }
                else -> {}
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared session listener
// ---------------------------------------------------------------------------

/**
 * Registers a session listener on [session] that keeps the "Wasm Stack" and
 * "Wasm Disassembly" panels in sync with the current stack frame.
 *
 * All CDP calls run in [scope] (off-EDT). Table model updates are delivered
 * via [ApplicationManager.getApplication().invokeLater].
 */
internal fun registerDebugTabsListener(
    session: XDebugSession,
    scope: CoroutineScope,
    cdp: CdpClient,
    stackModel: WasmStackTableModel,
    disassemblyPanel: WasmDisassemblyPanel,
    disassemblySource: WasmDisassemblySource,
) {
    val LOG = Logger.getInstance("CdpDebuggerTabs")

    fun unwrapPhysical(frame: XStackFrame?): CdpStackFrame? = when (frame) {
        is CdpStackFrame -> frame
        is CdpInlinedStackFrame -> frame.physicalFrame
        else -> null
    }

    fun refreshForFrame(frame: XStackFrame?) {
        val physical = unwrapPhysical(frame) ?: run {
            ApplicationManager.getApplication().invokeLater { stackModel.clear(); disassemblyPanel.model.clear() }
            return
        }

        // ---- Wasm Stack ----
        scope.launch {
            try {
                val rows = mutableListOf<WasmStackTableModel.Row>()

                // V8 exposes the operand stack as a "stack" property (className
                // "Stack") inside a dedicated scope of type "wasm-expression-stack";
                // older layouts put it in the "local" scope. Search both.
                var stackObjectId: String? = null
                for (scopeEntry in physical.scopeChain) {
                    val scopeType = scopeEntry["type"]
                    if (scopeType != "wasm-expression-stack" && scopeType != "local") continue
                    val scopeObjectId = (scopeEntry["object"] as? Map<*, *>)?.get("objectId") as? String ?: continue
                    val propsResult = cdp.send("Runtime.getProperties", mapOf(
                        "objectId" to scopeObjectId,
                        "ownProperties" to true,
                    ))
                    @Suppress("UNCHECKED_CAST")
                    val props = propsResult["result"] as? List<Map<String, Any?>> ?: emptyList()
                    val stackProp = props.firstOrNull { prop ->
                        prop["name"] == "stack" &&
                            (prop["value"] as? Map<*, *>)?.get("className") == "Stack"
                    }
                    stackObjectId = (stackProp?.get("value") as? Map<*, *>)?.get("objectId") as? String
                    if (stackObjectId != null) break
                }
                run {
                    if (stackObjectId != null) {
                        val stackPropsResult = cdp.send("Runtime.getProperties", mapOf(
                            "objectId" to stackObjectId,
                            "ownProperties" to true,
                        ))
                        @Suppress("UNCHECKED_CAST")
                        val stackProps = stackPropsResult["result"] as? List<Map<String, Any?>> ?: emptyList()
                        for (entry in stackProps) {
                            val name = entry["name"] as? String ?: continue
                            val idx = name.toIntOrNull() ?: continue
                            @Suppress("UNCHECKED_CAST")
                            val value = entry["value"] as? Map<String, Any?> ?: continue
                            val type = value["description"] as? String ?: "?"
                            val resolved = CdpStackFrame.resolveWasmValue(value, cdp) ?: "?"
                            rows.add(WasmStackTableModel.Row(idx, type, resolved))
                        }
                        rows.sortBy { it.index }
                    }
                }

                ApplicationManager.getApplication().invokeLater { stackModel.update(rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("WasmStack: failed to refresh", e)
                ApplicationManager.getApplication().invokeLater { stackModel.clear() }
            }
        }

        // ---- Wasm Disassembly ----
        val scriptId = physical.scriptId
        val byteOffset = physical.columnNumber
        val isWasmFrame = scriptId != null && cdp.scripts[scriptId]?.url?.startsWith("wasm://") == true

        if (!isWasmFrame || !disassemblySource.isAvailable()) {
            ApplicationManager.getApplication().invokeLater { disassemblyPanel.model.clear() }
            return
        }

        scope.launch {
            try {
                val result = disassemblySource.rowsForOffset(byteOffset)
                ApplicationManager.getApplication().invokeLater {
                    if (result == null) {
                        disassemblyPanel.model.clear()
                    } else {
                        val (newRows, _) = result
                        disassemblyPanel.model.update(newRows)
                        disassemblyPanel.highlight(byteOffset)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("WasmDisassembly: failed to refresh", e)
                ApplicationManager.getApplication().invokeLater { disassemblyPanel.model.clear() }
            }
        }
    }

    session.addSessionListener(object : XDebugSessionListener {
        override fun sessionPaused() {
            refreshForFrame(session.currentStackFrame)
        }

        override fun stackFrameChanged() {
            refreshForFrame(session.currentStackFrame)
        }

        override fun sessionResumed() {
            ApplicationManager.getApplication().invokeLater {
                stackModel.clear()
                disassemblyPanel.model.clear()
            }
        }
    })
}
