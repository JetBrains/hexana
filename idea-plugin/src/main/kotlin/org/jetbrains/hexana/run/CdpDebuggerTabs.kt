package org.jetbrains.hexana.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.parseCodeEntry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
 * Creates the "Wasm Stack" panel containing a [JBTable] that shows the
 * wasm operand-stack slots (index, type, value) for the currently paused frame.
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

    init {
        var tempOffsets: List<CodeEntryOffset>? = null
        var tempBytes: ByteArray? = null
        var tempImportCount = 0
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
            // Emit one row per instruction, in parse order. Opcodes with immediates
            // carry their real byte offset; singleton zero-operand opcodes (I32.Add,
            // Drop, Return, …) are shared object instances whose inherited offset
            // defaults to 0 and means nothing — show them with a blank offset cell
            // rather than dropping them from the listing.
            record.opCodes.map { opCode ->
                val offset = (opCode as? OpCodeWithOffset)?.offset?.takeIf { it > 0 } ?: -1
                WasmDisassemblyTableModel.Row(offset, opcodeText(opCode))
            }
        } catch (e: Exception) {
            LOG.debug("WasmDisassemblySource: failed to parse func localIdx=$localIdx", e)
            emptyList()
        }
    }

    /** Converts an [OpCode] to a readable WAT-style string. */
    private fun opcodeText(opCode: OpCode): String {
        return when (opCode) {
            is UnparsedOpCode -> "<unparsed: ${opCode.e.message}>"
            else -> {
                // Use the Kotlin class simple name; it matches the WAT mnemonic closely
                // (e.g. "I32.Add" -> "i32.add", "F64.Load" -> "f64.load").
                val rawName = opCode::class.simpleName ?: opCode::class.qualifiedName ?: "unknown"
                // Prepend the enclosing object name for nested types (e.g. I32.Add)
                val qualified = opCode::class.qualifiedName ?: rawName
                // Extract last two segments (e.g. "I32.Add" from "...opcodes.numeric.I32.Add")
                val segments = qualified.split(".")
                val displayName = if (segments.size >= 2) {
                    val last = segments[segments.size - 1]
                    val secondLast = segments[segments.size - 2]
                    // Only join if secondLast looks like a type prefix (I32, I64, F32, F64, V128, etc.)
                    if (secondLast.matches(Regex("[A-Z][0-9A-Za-z]+"))) {
                        "${secondLast.lowercase()}.${camelToSnake(last)}"
                    } else {
                        camelToSnake(last)
                    }
                } else camelToSnake(rawName)
                // Append immediates
                val immediates = immediatesText(opCode)
                if (immediates.isNotEmpty()) "$displayName $immediates" else displayName
            }
        }
    }

    private fun camelToSnake(name: String): String =
        name.replace(Regex("([A-Z])"), "_$1").trimStart('_').lowercase()
            .replace("__", "_")

    @Suppress("UNCHECKED_CAST")
    private fun immediatesText(opCode: OpCode): String {
        val withVal = opCode as? OpCodeWithValue<*> ?: return ""
        return withVal.value?.toString() ?: ""
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

                // Find the local scope
                val localScope = physical.scopeChain.firstOrNull { it["type"] == "local" }
                val localObjectId = (localScope?.get("object") as? Map<*, *>)?.get("objectId") as? String
                if (localObjectId != null) {
                    val propsResult = cdp.send("Runtime.getProperties", mapOf(
                        "objectId" to localObjectId,
                        "ownProperties" to true,
                    ))
                    @Suppress("UNCHECKED_CAST")
                    val props = propsResult["result"] as? List<Map<String, Any?>> ?: emptyList()

                    // Find the "stack" property with className "Stack"
                    val stackProp = props.firstOrNull { prop ->
                        prop["name"] == "stack" &&
                            (prop["value"] as? Map<*, *>)?.get("className") == "Stack"
                    }
                    val stackObjectId = (stackProp?.get("value") as? Map<*, *>)?.get("objectId") as? String
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
