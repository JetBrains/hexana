package org.jetbrains.hexana.shared.diff

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.io.toCommonByteBuffer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * .NET assembly diff over `pe/sample-clr.exe` (base, `src/clr/Sample.cs`) and `pe/sample-clr-v2.exe`
 * (head, `src/clr/diff/SampleV2.cs`).
 */
class ClrDiffProviderTest {

    private fun fixture(path: String): ByteArray =
        File("../parsing/native-fixtures/test-data/$path").also { assertTrue(it.exists(), "missing fixture ${it.absolutePath}") }.readBytes()

    private val base by lazy { fixture("pe/sample-clr.exe") }
    private val head by lazy { fixture("pe/sample-clr-v2.exe") }

    private fun input(left: ByteArray, right: ByteArray) =
        BinaryDiffInput(left.toCommonByteBuffer(), right.toCommonByteBuffer(), "base", "head")

    private fun session(left: ByteArray = base, right: ByteArray = head) = ClrDiffProvider().session(input(left, right))

    private suspend fun tab(session: BinaryDiffSession, name: String, ctx: DiffTabContext = DiffTabContext()) =
        session.tabs.single { it.name == name }.compute(ctx)

    /** The table's rows as `name | change | …detail` strings. */
    private fun DiffTabContent.rows(): Map<String, String> {
        val table = assertNotNull(table, emptyMessage)
        val detail = table.columns.last()
        return (0 until table.rowCount).associate { i ->
            table.columns[0].cellProvider(i).representation to "${table.columns[1].cellProvider(i).representation} | ${detail.cellProvider(i).representation}"
        }
    }

    @Test
    fun registryPicksClrProviderForTwoAssembliesOnly() {
        assertIs<ClrDiffProvider>(BinaryDiffProviderRegistry.forInput(input(base, head)))
        val native = fixture("pe/sample.exe")
        assertIs<NativeDiffProvider>(BinaryDiffProviderRegistry.forInput(input(base, native)))
        assertIs<NativeDiffProvider>(BinaryDiffProviderRegistry.forInput(input(native, native)))
    }

    @Test
    fun sessionHasSizeTypesAndMethodsTabs() {
        val s = session()
        assertEquals(listOf("Size Impact", "Types", "Methods"), s.tabs.map { it.name })
        assertNotNull(s.detail)
    }

    @Test
    fun identicalAssembliesHaveNoChanges() = runTest {
        val s = session(base, base)
        assertNull(tab(s, "Methods").table)
        assertNull(tab(s, "Types").table)
    }

    @Test
    fun methodsTabClassifiesIlChanges() = runTest {
        val content = tab(session(), "Methods")
        val rows = content.rows()
        assertTrue(rows.getValue("Hexana.Sample.Greeter::string Greet(string name)").startsWith("Modified"), rows.toString())
        assertTrue(rows.getValue("Hexana.Sample.Program::string Describe(int value)").startsWith("Modified"), rows.toString())
        assertTrue(rows.getValue("Hexana.Sample.Greeter::string get_Prefix()").startsWith("Removed"), rows.toString())
        assertTrue(rows.keys.any { it.startsWith("Hexana.Sample.Farewell::") && rows.getValue(it).startsWith("Added") }, rows.toString())
        // Main's IL bytes change (tokens shift with the new type and strings), its rendered IL does not.
        assertTrue(rows.keys.none { it.startsWith("Hexana.Sample.Program::int Main") }, rows.toString())
        assertTrue(content.header.any { "only in metadata tokens" in it.text }, content.header.joinToString { it.text })
    }

    @Test
    fun typesTabSummarizesMemberChanges() = runTest {
        val content = tab(session(), "Types")
        val rows = content.rows()
        assertTrue(rows.getValue("Hexana.Sample.Farewell").startsWith("Added"), rows.toString())
        assertTrue(rows.getValue("Hexana.Sample.Color").startsWith("Modified | declaration or fields changed"), rows.toString())
        assertTrue(rows.getValue("Hexana.Sample.Greeter").contains("methods -1 ~1"), rows.toString())
        assertTrue("Hexana.Sample.Box<T>" !in rows, rows.toString())
        assertTrue(content.header.any { "1.2.3.4" in it.text && "1.2.4.0" in it.text }, content.header.joinToString { it.text })
    }

    @Test
    fun detailShowsMethodIlSideBySide() = runTest {
        val s = session()
        var selected: DiffItemRef? = null
        val content = tab(s, "Methods", DiffTabContext(select = { selected = it }))
        val table = content.table!!
        val row = (0 until table.rowCount).first { table.columns[0].cellProvider(it).representation.endsWith("Greet(string name)") }
        table.rowClickAction!!(row)
        val diff = s.detail!!.render(selected!!)
        assertTrue(diff.title.startsWith("Hexana.Sample.Greeter::string Greet(string name)"), diff.title)
        assertTrue(diff.rows.any { it.leftRemoved && it.left!!.contains("\", \"") }, diff.rows.joinToString("\n"))
        assertTrue(diff.rows.any { it.rightAdded && it.right!!.contains("\", dear \"") }, diff.rows.joinToString("\n"))
        // The method header lines up despite the RVA moving.
        assertTrue(diff.rows.any { !it.leftRemoved && !it.rightAdded && it.left!!.startsWith(".method") }, diff.rows.joinToString("\n"))
    }

    @Test
    fun detailShowsWholeTypeForTypeRows() = runTest {
        val s = session()
        var selected: DiffItemRef? = null
        val table = tab(s, "Types", DiffTabContext(select = { selected = it })).table!!
        val row = (0 until table.rowCount).first { table.columns[0].cellProvider(it).representation == "Hexana.Sample.Color" }
        table.rowClickAction!!(row)
        val diff = s.detail!!.render(selected!!)
        assertEquals("Hexana.Sample.Color", diff.title)
        assertTrue(diff.rows.any { it.rightAdded && it.right!!.contains("Alpha") }, diff.rows.joinToString("\n"))
    }
}
