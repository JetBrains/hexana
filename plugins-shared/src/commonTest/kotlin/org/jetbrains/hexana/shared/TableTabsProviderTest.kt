package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableTabsProviderTest {

    // -----------------------------------------------------------------------
    // Helpers: minimal wasm binary builders
    // -----------------------------------------------------------------------

    private fun wasmHeader(): WasmBinaryWriter {
        val w = WasmBinaryWriter()
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D)) // magic
        w.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00)) // version 1
        return w
    }

    /** Minimal valid wasm module (just header, no sections). */
    private fun buildEmptyWasm(): ByteArray = wasmHeader().toByteArray()

    /**
     * Builds a minimal wasm module with one exported function.
     * Type section: 1 func type () -> ()
     * Function section: 1 function referencing type 0
     * Export section: 1 export "hello" -> function 0
     * Code section: 1 minimal body
     */
    private fun buildWasmWithExport(): ByteArray {
        val w = wasmHeader()

        // Section 1: Type section
        val types = WasmBinaryWriter()
        types.writeULEB128(1)     // count = 1
        types.writeByte(0x60)     // func type
        types.writeULEB128(0)     // 0 params
        types.writeULEB128(0)     // 0 results
        w.writeSection(1, types.toByteArray())

        // Section 3: Function section
        val funcs = WasmBinaryWriter()
        funcs.writeULEB128(1)     // count = 1
        funcs.writeULEB128(0)     // type index 0
        w.writeSection(3, funcs.toByteArray())

        // Section 7: Export section
        val exports = WasmBinaryWriter()
        exports.writeULEB128(1)   // count = 1
        exports.writeName("hello")
        exports.writeByte(0x00)   // export kind: function
        exports.writeULEB128(0)   // function index 0
        w.writeSection(7, exports.toByteArray())

        // Section 10: Code section
        val code = WasmBinaryWriter()
        code.writeULEB128(1)      // count = 1
        val body = WasmBinaryWriter()
        body.writeULEB128(0)      // local decl count = 0
        body.writeByte(0x0B)      // end
        code.writeULEB128(body.size())
        code.writeBytes(body.toByteArray())
        w.writeSection(10, code.toByteArray())

        return w.toByteArray()
    }

    /**
     * Builds a minimal wasm module with one import.
     * Import section: 1 import "env" . "log" -> type 0
     */
    private fun buildWasmWithImport(): ByteArray {
        val w = wasmHeader()

        // Section 1: Type section
        val types = WasmBinaryWriter()
        types.writeULEB128(1)     // count = 1
        types.writeByte(0x60)     // func type
        types.writeULEB128(0)     // 0 params
        types.writeULEB128(0)     // 0 results
        w.writeSection(1, types.toByteArray())

        // Section 2: Import section
        val imports = WasmBinaryWriter()
        imports.writeULEB128(1)   // count = 1
        imports.writeName("env")
        imports.writeName("log")
        imports.writeByte(0x00)   // import kind: function
        imports.writeULEB128(0)   // type index 0
        w.writeSection(2, imports.toByteArray())

        return w.toByteArray()
    }

    /**
     * Builds a minimal wasm module with a data section.
     */
    private fun buildWasmWithData(): ByteArray {
        val w = wasmHeader()

        // Section 11: Data section (passive data segment)
        val data = WasmBinaryWriter()
        data.writeULEB128(1)      // count = 1
        data.writeULEB128(1)      // data segment type: 1 (passive)
        data.writeULEB128(5)      // byte length = 5
        data.writeBytes(byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F)) // "Hello"
        w.writeSection(11, data.toByteArray())

        // Data count section (section 12) required by some parsers
        val dataCount = WasmBinaryWriter()
        dataCount.writeULEB128(1)
        w.writeSection(12, dataCount.toByteArray())

        return w.toByteArray()
    }

    /**
     * Builds a minimal wasm module with a custom section.
     */
    private fun buildWasmWithCustom(): ByteArray {
        val w = wasmHeader()

        // Section 0: Custom section
        val custom = WasmBinaryWriter()
        custom.writeName("test-section")
        custom.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        w.writeSection(0, custom.toByteArray())

        return w.toByteArray()
    }

    /**
     * Builds a wasm module with function + type sections (for functions tab).
     */
    private fun buildWasmWithFunctions(): ByteArray {
        val w = wasmHeader()

        // Section 1: Type section
        val types = WasmBinaryWriter()
        types.writeULEB128(1)     // count = 1
        types.writeByte(0x60)     // func type
        types.writeULEB128(1)     // 1 param
        types.writeByte(0x7F)     // i32
        types.writeULEB128(1)     // 1 result
        types.writeByte(0x7F)     // i32
        w.writeSection(1, types.toByteArray())

        // Section 3: Function section
        val funcs = WasmBinaryWriter()
        funcs.writeULEB128(1)     // count = 1
        funcs.writeULEB128(0)     // type index 0
        w.writeSection(3, funcs.toByteArray())

        // Section 10: Code section
        val code = WasmBinaryWriter()
        code.writeULEB128(1)      // count = 1
        val body = WasmBinaryWriter()
        body.writeULEB128(0)      // local decl count = 0
        body.writeByte(0x20)      // local.get
        body.writeULEB128(0)      // index 0
        body.writeByte(0x0B)      // end
        code.writeULEB128(body.size())
        code.writeBytes(body.toByteArray())
        w.writeSection(10, code.toByteArray())

        return w.toByteArray()
    }

    // -----------------------------------------------------------------------
    // WasmTableTabsProvider tests
    // -----------------------------------------------------------------------

    @Test
    fun wasmProviderReturnsEmptyForGenericBinary() {
        val provider = GenericBinaryProvider(byteArrayOf(0, 1, 2, 3).toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        assertTrue(tabs.isEmpty())
    }

    @Test
    fun wasmProviderReturnsEmptyForEmptyModule() {
        val provider = WasmBinaryProvider(buildEmptyWasm().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        assertTrue(tabs.isEmpty(), "Empty module should produce no table tabs")
    }

    // -----------------------------------------------------------------------
    // WasmProfilerTabsProvider tests
    // -----------------------------------------------------------------------

    @Test
    fun profilerProviderReturnsEmptyForGenericBinary() {
        val provider = GenericBinaryProvider(byteArrayOf(0, 1, 2, 3).toCommonByteBuffer())
        val tabs = WasmProfilerTabsProvider().tableTabs(provider)
        assertTrue(tabs.isEmpty())
    }

    @Test
    fun wasmProviderReturnsExportsTab() {
        val provider = WasmBinaryProvider(buildWasmWithExport().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        val exportsTab = tabs.find { it.name == "Exports" }
        assertNotNull(exportsTab, "Should produce an Exports tab")
        assertEquals(1, exportsTab.rowCount)
        assertEquals(3, exportsTab.columns.size)
        assertEquals("Kind", exportsTab.columns[0].name)
        assertEquals("Name", exportsTab.columns[1].name)
        assertEquals("Signature", exportsTab.columns[2].name)

        val kindCell = exportsTab.columns[0].cellProvider(0)
        assertEquals("function", kindCell.representation)

        val nameCell = exportsTab.columns[1].cellProvider(0)
        assertEquals("hello", nameCell.representation)
    }

    @Test
    fun wasmProviderReturnsImportsTab() {
        val provider = WasmBinaryProvider(buildWasmWithImport().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        val importsTab = tabs.find { it.name == "Imports" }
        assertNotNull(importsTab, "Should produce an Imports tab")
        assertEquals(1, importsTab.rowCount)
        assertEquals(1, importsTab.columns.size)
        assertEquals("Data", importsTab.columns[0].name)
    }

    @Test
    fun wasmProviderReturnsFunctionsTab() {
        val provider = WasmBinaryProvider(buildWasmWithFunctions().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        val functionsTab = tabs.find { it.name == "Functions" }
        assertNotNull(functionsTab, "Should produce a Functions tab")
        assertEquals(1, functionsTab.rowCount)

        val sigColumn = functionsTab.columns.find { it.name == "Signature" }
        assertNotNull(sigColumn, "Functions tab should have a Signature column")

        val sigCell = sigColumn.cellProvider(0)
        assertTrue(sigCell.representation.contains("i32"), "Signature should mention i32")
    }

    @Test
    fun wasmProviderReturnsCustomTab() {
        val provider = WasmBinaryProvider(buildWasmWithCustom().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        val customTab = tabs.find { it.name == "Custom" }
        assertNotNull(customTab, "Should produce a Custom tab")
        assertEquals(1, customTab.rowCount)
        assertEquals(2, customTab.columns.size)
        assertEquals("Name", customTab.columns[0].name)
        assertEquals("Payload", customTab.columns[1].name)

        val nameCell = customTab.columns[0].cellProvider(0)
        assertEquals("test-section", nameCell.representation)
    }

    @Test
    fun wasmProviderExportsTabRowSelectionReturnsData() {
        val provider = WasmBinaryProvider(buildWasmWithExport().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        val exportsTab = tabs.find { it.name == "Exports" }
        assertNotNull(exportsTab)
        val selection = exportsTab.rowSelectionProvider(0)
        assertNotNull(selection, "Row selection should return offset/size data")
        assertTrue(selection.offset >= 0)
        assertTrue(selection.size > 0)
    }

    @Test
    fun wasmProviderExportsTabSignatureResolution() {
        val provider = WasmBinaryProvider(buildWasmWithExport().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        val exportsTab = tabs.find { it.name == "Exports" }
        assertNotNull(exportsTab)
        val sigColumn = exportsTab.columns.find { it.name == "Signature" }
        assertNotNull(sigColumn)
        val sigCell = sigColumn.cellProvider(0)
        assertEquals("() -> void", sigCell.representation)
    }

    @Test
    fun wasmProviderDoesNotReturnComponentTabs() {
        val provider = WasmBinaryProvider(buildWasmWithExport().toCommonByteBuffer())
        val tabs = WasmTableTabsProvider().tableTabs(provider)
        assertNull(tabs.find { it.name == "Modules" }, "Core Wasm should not have Modules tab")
    }

    @Test
    fun wasmExportsTabDefaultSortByIdAsc() {
        val provider = WasmBinaryProvider(buildWasmWithExport().toCommonByteBuffer())
        val tab = WasmTableTabsProvider().tableTabs(provider).find { it.name == "Exports" }
        assertNotNull(tab)
        assertEquals(TableTabDescriptor.ID_COLUMN, tab.defaultSortColumn)
        assertTrue(tab.defaultSortAscending)
    }

    @Test
    fun wasmImportsTabDefaultSortByIdAsc() {
        val provider = WasmBinaryProvider(buildWasmWithImport().toCommonByteBuffer())
        val tab = WasmTableTabsProvider().tableTabs(provider).find { it.name == "Imports" }
        assertNotNull(tab)
        assertEquals(TableTabDescriptor.ID_COLUMN, tab.defaultSortColumn)
        assertTrue(tab.defaultSortAscending)
    }

    @Test
    fun wasmDataTabHasNoDefaultSort() {
        val provider = WasmBinaryProvider(buildWasmWithData().toCommonByteBuffer())
        val tab = WasmTableTabsProvider().tableTabs(provider).find { it.name == "Data" }
        assertNotNull(tab)
        assertNull(tab.defaultSortColumn)
    }

    @Test
    fun wasmFunctionsTabHasNoDefaultSort() {
        val provider = WasmBinaryProvider(buildWasmWithFunctions().toCommonByteBuffer())
        val tab = WasmTableTabsProvider().tableTabs(provider).find { it.name == "Functions" }
        assertNotNull(tab)
        assertNull(tab.defaultSortColumn)
    }

    @Test
    fun wasmCustomTabHasNoDefaultSort() {
        val provider = WasmBinaryProvider(buildWasmWithCustom().toCommonByteBuffer())
        val tab = WasmTableTabsProvider().tableTabs(provider).find { it.name == "Custom" }
        assertNotNull(tab)
        assertNull(tab.defaultSortColumn)
    }

    // -----------------------------------------------------------------------
    // WasmComponentTableTabsProvider tests
    // -----------------------------------------------------------------------

    @Test
    fun componentProviderReturnsEmptyForGenericBinary() {
        val provider = GenericBinaryProvider(byteArrayOf(0, 1, 2, 3).toCommonByteBuffer())
        val tabs = WasmComponentTableTabsProvider().tableTabs(provider)
        assertTrue(tabs.isEmpty())
    }

    @Test
    fun componentProviderReturnsEmptyForCoreWasm() {
        val provider = WasmBinaryProvider(buildWasmWithExport().toCommonByteBuffer())
        val tabs = WasmComponentTableTabsProvider().tableTabs(provider)
        assertTrue(tabs.isEmpty(), "Component provider should ignore core Wasm modules")
    }

    @Test
    fun componentProviderReturnsComponentsTabForNestedComponent() {
        // Component header (\0asm, version=0x0D, layer=1) + an id-4 component section
        // whose content is itself a minimal component preamble.
        val componentHeader = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)
        val bytes = componentHeader +
            byteArrayOf(0x04, componentHeader.size.toByte()) + componentHeader
        val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())

        val tab = WasmComponentTableTabsProvider().tableTabs(provider).find { it.name == "Components" }
        assertNotNull(tab, "Expected a Components tab for a component with a nested component")
        assertEquals(1, tab.rowCount)
        // Section content starts after the 8-byte header + 2 bytes of section framing.
        assertEquals("component:@0000000a", tab.columns.single().cellProvider(0).representation)
    }

    @Test
    fun componentTabsRecurseIntoNestedComponent() {
        // Real artifact (wasm-tools parse): a component with one core module plus a
        // nested component that itself contains a core module exporting "inner".
        val bytes = (
            "0061736d0d00010001260061736d010000000105016000017f03020100070901056f7574657200000a0601040041010b" +
                "04300061736d0d00010001260061736d010000000105016000017f0302010007090105696e6e657200000a06010400412a0b"
            ).hexToByteArray()
        val provider = WasmComponentBinaryProvider(bytes.toCommonByteBuffer())

        val tabs = WasmComponentTableTabsProvider().tableTabs(provider)
        assertNotNull(tabs.find { it.name == "Modules" })
        assertNotNull(tabs.find { it.name == "Components" })

        // Slice the nested component the way the enricher's click handler does and
        // verify the nested view again offers its own openable Modules tab.
        val section = provider.wasmModule?.componentSection
        assertNotNull(section)
        val nested = WasmComponentBinaryProvider(provider.bytes.slice(section.contentOffset, section.contentSize))
        val nestedTabs = WasmComponentTableTabsProvider().tableTabs(nested)
        assertNotNull(nestedTabs.find { it.name == "Modules" }, "Nested component should expose its own Modules tab")
        assertNull(nestedTabs.find { it.name == "Components" }, "Nested component has no further nesting")
    }

    // -----------------------------------------------------------------------
    // NativeTableTabsProvider tests
    // -----------------------------------------------------------------------

    @Test
    fun nativeDebugContextDataIsScopedToTableTabsCall() {
        val tabsProvider = NativeTableTabsProvider()
        val providerA = emptyElfProvider(sourcePath = "/tmp/a")
        val providerB = emptyElfProvider(sourcePath = "/tmp/b")

        val injectedA = NativeDebugTabData(
            debugContext = debugContextWithCompanion(binaryPath = "/tmp/a", name = "build-id-a"),
            debugInfoRows = null,
        )
        val injectedB = NativeDebugTabData(
            debugContext = debugContextWithCompanion(binaryPath = "/tmp/b", name = "build-id-b"),
            debugInfoRows = null,
        )

        val tabA = tabsProvider.tableTabs(providerA, TableTabsContext(injectedA)).debugContextTab()
        assertEquals("build-id-a", tabA.columns.first { it.name == "Name" }.cellProvider(0).representation)

        val fallbackTab = tabsProvider.tableTabs(providerB).debugContextTab()
        assertEquals(1, fallbackTab.rowCount)
        assertEquals("DWARF sections", fallbackTab.columns.first { it.name == "Name" }.cellProvider(0).representation)

        val tabB = tabsProvider.tableTabs(providerB, TableTabsContext(injectedB)).debugContextTab()
        assertEquals("build-id-b", tabB.columns.first { it.name == "Name" }.cellProvider(0).representation)
    }

    // -----------------------------------------------------------------------
    // TabDescriptor structure tests
    // -----------------------------------------------------------------------

    @Test
    fun columnWidthTypesAreDistinct() {
        val fill = ColumnWidth.Fill()
        val fillFraction = ColumnWidth.Fill(0.5f)
        val measured = ColumnWidth.Measured("hello")
        val measuredMin = ColumnWidth.MeasuredMin("world")

        assertTrue(fill is ColumnWidth.Fill)
        assertEquals(1f, (fill as ColumnWidth.Fill).fraction)
        assertEquals(0.5f, (fillFraction as ColumnWidth.Fill).fraction)
        assertTrue(measured is ColumnWidth.Measured)
        assertEquals("hello", (measured as ColumnWidth.Measured).sample)
        assertTrue(measuredMin is ColumnWidth.MeasuredMin)
        assertEquals("world", (measuredMin as ColumnWidth.MeasuredMin).sample)
    }

    @Test
    fun cellValueDefaults() {
        val cell = CellValue(42)
        assertEquals(42, cell.value)
        assertEquals("42", cell.representation)
        assertNull(cell.tooltip)
    }

    @Test
    fun cellValueCustomRepresentation() {
        val cell = CellValue(42, "forty-two", "the answer minus one")
        assertEquals(42, cell.value)
        assertEquals("forty-two", cell.representation)
        assertEquals("the answer minus one", cell.tooltip)
    }

    @Test
    fun rowSelectionDataHoldsValues() {
        val data = RowSelectionData(100, 50)
        assertEquals(100, data.offset)
        assertEquals(50, data.size)
    }

    @Test
    fun tableTabDescriptorDefaultRowSelectionReturnsNull() {
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 0,
        )
        assertNull(tab.rowSelectionProvider(0))
    }

    @Test
    fun tableTabDescriptorDefaultSortIsNull() {
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 0,
        )
        assertNull(tab.defaultSortColumn)
        assertTrue(tab.defaultSortAscending)
    }

    @Test
    fun tableTabDescriptorWithExplicitDefaultSort() {
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 0,
            defaultSortColumn = TableTabDescriptor.ID_COLUMN,
            defaultSortAscending = false,
        )
        assertEquals(TableTabDescriptor.ID_COLUMN, tab.defaultSortColumn)
        assertEquals(false, tab.defaultSortAscending)
    }

    @Test
    fun idColumnSentinelIsMinusOne() {
        assertEquals(-1, TableTabDescriptor.ID_COLUMN)
    }

    // -----------------------------------------------------------------------
    // RowActionDescriptor tests
    // -----------------------------------------------------------------------

    @Test
    fun rowActionDescriptorHoldsValues() {
        var invokedIndex = -1
        val action = RowActionDescriptor(
            label = "Run",
            tooltipProvider = { index -> if (index == 0) "Run 'hello'" else null },
            action = { index -> invokedIndex = index },
        )
        assertEquals("Run", action.label)
        assertEquals("Run 'hello'", action.tooltipProvider(0))
        assertNull(action.tooltipProvider(1))
        action.action(42)
        assertEquals(42, invokedIndex)
    }

    // -----------------------------------------------------------------------
    // RowContextMenuItemDescriptor tests
    // -----------------------------------------------------------------------

    @Test
    fun rowContextMenuItemDescriptorHoldsValues() {
        var invokedIndex = -1
        val item = RowContextMenuItemDescriptor(
            labelProvider = { index -> "Navigate #$index" },
            action = { index -> invokedIndex = index },
        )
        assertEquals("Navigate #3", item.labelProvider(3))
        item.action(7)
        assertEquals(7, invokedIndex)
    }

    // -----------------------------------------------------------------------
    // TableTabDescriptor action properties tests
    // -----------------------------------------------------------------------

    @Test
    fun tableTabDescriptorDefaultActionsAreEmpty() {
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 0,
        )
        assertNull(tab.rowAction)
        assertTrue(tab.contextMenuItems.isEmpty())
        assertNull(tab.rowClickAction)
    }

    @Test
    fun tableTabDescriptorWithRowAction() {
        val action = RowActionDescriptor("Run", { "tooltip" }, { })
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 1,
            rowAction = action,
        )
        assertNotNull(tab.rowAction)
        assertEquals("Run", tab.rowAction!!.label)
    }

    @Test
    fun tableTabDescriptorWithContextMenuItems() {
        val items = listOf(
            RowContextMenuItemDescriptor({ "Item 1" }, { }),
            RowContextMenuItemDescriptor({ "Item 2" }, { }),
        )
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 1,
            contextMenuItems = items,
        )
        assertEquals(2, tab.contextMenuItems.size)
        assertEquals("Item 1", tab.contextMenuItems[0].labelProvider(0))
        assertEquals("Item 2", tab.contextMenuItems[1].labelProvider(0))
    }

    @Test
    fun tableTabDescriptorWithRowClickAction() {
        var clicked = -1
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 1,
            rowClickAction = { index -> clicked = index },
        )
        assertNotNull(tab.rowClickAction)
        tab.rowClickAction!!(5)
        assertEquals(5, clicked)
    }

    @Test
    fun tableTabDescriptorRowClickActionOverridesSelection() {
        var clicked = -1
        val tab = TableTabDescriptor(
            name = "test",
            columns = emptyList(),
            rowCount = 1,
            rowSelectionProvider = { RowSelectionData(100, 50) },
            rowClickAction = { index -> clicked = index },
        )
        // Both are set; the UI layer should prefer rowClickAction when present
        assertNotNull(tab.rowSelectionProvider(0))
        assertNotNull(tab.rowClickAction)
        tab.rowClickAction!!(0)
        assertEquals(0, clicked)
    }

    private fun emptyElfProvider(sourcePath: String): ElfBinaryProvider =
        ElfBinaryProvider(
            sharedBytes = ByteArray(16).toCommonByteBuffer(),
            nativeFormat = NativeBinaryFormat.Elf(
                wordSize = NativeBinaryWordSize.Bits64,
                endian = NativeBinaryEndian.Little,
                fileType = null,
                machine = null,
            ),
            sourcePath = sourcePath,
        )

    private fun debugContextWithCompanion(binaryPath: String, name: String): NativeDebugContext =
        NativeDebugContext(
            binaryPath = binaryPath,
            embeddedDebugInfoPresent = false,
            elfBuildId = name,
            gnuDebugLink = null,
            machOUuid = null,
            pePdbPath = null,
            peCodeViewId = null,
            companions = listOf(
                NativeDebugCompanion(
                    kind = NativeDebugCompanionKind.ElfBuildId,
                    status = NativeDebugCompanionStatus.Found,
                    name = name,
                    location = ".build-id/${name.take(2)}/${name.drop(2)}.debug",
                    description = "Injected test companion.",
                )
            ),
        )

    private fun List<TableTabDescriptor>.debugContextTab(): TableTabDescriptor {
        val tab = find { it.name == "Debug Context" }
        assertNotNull(tab, "Expected Debug Context tab")
        return tab
    }
}
