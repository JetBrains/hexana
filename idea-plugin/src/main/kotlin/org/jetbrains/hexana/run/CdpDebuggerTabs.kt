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
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.WasmDebugModules
import org.jetbrains.hexana.shared.WasmStackShapeAnalyzer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
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
    fun instructionAt(index: Int): String = rows[index].instruction

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
     * Select and scroll to the row whose offset matches [pauseOffset] exactly; if no
     * exact match exists (offset deduction failed for that byte), fall back to the row
     * whose offset is the largest not exceeding [pauseOffset]. Rows without a recorded
     * offset (deduction failed, offset -1) are skipped in both passes.
     */
    fun highlight(pauseOffset: Int) {
        // First pass: exact match wins.
        for (i in 0 until model.rowCount()) {
            if (model.offsetAt(i) == pauseOffset) {
                table.setRowSelectionInterval(i, i)
                table.scrollRectToVisible(table.getCellRect(i, 0, true))
                return
            }
        }
        // Fallback: nearest offset at or below pauseOffset.
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
// "Wasm" tab state
// ---------------------------------------------------------------------------

/**
 * State-only view of the "Wasm" debugger tab, shared by every debug process that
 * shows it (CDP, wasmtime, WAMR). Exposes no controls: it reports what the tab
 * currently presents so the packaged UI-driver probe can assert on it.
 *
 * [selectedOffset] is `-1` and [selectedInstruction] empty when no row is
 * highlighted (no pause yet, resumed session, or a non-wasm frame).
 */
internal data class WasmDebuggerTabState(
    /** False when the project has no live debug session (none started, or already stopped). */
    val sessionActive: Boolean,
    val sessionSuspended: Boolean,
    /** True once the tab has been added to the debugger layout. */
    val tabRegistered: Boolean,
    /** True while the tab is the selected content; switches stepping to instruction granularity. */
    val tabSelected: Boolean,
    val disassemblyRowCount: Int,
    val selectedRow: Int,
    val selectedOffset: Int,
    val selectedInstruction: String,
    val operandStackRowCount: Int,
) {
    companion object {
        /** State for a project without a live debug session. */
        val NO_SESSION = WasmDebuggerTabState(
            sessionActive = false,
            sessionSuspended = false,
            tabRegistered = false,
            tabSelected = false,
            disassemblyRowCount = 0,
            selectedRow = -1,
            selectedOffset = -1,
            selectedInstruction = "",
            operandStackRowCount = 0,
        )
    }
}

/**
 * Reads the current [WasmDebuggerTabState] off the tab's models. Must be called
 * on the EDT: [WasmDisassemblyPanel.table] selection is Swing state.
 */
internal fun wasmDebuggerTabStateOf(
    sessionSuspended: Boolean,
    tabRegistered: Boolean,
    tabSelected: Boolean,
    disassemblyPanel: WasmDisassemblyPanel,
    stackModel: WasmStackTableModel,
): WasmDebuggerTabState {
    val rowCount = disassemblyPanel.model.rowCount()
    val selectedRow = disassemblyPanel.table.selectedRow
    val highlighted = selectedRow in 0 until rowCount
    return WasmDebuggerTabState(
        sessionActive = true,
        sessionSuspended = sessionSuspended,
        tabRegistered = tabRegistered,
        tabSelected = tabSelected,
        disassemblyRowCount = rowCount,
        selectedRow = selectedRow,
        selectedOffset = if (highlighted) disassemblyPanel.model.offsetAt(selectedRow) else -1,
        selectedInstruction = if (highlighted) disassemblyPanel.model.instructionAt(selectedRow) else "",
        operandStackRowCount = stackModel.rowCount,
    )
}

// ---------------------------------------------------------------------------
// Wasm binary disassembly engine
// ---------------------------------------------------------------------------

/**
 * The disassembly and operand-stack analysis of the core module a stop landed in.
 *
 * @property moduleId the resolved module's id (0 for a plain core module, the
 *           component `module` section index otherwise).
 * @property rows the disassembly of the function covering the queried offset.
 * @property stackAnalyzer that module's operand-stack analyzer, or null when the
 *           reconstruction is not available for it.
 */
internal class WasmStopAnalysis(
    val moduleId: Int,
    val rows: List<WasmDisassemblyTableModel.Row>,
    val stackAnalyzer: WasmStackShapeAnalyzer?,
)

internal class WasmDisassemblySource(wasmBuffer: CommonByteBuffer?) {

    private val LOG = Logger.getInstance(WasmDisassemblySource::class.java)

    /**
     * The core modules a stop can land in — the binary itself when it is a core
     * module, every embedded core module when it is a component. Null when the
     * buffer is absent or nothing in it could be indexed.
     */
    private val modules: WasmDebugModules? = wasmBuffer?.let {
        WasmDebugModules.create(it).also { indexed ->
            if (indexed == null) LOG.warn("WasmDisassemblySource: failed to init from buffer")
        }
    }

    /** Cached disassembly rows, keyed by module id in the high 32 bits and function index in the low. */
    private val cache = ConcurrentHashMap<Long, List<WasmDisassemblyTableModel.Row>>()

    /** Returns true if at least one core module of the wasm binary was successfully indexed. */
    fun isAvailable(): Boolean = modules != null

    /** True when the executed binary is a component, so offsets are per embedded core module. */
    fun isComponent(): Boolean = modules?.isComponent == true

    /**
     * Analyses a stop reported as [moduleId] (null when the translation path has no
     * module id) plus [offset], a byte offset in that module's own offset space.
     *
     * Returns null when no indexed module has a function body covering the offset.
     */
    fun analyse(moduleId: Int?, offset: Int): WasmStopAnalysis? {
        val modules = this.modules ?: return null
        val module = modules.moduleFor(moduleId, offset) ?: return null
        // Resolve the cache key first: parsing the body and only then looking it up
        // would make the cache pure overhead.
        val localIdx = module.disassembly.functionIndexForOffset(offset)
        if (localIdx < 0) return null
        val key = (module.moduleId.toLong() shl 32) or localIdx.toLong()
        val rows = cache.getOrPut(key) {
            module.disassembly.disassembleFunction(localIdx)
                .map { WasmDisassemblyTableModel.Row(it.offset, it.instruction) }
                .also {
                    if (it.isEmpty()) {
                        LOG.debug("WasmDisassemblySource: failed to parse module=${module.moduleId} func localIdx=$localIdx")
                    }
                }
        }
        return WasmStopAnalysis(module.moduleId, rows, module.stackAnalyzer)
    }
}

/**
 * Maps [path] read-only into a [CommonByteBuffer], or null when [path] is
 * null, missing, or cannot be mapped. All consumers read through duplicates,
 * so the module is never materialized as a byte-array copy.
 */
internal fun mapWasmFileToBuffer(path: String?): CommonByteBuffer? {
    if (path == null) return null
    return try {
        val mapped = FileChannel.open(java.nio.file.Path.of(path), StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
        mapped.toCommonByteBuffer()
    } catch (e: Exception) {
        Logger.getInstance("CdpDebuggerTabs").debug("Failed to map wasm file $path", e)
        null
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
                // V8 reports the offset within the wasm script it is executing and has
                // no module-id notion of its own (it never runs components), so the
                // module is resolved from the offset alone.
                val result = disassemblySource.analyse(moduleId = null, offset = byteOffset)
                ApplicationManager.getApplication().invokeLater {
                    if (result == null) {
                        disassemblyPanel.model.clear()
                    } else {
                        disassemblyPanel.model.update(result.rows)
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
