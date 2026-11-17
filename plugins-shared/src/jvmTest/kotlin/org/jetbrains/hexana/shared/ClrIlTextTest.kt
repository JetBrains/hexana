package org.jetbrains.hexana.shared

import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** IL view over `parsing/native-fixtures/test-data/pe/sample-clr.exe`; compare with `ikdasm` output for the same file. */
class ClrIlTextTest {
    private val provider = File("../parsing/native-fixtures/test-data/pe/sample-clr.exe").let { file ->
        assertIs<PeBinaryProvider>(createBinaryProvider(file.readBytes().toCommonByteBuffer(), file.name))
    }
    private val metadata = assertNotNull(provider.clrMetadata)
    private val types = clrIlTypes(metadata)

    private fun rows(typeName: String): List<String> =
        clrIlTypeRows(metadata, provider.bytes, types.single { it.name == typeName }).map { it.text.drop(10) }

    @Test
    fun `lists every type with its declaration`() {
        assertEquals(metadata.typeDefs.size, types.size)
        val box = types.single { it.name == "Hexana.Sample.Box<T>" }
        assertEquals("public class", box.declaration)
        assertEquals("System.Object", box.extends)
        assertEquals(3, box.memberCount)
        assertEquals("public static class", types.single { it.name == "Hexana.Sample.Program" }.declaration)
    }

    @Test
    fun `renders a method with locals, switch and exception clauses`() {
        val program = rows("Hexana.Sample.Program")
        assertEquals(".class public static class Hexana.Sample.Program", program.first())
        val describe = program.dropWhile { !it.startsWith(".method public static string Describe(int value)") }
        assertEquals(
            listOf(
                ".method public static string Describe(int value)",
                "{",
                "    // RVA 0x215C, code size 119, fat header",
                "    .maxstack 3",
                "    .locals init (string V_0)",
                "    IL_0000: ldarg.0",
                "    IL_0001: switch     (IL_0014, IL_001c, IL_0024)",
                "    IL_0012: br.s       IL_002c",
                "    IL_0014: ldstr      \"zero\"",
            ),
            describe.take(9),
        )
        assertTrue("    IL_0057: callvirt   string Box<int>::Map<string>(Func<!0, string>)" in describe, describe.toString())
        assertTrue("    .try IL_0000 to IL_005f catch System.FormatException handler IL_005f to IL_0068" in describe)
        assertTrue("    .try IL_0000 to IL_0068 finally handler IL_0068 to IL_0075" in describe)
        assertTrue("    .entrypoint" in program)
    }

    @Test
    fun `instruction rows carry their bytes and label references`() {
        val main = metadata.methods.single { it.name == "Main" }
        val rows = clrIlTypeRows(metadata, provider.bytes, types.single { it.name == "Hexana.Sample.Program" })
        val ret = rows.first { it.fileOffset == (main.body!!.codeRange.offset + 0x3D).toLong() }
        assertEquals(1, ret.byteSize)
        assertTrue(ret.text.endsWith("IL_003d: ret"))
        val gutter = ret.tokens.first()
        assertEquals(RenderTokenKind.OFFSET_ANNOTATION, gutter.kind)
        assertEquals(ret.fileOffset, gutter.sourceOffset)

        val branch = rows.first { it.text.contains("br.s") }
        assertEquals(RenderTokenKind.LABEL_REF, branch.tokens.last().kind)
    }

    @Test
    fun `abstract methods render without a body`() {
        assertEquals(
            listOf(".class public interface Hexana.Sample.IGreeter", "", ".method public abstract string Greet(string name)", "    // abstract: no body"),
            rows("Hexana.Sample.IGreeter").map { it.trimEnd() },
        )
    }

    @Test
    fun `chunk tree maps method bodies and metadata rows to their type body`() {
        val tree = clrIlChunkTree(metadata, types)
        assertEquals(types.size * 2, tree.totalChunks)
        val describe = metadata.methods.single { it.name == "Describe" }
        val programIndex = types.single { it.name == "Hexana.Sample.Program" }.index
        val bodyChunk = clrIlTypeBodyId(programIndex)

        for (offset in listOf(describe.body!!.codeRange.offset + 5, describe.recordRange.offset, metadata.typeDefs[programIndex].recordRange.offset)) {
            val index = assertNotNull(tree.chunkContainingByte(offset.toLong()), "offset $offset")
            assertEquals(bodyChunk, tree.chunkAt(index).id)
            assertIs<BinaryChunk.Element>(tree.chunkAt(index))
        }
        assertEquals(null, tree.chunkContainingByte(0))
    }

    @Test
    fun `search index covers types and methods`() {
        val index = clrIlSearchIndex(metadata, types)
        val describe = index.single { it.label == "Hexana.Sample.Program::Describe" }
        assertEquals("string Describe(int value)", describe.detail)
        assertEquals("Hexana.Sample.Program", types[describe.typeIndex].name)
        assertTrue(index.any { it.label == "Hexana.Sample.Box<T>" && it.detail == null })
    }

    @Test
    fun `renders every type of a real assembly without unresolved tokens`() {
        for (type in types) {
            val text = clrIlTypeRows(metadata, provider.bytes, type).joinToString("\n") { it.text }
            assertTrue("<unresolved" !in text && "<invalid" !in text && ".byte" !in text, "${type.name}:\n$text")
        }
    }
}
