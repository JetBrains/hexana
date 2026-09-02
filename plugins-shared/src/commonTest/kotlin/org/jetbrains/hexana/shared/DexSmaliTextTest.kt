package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexSmaliTextTest {

    private val bytes = buildSampleDex().toCommonByteBuffer()
    private val dex = DexFileParser.parse(bytes.duplicate())
    private val classes = dexSmaliClasses(dex)

    @Test
    fun `classes come from class_def_items alone`() {
        assertEquals(1, classes.size)
        val cls = classes.single()
        assertEquals("Lcom/example/Foo;", cls.descriptor)
        assertEquals("Ljava/lang/Object;", cls.superDescriptor)
        assertEquals(dex.header.classDefsOff, cls.byteRange.first)
        assertEquals(32L, cls.byteRange.last - cls.byteRange.first + 1)
    }

    @Test
    fun `renders the class header fields and methods`() {
        val text = dexSmaliClassRows(dex, bytes, classes.single()).map(::contentOf)

        assertTrue(text.contains(".class public final Lcom/example/Foo;"), text.toString())
        assertTrue(text.contains(".super Ljava/lang/Object;"), text.toString())
        assertTrue(text.contains(".source \"Foo.java\""), text.toString())
        assertTrue(text.contains("# instance fields"), text.toString())
        assertTrue(text.contains(".field private x:I"), text.toString())
        assertTrue(text.contains("# direct methods"), text.toString())
        assertTrue(text.contains(".method public bar()V"), text.toString())
        assertTrue(text.contains("# virtual methods"), text.toString())
        assertTrue(text.contains(".method public print(Ljava/lang/String;)V"), text.toString())
        assertEquals(2, text.count { it == ".end method" }, text.toString())
    }

    @Test
    fun `renders instructions with resolved references and smali register names`() {
        val text = dexSmaliClassRows(dex, bytes, classes.single()).map(::contentOf)

        // registers_size 2, ins_size 1: v1 is the incoming `this`, so it prints as p0.
        assertTrue(text.contains(".registers 2  # ins 1, outs 0"), text.toString())
        assertTrue(text.contains("0000: const/4 v0, 0x1"), text.toString())
        assertTrue(text.contains("0001: iput v0, p0, Lcom/example/Foo;->x:I"), text.toString())
        assertTrue(text.contains("0003: return-void"), text.toString())

        assertTrue(text.contains("0000: const-string v0, \"hello\""), text.toString())
        assertTrue(
            text.contains("0002: invoke-virtual {p0, v0}, Lcom/example/Foo;->print(Ljava/lang/String;)V"),
            text.toString(),
        )
    }

    @Test
    fun `renders try ranges and their handlers`() {
        val text = dexSmaliClassRows(dex, bytes, classes.single()).map(::contentOf)

        assertTrue(
            text.contains(".catch Ljava/lang/Object; {0000 .. 0003} -> :0005"),
            text.toString(),
        )
        assertTrue(text.contains(".catchall {0000 .. 0003} -> :0005"), text.toString())
    }

    @Test
    fun `instruction rows carry the file offset of their code unit`() {
        val rows = dexSmaliClassRows(dex, bytes, classes.single())
        val code = DexFileParser.parseCodeItem(
            bytes.duplicate(),
            DexFileParser.parseClassData(bytes.duplicate(), dex.classDefs[0])!!.directMethods[0].codeOff,
        )!!

        val firstInstruction = rows.first { contentOf(it).startsWith("0000: const/4") }
        assertEquals(code.insnsFileOffset.toLong(), firstInstruction.fileOffset)
        // The gutter is a clickable offset annotation pointing at the same byte.
        val gutter = firstInstruction.tokens.first()
        assertEquals(RenderTokenKind.OFFSET_ANNOTATION, gutter.kind)
        assertEquals(code.insnsFileOffset.toLong(), gutter.sourceOffset)

        val thirdInstruction = rows.first { contentOf(it).startsWith("0003: return-void") }
        assertEquals(code.insnsFileOffset.toLong() + 3 * 2, thirdInstruction.fileOffset)
    }

    @Test
    fun `structural rows have no offset but keep the gutter width`() {
        val rows = dexSmaliClassRows(dex, bytes, classes.single())
        val endMethod = rows.first { contentOf(it) == ".end method" }
        assertNull(endMethod.fileOffset)
        assertEquals(RenderTokenKind.WHITESPACE, endMethod.tokens.first().kind)
        assertNull(endMethod.tokens.first().sourceOffset)
    }

    @Test
    fun `chunk tree pairs a foldable header with a body element per class`() {
        val tree = dexSmaliChunkTree(classes)

        assertEquals(2, tree.totalChunks)
        val header = tree.chunkAt(0)
        val body = tree.chunkAt(1)
        assertTrue(header is BinaryChunk.SectionHeader)
        assertTrue(body is BinaryChunk.Element)
        assertEquals(DexSmaliSectionKinds.CLASS, header.kind)
        assertEquals(DexSmaliSectionKinds.CLASS_BODY, body.kind)
        assertEquals("Lcom/example/Foo;", (body as BinaryChunk.Element).displayName)

        assertEquals(0, tree.chunkForId(dexSmaliClassHeaderId(classes.single())))
        assertEquals(1, tree.chunkForId(dexSmaliClassBodyId(0)))
        assertNull(tree.chunkForId(dexSmaliClassBodyId(7)))
    }

    @Test
    fun `chunk ids are unique and offsets resolve to the class body`() {
        val tree = dexSmaliChunkTree(classes)
        val ids = (0 until tree.totalChunks).map { tree.chunkAt(it).id }
        assertEquals(ids.size, ids.toSet().size)

        val classDefsOff = dex.header.classDefsOff
        assertEquals(1, tree.chunkContainingByte(classDefsOff))
        assertEquals(1, tree.chunkContainingByte(classDefsOff + 31))
        assertNull(tree.chunkContainingByte(classDefsOff - 1))
        assertNull(tree.chunkContainingByte(classDefsOff + 32))
    }

    @Test
    fun `search index covers class descriptors and method signatures`() {
        val index = dexSmaliSearchIndex(dex, bytes, classes)

        val classEntry = index.first { it.label == "Lcom/example/Foo;" }
        assertEquals(0, classEntry.classIndex)
        assertNull(classEntry.detail)

        val methodEntry = assertNotNull(index.firstOrNull { it.label == "print(Ljava/lang/String;)V" })
        assertEquals(0, methodEntry.classIndex)
        assertEquals("Lcom/example/Foo;", methodEntry.detail)
    }

    @Test
    fun `large files are indexed by class name only`() {
        // The method pass parses every class_data_item, so it is skipped once a
        // file has more classes than the limit.
        val index = dexSmaliSearchIndex(dex, bytes, classes, methodIndexClassLimit = 0)
        assertEquals(listOf("Lcom/example/Foo;"), index.map { it.label })
    }

    @Test
    fun `chunk id types match what the panel navigates to`() {
        assertEquals(
            ChunkId.Symbolic(DexSmaliSectionKinds.CLASS, "Lcom/example/Foo;"),
            dexSmaliClassHeaderId(classes.single()),
        )
        assertEquals(ChunkId.Indexed(DexSmaliSectionKinds.CLASS_BODY, 3), dexSmaliClassBodyId(3))
    }
}

/**
 * The row's content without its fixed-width gutter, which hosts render as a
 * separate clickable prefix.
 */
private fun contentOf(row: DexSmaliRow): String = row.tokens
    .dropWhile { it.kind == RenderTokenKind.OFFSET_ANNOTATION || it.kind == RenderTokenKind.WHITESPACE }
    .joinToString("") { it.text }
    .trim()
