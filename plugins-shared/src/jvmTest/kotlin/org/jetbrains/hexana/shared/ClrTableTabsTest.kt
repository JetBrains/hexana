package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CLR tabs over the committed `parsing/native-fixtures/test-data/pe/sample-clr.exe` (see `src/clr/Sample.cs`). */
class ClrTableTabsTest {
    private fun provider(path: String): PeBinaryProvider {
        val file = File("../parsing/native-fixtures/test-data/$path").also { assertTrue(it.exists(), "missing fixture ${it.absolutePath}") }
        return assertIs<PeBinaryProvider>(createBinaryProvider(file.readBytes().toCommonByteBuffer(), file.name))
    }

    private val managed = provider("pe/sample-clr.exe")
    private val tabsProvider = NativeTableTabsProvider()

    private fun TableTabDescriptor.cell(row: Int, column: String): String =
        columns.single { it.name == column }.cellProvider(row).representation

    private fun TableTabDescriptor.rowWhere(column: String, value: String): Int =
        (0 until rowCount).single { cell(it, column) == value }

    @Test
    fun `managed PE gets CLR tabs after the PE headers`() {
        val names = tabsProvider.tableTabs(managed).map { it.name }
        assertEquals(listOf("Headers", ".NET", "Types", "Methods", "Assembly Refs"), names.take(5))
        assertEquals(
            listOf("Streams", "Tables", "Type Refs", "Member Refs", "User Strings", "Resources"),
            tabsProvider.groupedTabs(managed).single { it.name == ".NET Metadata" }.innerTabs.map { it.name },
        )
        assertEquals("PE32 (.NET)", fileTypeDescription(managed))
        // IL-only and not ReadyToRun: hosts drop the native disassembly views for it.
        assertTrue(managed.isPureIl)
    }

    @Test
    fun `native PE gets no CLR tabs`() {
        val native = provider("pe/sample.exe")
        assertFalse(native.isManaged)
        assertFalse(native.isPureIl)
        assertTrue(tabsProvider.tableTabs(native).none { it.name == ".NET" || it.name == "Types" })
        assertEquals(emptyList(), tabsProvider.groupedTabs(native))
        assertEquals("PE32+", fileTypeDescription(native))
    }

    @Test
    fun `overview summarizes the assembly`() {
        val overview = tabsProvider.tableTabs(managed).single { it.name == ".NET" }
        fun field(name: String) = overview.cell(overview.rowWhere("Field", name), "Value")
        assertEquals("AnyCPU", field("Platform"))
        assertEquals("Hexana.Sample.Program::Main", field("Entry Point"))
        assertEquals(".NETFramework,Version=v4.8", field("Target Framework"))
        assertEquals("sample-clr", field("Assembly"))
        assertEquals("1.2.3.4", field("Assembly Version"))
        assertEquals("v4.0.30319", field("Metadata Version"))
        assertEquals("SHA1 (0x8004)", field("Hash Algorithm"))
        assertEquals("8", field("Types"))
    }

    @Test
    fun `types tab lists members as children and selects their bytes`() {
        val types = tabsProvider.tableTabs(managed).single { it.name == "Types" }
        val box = types.rowWhere("Type", "Hexana.Sample.Box<T>")
        assertEquals("class", types.cell(box, "Kind"))
        assertEquals("public", types.cell(box, "Modifiers"))
        assertEquals("public static", types.cell(types.rowWhere("Type", "Hexana.Sample.Program"), "Modifiers"))
        assertEquals("enum", types.cell(types.rowWhere("Type", "Hexana.Sample.Color"), "Kind"))

        val members = assertNotNull(assertNotNull(types.childrenProvider)(box))
        val signatures = (0 until members.rowCount).map { members.columns[1].cellProvider(it).representation }
        assertEquals(listOf("T Value", "U Map<U>(Func<T, U> map)", "void .ctor()"), signatures)
        assertEquals("public", members.columns[3].cellProvider(0).representation)

        val method = managed.clrMetadata!!.methods.single { it.name == "Map" }
        val selection = assertNotNull(members.rowSelectionProvider(1))
        assertEquals(method.body!!.range.offset, selection.offset)
        assertEquals(method.body!!.range.size, selection.size)
    }

    @Test
    fun `methods and references render tokens and modifiers`() {
        val methods = tabsProvider.tableTabs(managed).single { it.name == "Methods" }
        val main = methods.rowWhere("Method", "int Main(string[] args)")
        assertEquals("0x06000009", methods.cell(main, "Token"))
        assertEquals("public static", methods.cell(main, "Modifiers"))
        assertEquals("Hexana.Sample.Program", methods.cell(main, "Type"))
        val greet = methods.rowWhere("Token", "0x06000001")
        assertEquals("public abstract", methods.cell(greet, "Modifiers"))
        assertEquals("", methods.cell(greet, "IL Size"))

        val refs = tabsProvider.tableTabs(managed).single { it.name == "Assembly Refs" }
        assertEquals("B77A5C561934E089", refs.cell(0, "Public Key Token"))

        val group = tabsProvider.groupedTabs(managed).single()
        val strings = group.innerTabs.single { it.name == "User Strings" }
        assertTrue((0 until strings.rowCount).any { strings.cell(it, "Value") == "Hello" })
        val resources = group.innerTabs.single { it.name == "Resources" }
        assertEquals("Hexana.Sample.hello.txt", resources.cell(0, "Name"))
        assertEquals("18", resources.cell(0, "Size"))
    }

    @Test
    fun `size analysis nests CLR metadata under its section`() {
        val text = assertNotNull(managed.sizeAnalysis).children.single { it.name == ".text" }
        val metadata = text.children.single { it.name == "CLR metadata" }
        assertEquals(listOf("#~", "#Strings", "#US", "#GUID", "#Blob"), metadata.children.map { it.name })
        assertEquals(listOf("Hexana.Sample.hello.txt"), text.children.single { it.name == "Managed resources" }.children.map { it.name })
    }
}
