package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The managed size breakdown behind the Top tab, over `parsing/native-fixtures/test-data/pe`.
 * Expected sizes for the ReadyToRun fixture come from crossgen2's map of the same image
 * (`--map --mapcsv`): code 721 B, copied IL 424 B, unwind and GC info 308 B.
 */
class ClrSizeAnalysisTest {
    private fun provider(name: String): PeBinaryProvider {
        val file = File("../parsing/native-fixtures/test-data/pe/$name").also { assertTrue(it.exists(), "missing fixture ${it.absolutePath}") }
        return assertIs<PeBinaryProvider>(createBinaryProvider(file.readBytes().toCommonByteBuffer(), file.name))
    }

    private fun BinarySizeNode.child(vararg path: String): BinarySizeNode =
        path.fold(this) { node, name -> assertNotNull(node.children.firstOrNull { it.name == name }, "no '$name' under '${node.name}': ${node.children.map { it.name }}") }

    private fun BinarySizeNode.assertChildrenAddUp() {
        if (children.isEmpty()) return
        assertEquals(size, children.sumOf { it.size }, "children of '$name'")
    }

    @Test
    fun `ReadyToRun image attributes code to methods and tables to their sections`() {
        val root = assertNotNull(provider("sample-r2r-win-x64.dll").sizeAnalysis)
        val text = root.child(".text")
        val native = text.child(ClrSizeGroups.NATIVE_CODE)
        assertEquals(51, native.child("Hexana.R2rSample", "Calculator", "int Fib(int n)").size)
        assertEquals(70, native.child("Hexana.R2rSample", "Calculator", "int Parse(string text)").size) // body + catch funclet
        assertEquals(36, native.child("Hexana.R2rSample", "Box<T>", "Box<int>::ToString").size)
        assertEquals(50, native.child("Hexana.R2rSample", "Calculator", "Calculator::Max<__Canon>").size)
        assertEquals(26, native.child("Hexana.R2rSample", "Point+Formatter", "string Format(Point point)").size)
        assertEquals(721, native.size - (native.children.firstOrNull { it.name == ClrSizeGroups.CODE_GAPS }?.size ?: 0))

        assertEquals(424, text.child(ClrSizeGroups.IL_BODIES).size)
        // The last GC info block has no recorded end and runs to the next attributed byte, which
        // here takes in the 4 alignment bytes after crossgen's 308-byte node.
        assertTrue(text.child(ClrSizeGroups.READY_TO_RUN_DATA, "Unwind and GC info").size in 308..312)
        assertEquals(72, text.child(ClrSizeGroups.CLI_HEADER).size)
        // Every byte of .text is accounted for: the gaps crossgen leaves are int3 alignment fill.
        assertNull(text.children.firstOrNull { it.name == ClrSizeGroups.UNATTRIBUTED }, text.children.map { "${it.name}=${it.size}" }.toString())

        // On Windows the R2R tables sit in .text; the import cells and their signatures in .data.
        root.child(".data").child(ClrSizeGroups.READY_TO_RUN_DATA, "Import cells")
        for (section in root.children) section.assertChildrenAddUp()
        text.child(ClrSizeGroups.NATIVE_CODE).assertChildrenAddUp()
    }

    @Test
    fun `IL-only assembly breaks the text section into IL, metadata and resources`() {
        val text = assertNotNull(provider("sample-clr.exe").sizeAnalysis).child(".text")
        assertNull(text.children.firstOrNull { it.name == ClrSizeGroups.NATIVE_CODE })
        val describe = text.child(ClrSizeGroups.IL_BODIES, "Hexana.Sample", "Program", "string Describe(int value)")
        assertTrue(describe.size > 119) // header + 119 bytes of IL + exception sections
        assertTrue(text.child(ClrSizeGroups.METADATA, "#~", "MethodDef").size > 0)
        assertEquals(22, text.child(ClrSizeGroups.RESOURCES, "Hexana.Sample.hello.txt").size) // 4-byte length + 18 bytes
        text.assertChildrenAddUp()
    }

    @Test
    fun `native PE keeps the plain section list`() {
        val root = assertNotNull(provider("sample.exe").sizeAnalysis)
        assertTrue(root.children.all { it.children.isEmpty() })
    }

    @Test
    fun `survives corrupted ReadyToRun images`() {
        val original = File("../parsing/native-fixtures/test-data/pe/sample-r2r-win-x64.dll").readBytes()
        val random = kotlin.random.Random(2026)
        repeat(400) {
            val corrupted = original.copyOf()
            repeat(1 + random.nextInt(12)) { corrupted[random.nextInt(0x200, corrupted.size)] = random.nextInt(256).toByte() }
            (createBinaryProvider(corrupted.toCommonByteBuffer(), "corrupted.dll") as? PeBinaryProvider)?.sizeAnalysis
        }
    }
}
