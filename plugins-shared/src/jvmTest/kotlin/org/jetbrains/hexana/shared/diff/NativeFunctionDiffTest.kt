package org.jetbrains.hexana.shared.diff

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.disassembly.capstone.CapstoneWasmDisassembler
import org.jetbrains.hexana.disassembly.capstone.loadFromClasspath
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-function native diff over the committed `parsing/native-fixtures` binaries. */
class NativeFunctionDiffTest {

    private fun fixture(path: String): ByteArray =
        File("../parsing/native-fixtures/test-data/$path").also { assertTrue(it.exists(), "missing fixture ${it.absolutePath}") }.readBytes()

    private fun input(left: ByteArray, right: ByteArray) =
        BinaryDiffInput(left.toCommonByteBuffer(), right.toCommonByteBuffer(), "base", "head")

    private val capstone = BinaryDiffHost(nativeDisassembler = { CapstoneWasmDisassembler.loadFromClasspath() })

    private fun functionsTab(session: BinaryDiffSession) = session.tabs.single { it.name == "Functions" }

    /** [bytes] with the first function body's first byte flipped: same size, different code. */
    private fun withFirstFunctionPatched(bytes: ByteArray): Pair<ByteArray, String> {
        val body = NativeFunctionSession(input(bytes, bytes), BinaryDiffHost()).left.first()
        val patched = bytes.copyOf()
        patched[body.fileOffset] = (patched[body.fileOffset].toInt() xor 0xFF).toByte()
        return patched to body.name
    }

    @Test
    fun formatsWithFunctionRecordsGetFunctionsTabAndDetail() {
        for (path in listOf("elf/sample.elf", "elf/sample.o", "macho/sample-arm64.o", "pe/sample.coff")) {
            val bytes = fixture(path)
            val session = NativeDiffProvider().session(input(bytes, bytes))
            assertEquals(listOf("Size Impact", "Functions"), session.tabs.map { it.name }, path)
            assertNotNull(session.detail, path)
        }
    }

    @Test
    fun peImageKeepsSizeTabOnly() {
        val bytes = fixture("pe/sample.exe")
        val session = NativeDiffProvider().session(input(bytes, bytes))
        assertEquals(listOf("Size Impact"), session.tabs.map { it.name })
        assertNull(session.detail)
    }

    @Test
    fun identicalBinariesHaveNoChangedFunctions() = runTest {
        val bytes = fixture("elf/sample.elf")
        val content = functionsTab(NativeDiffProvider().session(input(bytes, bytes), capstone)).compute(DiffTabContext())
        assertNull(content.table)
        assertTrue(content.emptyMessage!!.startsWith("All "), content.emptyMessage)
    }

    @Test
    fun patchedFunctionIsModifiedAndRendersDisassembly() = runTest {
        val base = fixture("elf/sample.elf")
        val (head, name) = withFirstFunctionPatched(base)
        var selected: DiffItemRef? = null
        val session = NativeDiffProvider().session(input(base, head), capstone)
        val table = assertNotNull(functionsTab(session).compute(DiffTabContext(select = { selected = it })).table)
        val row = (0 until table.rowCount).single { table.columns[0].cellProvider(it).representation == name }
        assertEquals("Modified", table.columns[1].cellProvider(row).representation)

        table.rowClickAction!!.invoke(row)
        val rendered = assertNotNull(session.detail).render(assertNotNull(selected))
        assertNull(rendered.emptyMessage)
        assertTrue(!rendered.title.endsWith("[bytes]"), rendered.title)
        assertTrue(rendered.rows.any { it.leftRemoved && it.rightAdded }, "the patched instruction differs")
        assertTrue(rendered.rows.first().left!!.startsWith("0x"), "lines carry their address")
    }

    @Test
    fun withoutDisassemblerDetailFallsBackToBytes() = runTest {
        val base = fixture("elf/sample.elf")
        val (head, name) = withFirstFunctionPatched(base)
        val session = NativeDiffProvider().session(input(base, head))
        val content = functionsTab(session).compute(DiffTabContext())
        assertTrue(content.header.any { "raw bytes" in it.text })
        val index = NativeFunctionSession(input(base, head), BinaryDiffHost()).left.indexOfFirst { it.name == name }
        val rendered = assertNotNull(session.detail).render(DiffItemRef(name, index, index))
        assertTrue(rendered.title.endsWith("[bytes]"), rendered.title)
        assertTrue(rendered.rows.first().leftRemoved, "first byte row differs")
    }

    @Test
    fun pairingDetectsRenameAddAndRemove() {
        fun body(name: String, hash: Long, size: Int = 4) = NativeFunctionBody(name, 0uL, 0, size, null, hash)
        val pairs = pairNativeFunctions(
            left = listOf(body("same", 1), body("changed", 2), body("old_name", 3), body("gone", 4)),
            right = listOf(body("same", 1), body("changed", 5, size = 8), body("new_name", 3), body("fresh", 6)),
        ).associateBy { it.name }
        assertEquals(DiffClass.IDENTICAL, pairs.getValue("same").classification)
        assertEquals(DiffClass.MODIFIED, pairs.getValue("changed").classification)
        assertEquals(DiffClass.MOVED, pairs.getValue("new_name").classification)
        assertEquals("renamed from old_name", pairs.getValue("new_name").detail)
        assertEquals(DiffClass.REMOVED, pairs.getValue("gone").classification)
        assertEquals(DiffClass.ADDED, pairs.getValue("fresh").classification)
    }

    @Test
    fun normalizationMasksAddressesButKeepsSmallImmediates() {
        assertEquals("call 0x…", normalizedKey("call 0x401a30"))
        assertEquals("mov eax, dword ptr [rip + 0x…]", normalizedKey("mov eax, dword ptr [rip + 0x2f0e]"))
        assertEquals("add rsp, 0x18", normalizedKey("add rsp, 0x18"))
    }
}
