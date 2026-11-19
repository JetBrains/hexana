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
 * The .NET assembly diff over Blazor WebAssembly (Webcil) modules: `sample-clr*.webcil.wasm` are the
 * `ClrDiffProviderTest` assemblies converted by the SDK's `WebcilConverter`, so a Webcil pair must
 * report exactly what the PE pair does, and an assembly against its own Webcil must report nothing.
 */
class WebcilDiffTest {
    private fun fixture(name: String): ByteArray =
        File("../parsing/native-fixtures/test-data/pe/$name").also { assertTrue(it.exists(), "missing fixture ${it.absolutePath}") }.readBytes()

    private val basePe by lazy { fixture("sample-clr.exe") }
    private val headPe by lazy { fixture("sample-clr-v2.exe") }
    private val baseWebcil by lazy { fixture("sample-clr.webcil.wasm") }
    private val headWebcil by lazy { fixture("sample-clr-v2.webcil.wasm") }

    private fun input(left: ByteArray, right: ByteArray, leftName: String = "base.wasm", rightName: String = "head.wasm") =
        BinaryDiffInput(left.toCommonByteBuffer(), right.toCommonByteBuffer(), leftName, rightName)

    private suspend fun tab(input: BinaryDiffInput, name: String): DiffTabContent =
        ClrDiffProvider().session(input).tabs.single { it.name == name }.compute(DiffTabContext())

    private fun DiffTabContent.rows(): List<String> {
        val table = table ?: return emptyList()
        return (0 until table.rowCount).map { i -> table.columns.joinToString(" | ") { it.cellProvider(i).representation } }
    }

    @Test
    fun webcilPairsGoToTheAssemblyDiffAndOtherWasmDoesNot() {
        assertIs<ClrDiffProvider>(BinaryDiffProviderRegistry.forInput(input(baseWebcil, headWebcil)))
        assertIs<ClrDiffProvider>(BinaryDiffProviderRegistry.forInput(input(basePe, headWebcil, "base.exe")))
        assertNull(BinaryDiffProviderRegistry.rejectionReason(input(basePe, headWebcil, "base.exe")))
        val plain = File("../wasmParser/src/commonTest/resources/c/utf8-c.wasm").readBytes()
        assertIs<WasmModuleDiffProvider>(BinaryDiffProviderRegistry.forInput(input(plain, plain)))
        // A Webcil module and a plain one are not two assemblies.
        assertIs<WasmModuleDiffProvider>(BinaryDiffProviderRegistry.forInput(input(baseWebcil, plain)))
    }

    @Test
    fun webcilPairReportsWhatThePePairReports() = runTest {
        for (name in listOf("Types", "Methods")) {
            assertEquals(tab(input(basePe, headPe), name).rows(), tab(input(baseWebcil, headWebcil), name).rows(), name)
        }
        val methods = tab(input(baseWebcil, headWebcil), "Methods").rows()
        assertTrue(methods.any { it.startsWith("Hexana.Sample.Greeter::string Greet(string name) | Modified") }, methods.toString())
    }

    @Test
    fun anAssemblyAndItsWebcilAreTheSameCode() = runTest {
        assertNull(tab(input(basePe, baseWebcil, "base.exe"), "Methods").table)
        assertNull(tab(input(basePe, baseWebcil, "base.exe"), "Types").table)
    }

    @Test
    fun sizeImpactComparesTheManagedSizeTrees() = runTest {
        val content = tab(input(baseWebcil, headWebcil), "Size Impact")
        assertNotNull(content.table, content.emptyMessage)
        assertTrue(content.rows().any { it.startsWith(".text") }, content.rows().toString())
    }

    @Test
    fun detailPaneRendersTheSelectedMethodFromBothWrappers() = runTest {
        val session = ClrDiffProvider().session(input(baseWebcil, headWebcil))
        var selected: DiffItemRef? = null
        val table = assertNotNull(session.tabs.single { it.name == "Methods" }.compute(DiffTabContext(select = { selected = it })).table)
        val row = (0 until table.rowCount).first { table.columns[0].cellProvider(it).representation.endsWith("Greet(string name)") }
        table.rowClickAction!!(row)
        val diff = assertNotNull(session.detail).render(assertNotNull(selected))
        // IL read from the wrappers' data segments, at their own file offsets, as for the PE pair.
        assertTrue(diff.rows.any { it.leftRemoved && it.left!!.contains("\", \"") }, diff.rows.joinToString("\n"))
        assertTrue(diff.rows.any { it.rightAdded && it.right!!.contains("\", dear \"") }, diff.rows.joinToString("\n"))
    }
}
