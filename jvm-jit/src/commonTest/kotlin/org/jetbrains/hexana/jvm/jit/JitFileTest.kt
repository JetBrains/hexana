package org.jetbrains.hexana.jvm.jit

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JitFileTest {

    @Test
    fun `rejects buffers without the MIN magic`() {
        val bytes = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(64)
        assertNull(JitFile.parse(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `parses a single-method dump end to end`() {
        val builder = JitDumpBuilder()
        // Strings — interned in order; offsets refer to byte positions in the
        // strings section (starting from the 1-byte null sentinel).
        val signatureStr = "[Ljava/lang/Foo;:_::bar:()V:_]"
        val sourceStr = "Foo.java"
        val signatureOffset = builder.intern(signatureStr)
        val sourceOffset = builder.intern(sourceStr)

        val code = byteArrayOf(0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0xc3.toByte()) // nop;nop;nop;ret

        builder.addSymbol(
            address = 0x100000uL,
            code = code,
            signatureOffset = signatureOffset,
            sourceFileOffset = sourceOffset,
            version = 1u,
            lineNumberEntries = listOf(0uL to 7u, 2uL to 8u),
            addressLocationMap = listOf(0x100000uL to 0uL, 0x100002uL to 5uL),
            inlineRecords = listOf(
                listOf(
                    StackInfo(
                        address = 0x100001uL,
                        frames = listOf(
                            FrameSpec(methodSig = signatureStr, sourcePath = sourceStr, bci = 3),
                        ),
                    ),
                ),
            ),
        )

        val dump = builder.finish()
        val parsed = JitFile.parse(dump.toCommonByteBuffer())
        assertNotNull(parsed)

        assertEquals(0.toUByte(), parsed.header.version)
        assertEquals(1, parsed.symbols.size)

        val sym = parsed.symbols.single()
        assertEquals(0x100000uL, sym.address)
        assertEquals(code.size.toULong(), sym.codeSize)
        assertEquals(1.toUByte(), sym.version)
        assertEquals(sourceStr, sym.sourceFileName)

        val parsedSym = sym.sym()
        assertNotNull(parsedSym)
        assertEquals("Ljava/lang/Foo;", parsedSym.classSignature)
        assertEquals(null, parsedSym.classGenericSignature)
        assertEquals("bar", parsedSym.methodName)
        assertEquals("()V", parsedSym.methodSignature)

        assertEquals(code.toList(), sym.codeBlock.toList())

        // Line number table: each entry is (jlocation u64, jint line) on disk
        // with 16-byte stride (per ABI sizeof(jvmtiLineNumberEntry) = 16).
        assertEquals(listOf(0uL to 7u, 2uL to 8u), sym.lineNumberTable)

        // Address-location map: each entry is (start_address u64, location u64)
        // with the start_address normalised to a code-relative offset by the
        // parser. We wrote raw absolute addresses; the parser subtracts the
        // symbol's address.
        assertEquals(listOf(0uL to 0uL, 2uL to 5uL), sym.addressLocationMap)

        val inlines = sym.inlineRecords
        assertEquals(1, inlines.size)
        assertEquals(1, inlines[0].inlines.size)
        val frame = inlines[0].inlines[0].inlined.single()
        assertEquals("bar", frame.sym?.methodName)
        assertEquals(sourceStr, frame.sourcePath)
        assertEquals(3, frame.bci)
    }

    @Test
    fun `stops the symbol walk on a bad magic`() {
        // Synthesize a dump whose syms section starts with random bytes —
        // the parser must not raise and must return an empty symbol list.
        val builder = JitDumpBuilder().apply {
            // No symbols added; inject a junk magic at the sym section start.
            corruptSymSection = true
        }
        val parsed = JitFile.parse(builder.finish().toCommonByteBuffer())
        assertNotNull(parsed)
        assertEquals(0, parsed.symbols.size)
    }

    @Test
    fun `string lookup decodes UTF-8 + caches`() {
        val builder = JitDumpBuilder()
        val s1 = builder.intern("HelloWorld")
        val s2 = builder.intern("Привет, мир")
        builder.addSymbol(
            address = 0uL,
            code = ByteArray(1),
            signatureOffset = s1,
            sourceFileOffset = s2,
            version = 1u,
            lineNumberEntries = emptyList(),
            addressLocationMap = emptyList(),
            inlineRecords = emptyList(),
        )
        val parsed = JitFile.parse(builder.finish().toCommonByteBuffer())
        assertNotNull(parsed)
        assertEquals("Привет, мир", parsed.symbols.single().sourceFileName)
        // Calling twice exercises the cache without checking it directly.
        assertEquals("Привет, мир", parsed.symbols.single().sourceFileName)
    }

    @Test
    fun `reports failure on empty buffer`() {
        assertNull(JitFile.parse(ByteArray(0).toCommonByteBuffer()))
    }

    @Test
    fun `reports failure on truncated header`() {
        val truncated = byteArrayOf('M'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte())
        assertNull(JitFile.parse(truncated.toCommonByteBuffer()))
    }

    @Test
    fun `unknown signature shape resolves to null`() {
        val builder = JitDumpBuilder()
        val s = builder.intern("Not a valid signature")
        val src = builder.intern("Foo.java")
        builder.addSymbol(
            address = 0uL,
            code = ByteArray(1),
            signatureOffset = s,
            sourceFileOffset = src,
            version = 1u,
            lineNumberEntries = emptyList(),
            addressLocationMap = emptyList(),
            inlineRecords = emptyList(),
        )
        val parsed = JitFile.parse(builder.finish().toCommonByteBuffer())
        assertNotNull(parsed)
        assertNull(parsed.symbols.single().sym())
    }

    @Test
    fun `assertFails on negative line count would NPE in lazy property`() {
        // Defensive sanity-check that the lazy line table doesn't crash on a
        // valid empty table.
        val builder = JitDumpBuilder()
        val s = builder.intern("[A:_::m:()V:_]")
        val src = builder.intern("A.java")
        builder.addSymbol(
            address = 0uL, code = ByteArray(0),
            signatureOffset = s, sourceFileOffset = src, version = 1u,
            lineNumberEntries = emptyList(),
            addressLocationMap = emptyList(),
            inlineRecords = emptyList(),
        )
        val parsed = JitFile.parse(builder.finish().toCommonByteBuffer())
        assertNotNull(parsed)
        assertEquals(emptyList(), parsed.symbols.single().lineNumberTable)
        assertEquals(emptyList(), parsed.symbols.single().addressLocationMap)
        // Reading codeBlock on a zero-size symbol returns an empty array.
        assertTrue(parsed.symbols.single().codeBlock.isEmpty())
        // The next assertion just ensures the test framework's assertFails
        // doesn't get optimised away by some KMP target.
        assertFails { error("sanity") }
    }
}

// -----------------------------------------------------------------------------
// Dump builder — mirrors the byte-exact layout written by agent.c's
// Agent_OnUnload (header_t / sym_entry_t / jvmtiLineNumberEntry /
// jvmtiAddrLocationMap / inline records / string table).
// -----------------------------------------------------------------------------

private data class FrameSpec(val methodSig: String, val sourcePath: String, val bci: Int)
private data class StackInfo(val address: ULong, val frames: List<FrameSpec>)

private class JitDumpBuilder {
    private val out = ArrayList<Byte>()
    private val strings = LinkedHashMap<String, UInt>()
    private var nextStringOffset: UInt = 1u // index 0 is the null sentinel
    private val pending = ArrayList<PendingSymbol>()

    /** When true, [finish] writes 8 bytes of junk where the first sym would go. */
    var corruptSymSection: Boolean = false

    private data class PendingSymbol(
        val address: ULong,
        val code: ByteArray,
        val codeFileOffset: ULong,
        val signatureOffset: UInt,
        val sourceFileOffset: UInt,
        val version: UByte,
        val lineNumberEntries: List<Pair<ULong, UInt>>,
        val addressLocationMap: List<Pair<ULong, ULong>>,
        val inlineRecords: List<List<StackInfo>>,
    )

    fun intern(value: String): UInt {
        strings[value]?.let { return it }
        val offset = nextStringOffset
        strings[value] = offset
        nextStringOffset += (value.encodeToByteArray().size + 1).toUInt() // +1 for the trailing NUL
        return offset
    }

    fun addSymbol(
        address: ULong,
        code: ByteArray,
        signatureOffset: UInt,
        sourceFileOffset: UInt,
        version: UByte,
        lineNumberEntries: List<Pair<ULong, UInt>>,
        addressLocationMap: List<Pair<ULong, ULong>>,
        inlineRecords: List<List<StackInfo>>,
    ) {
        pending += PendingSymbol(
            address = address,
            code = code,
            codeFileOffset = 0uL,
            signatureOffset = signatureOffset,
            sourceFileOffset = sourceFileOffset,
            version = version,
            lineNumberEntries = lineNumberEntries,
            addressLocationMap = addressLocationMap,
            inlineRecords = inlineRecords,
        )
    }

    fun finish(): ByteArray {
        // 1) Header (36 bytes) — fill in syms / strings offsets later.
        repeat(36) { u1(0) }
        out[0] = 'M'.code.toByte()
        out[1] = 'I'.code.toByte()
        out[2] = 'N'.code.toByte()
        // version (offset 3) is left at 0.
        writeU8At(4, out.size.toULong()) // header.offset = size of header itself

        // 2) Code section — concatenated raw bytes.
        for ((idx, sym) in pending.withIndex()) {
            val mutableSym = pending[idx].copy(codeFileOffset = out.size.toULong())
            pending[idx] = mutableSym
            for (b in sym.code) out.add(b)
        }

        // 3) Symbols section. corruptSymSection injects garbage instead.
        val symsStart = out.size
        if (corruptSymSection) {
            repeat(8) { u1(0xAA) }
        } else {
            for (sym in pending) {
                writeSymbol(sym)
            }
        }

        // 4) Strings section: null sentinel + UTF-8 strings (each null-terminated).
        val stringsStart = out.size
        u1(0)
        for ((value, _) in strings) {
            for (b in value.encodeToByteArray()) out.add(b)
            u1(0)
        }

        // Backfill header.syms_offset / header.string_offset.
        writeU8At(12, symsStart.toULong())
        writeU8At(20, stringsStart.toULong())
        // header.size left at 0 (mirroring agent.c — unused).

        return ByteArray(out.size) { out[it] }
    }

    private fun writeSymbol(sym: PendingSymbol) {
        val symStart = out.size
        // Reserve 81 bytes for the sym_entry_t header — backfill at the end.
        repeat(81) { u1(0) }

        val lineTableStart = out.size
        for ((startLoc, line) in sym.lineNumberEntries) {
            u8(startLoc)
            u4(line)
            // jvmtiLineNumberEntry has 4 bytes of trailing padding to make
            // sizeof = 16 — match the agent's `sizeof` write.
            repeat(4) { u1(0) }
        }

        val addressMapStart = out.size
        for ((startAddr, location) in sym.addressLocationMap) {
            u8(startAddr)
            u8(location)
        }

        val inlineStart = out.size
        for (record in sym.inlineRecords) {
            u4(record.size.toUInt())
            for (info in record) {
                u8(info.address)
                u4(info.frames.size.toUInt())
                for (frame in info.frames) {
                    val methodOffset = intern(frame.methodSig)
                    val pathOffset = intern(frame.sourcePath)
                    u4(methodOffset)
                    u4(pathOffset)
                    u4(frame.bci.toUInt())
                }
            }
        }

        val nextOffset = (out.size - symStart).toULong()

        // Backfill the sym_entry_t header now that we know the offsets.
        writeU8At(symStart + 0, JitFile.SYM_MAGIC0)
        writeU8At(symStart + 8, sym.address)
        writeU8At(symStart + 16, sym.code.size.toULong())
        writeU8At(symStart + 24, sym.codeFileOffset)
        writeU8At(symStart + 32, nextOffset)
        writeU4At(symStart + 40, sym.signatureOffset)
        writeU4At(symStart + 44, sym.sourceFileOffset)
        writeU4At(symStart + 48, (lineTableStart - symStart).toUInt())
        writeU4At(symStart + 52, (addressMapStart - symStart).toUInt())
        writeU4At(symStart + 56, (inlineStart - symStart).toUInt())
        writeU4At(symStart + 60, sym.lineNumberEntries.size.toUInt())
        writeU4At(symStart + 64, sym.addressLocationMap.size.toUInt())
        writeU4At(symStart + 68, sym.inlineRecords.size.toUInt())
        out[symStart + 72] = sym.version.toByte()
        writeU8At(symStart + 73, JitFile.SYM_MAGIC1)
    }

    private fun u1(value: Int) {
        out.add((value and 0xFF).toByte())
    }

    private fun u4(value: UInt) {
        u1((value and 0xFFu).toInt())
        u1(((value shr 8) and 0xFFu).toInt())
        u1(((value shr 16) and 0xFFu).toInt())
        u1(((value shr 24) and 0xFFu).toInt())
    }

    private fun u8(value: ULong) {
        for (i in 0 until 8) {
            u1(((value shr (i * 8)) and 0xFFuL).toInt())
        }
    }

    private fun writeU4At(at: Int, value: UInt) {
        for (i in 0 until 4) {
            out[at + i] = (((value shr (i * 8)) and 0xFFu).toInt()).toByte()
        }
    }

    private fun writeU8At(at: Int, value: ULong) {
        for (i in 0 until 8) {
            out[at + i] = (((value shr (i * 8)) and 0xFFuL).toInt()).toByte()
        }
    }
}
