package org.jetbrains.hexana.jvm.jit

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parsed JIT-method dump produced by the JVMTI agent at
 * `jvm-jit/jvmti-agent/src/agent.c`.
 *
 * The on-disk format (little-endian, struct layouts match `__attribute__((packed))`):
 *
 * ```c
 * typedef struct __attribute__((packed)) {
 *     char     magic[3];        // "MIN"
 *     uint8_t  version;
 *     uint64_t offset;          // sizeof(header_t)  (legacy field; not consumed here)
 *     uint64_t syms_offset;
 *     uint64_t string_offset;
 *     size_t   size;            // unused by the agent
 * } header_t;                    // 36 bytes
 * ```
 *
 * Following the header the dump is split into three logical sections:
 *
 *  1. **Code** — raw JIT-compiled bytes for every loaded method. Per-symbol
 *     `fd_offset` points back into this region.
 *  2. **Strings** (at `header.stringOffset`) — sentinel `0x00` followed by
 *     null-terminated UTF-8 strings; symbol entries reference them by
 *     in-section offset.
 *  3. **Symbols** (at `header.symsOffset`) — a chain of `sym_entry_t`
 *     records, each followed by its line-number table, address-location
 *     map, and inline-records blob. Records are linked by their `next`
 *     field (total record size including trailing tables).
 *
 * Use [parse] to construct a [JitFile]; it validates the `"MIN"` magic and
 * defers the rest of the work to lazy properties. The class itself is
 * agnostic of how the buffer was loaded — the JVM convenience `fromFile`
 * lives in `jvmMain`.
 */
class JitFile internal constructor(internal val buffer: CommonByteBuffer) {

    val header: JitHeader by lazy {
        JitHeader(
            version = buffer.u1At(OFFSET_VERSION),
            offset = buffer.u8At(OFFSET_OFFSET),
            symsOffset = buffer.u8At(OFFSET_SYMS_OFFSET),
            stringsOffset = buffer.u8At(OFFSET_STRINGS_OFFSET),
        )
    }

    val symbols: List<JitSym> by lazy { loadSymbols() }

    private val stringCache: HashMap<UInt, String> = HashMap()

    /** Looks up a null-terminated UTF-8 string by its byte offset within the strings section. */
    fun string(offset: UInt): String = stringCache.getOrPut(offset) {
        val start = (header.stringsOffset + offset.toULong()).toInt()
        val end = run {
            var p = start
            val limit = buffer.size
            while (p < limit && buffer[p] != 0.toByte()) p++
            p
        }
        val len = end - start
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = buffer[start + i]
        bytes.decodeToString()
    }

    private fun loadSymbols(): List<JitSym> {
        val symbols = mutableListOf<JitSym>()
        var symStart = header.symsOffset
        while (true) {
            val absStart = symStart.toInt()
            if (absStart < 0 || absStart >= buffer.size) break

            val magic = buffer.u8At(absStart + SYM_OFFSET_MAGIC)
            if (magic != SYM_MAGIC0) break

            val symAddress = buffer.u8At(absStart + SYM_OFFSET_ADDRESS)
            val symCodeSize = buffer.u8At(absStart + SYM_OFFSET_CODE_SIZE)
            val symFileOffset = buffer.u8At(absStart + SYM_OFFSET_FILE_OFFSET)
            val next = buffer.u8At(absStart + SYM_OFFSET_NEXT)
            val signatureOffset = buffer.u4At(absStart + SYM_OFFSET_STRING_OFFSET)
            val fileNameOffset = buffer.u4At(absStart + SYM_OFFSET_FILE_NAME_OFFSET)
            val lineNumberTableOffset = buffer.u4At(absStart + SYM_OFFSET_LINE_TABLE)
            val addressLocationMapOffset = buffer.u4At(absStart + SYM_OFFSET_ADDRESS_MAP)
            val inlineOffset = buffer.u4At(absStart + SYM_OFFSET_INLINE)
            val lineNumberEntryCount = buffer.u4At(absStart + SYM_OFFSET_LINE_COUNT)
            val mapLength = buffer.u4At(absStart + SYM_OFFSET_MAP_LENGTH)
            val inlineCount = buffer.u4At(absStart + SYM_OFFSET_INLINE_COUNT)
            val version = buffer.u1At(absStart + SYM_OFFSET_VERSION)

            // v2 (header.version >= 2): sym_entry_t carries the JVM bytecode
            // blob captured via GetBytecodes() at JIT-compile time. v0/v1
            // dumps have neither the fields nor the bytes — fall back to an
            // empty blob.
            val hasBytecode = header.version >= 2.toUByte()
            val bytecodeOffset =
                if (hasBytecode) buffer.u4At(absStart + SYM_OFFSET_BYTECODE_OFFSET) else 0u
            val bytecodeLength =
                if (hasBytecode) buffer.u4At(absStart + SYM_OFFSET_BYTECODE_LENGTH) else 0u

            symbols += JitSym(
                file = this,
                symbolOffset = symStart,
                address = symAddress,
                codeSize = symCodeSize,
                fileOffset = symFileOffset,
                version = version,
                signatureOffset = signatureOffset,
                fileNameOffset = fileNameOffset,
                lineNumberTableOffset = lineNumberTableOffset,
                lineNumberEntryCount = lineNumberEntryCount,
                addressLocationMapOffset = addressLocationMapOffset,
                mapLength = mapLength,
                inlineOffset = inlineOffset,
                inlineCount = inlineCount,
                bytecodeOffset = bytecodeOffset,
                bytecodeLength = bytecodeLength,
            )

            if (next == 0uL) break
            symStart += next
        }
        return symbols.toList()
    }

    companion object {
        const val MAGIC0: String = "MIN"
        const val SYM_MAGIC0: ULong = 0xdeadbeef00000001uL
        const val SYM_MAGIC1: ULong = 0xdeadbeef00000002uL

        // header_t (packed) field offsets:
        private const val OFFSET_VERSION = 3
        private const val OFFSET_OFFSET = 4
        private const val OFFSET_SYMS_OFFSET = 12
        private const val OFFSET_STRINGS_OFFSET = 20

        // sym_entry_t (packed) field byte offsets (relative to sym start):
        internal const val SYM_OFFSET_MAGIC = 0
        internal const val SYM_OFFSET_ADDRESS = 8
        internal const val SYM_OFFSET_CODE_SIZE = 16
        internal const val SYM_OFFSET_FILE_OFFSET = 24
        internal const val SYM_OFFSET_NEXT = 32
        internal const val SYM_OFFSET_STRING_OFFSET = 40
        internal const val SYM_OFFSET_FILE_NAME_OFFSET = 44
        internal const val SYM_OFFSET_LINE_TABLE = 48
        internal const val SYM_OFFSET_ADDRESS_MAP = 52
        internal const val SYM_OFFSET_INLINE = 56
        internal const val SYM_OFFSET_LINE_COUNT = 60
        internal const val SYM_OFFSET_MAP_LENGTH = 64
        internal const val SYM_OFFSET_INLINE_COUNT = 68
        internal const val SYM_OFFSET_VERSION = 72
        internal const val SYM_OFFSET_MAGIC1 = 73
        // v2 additions — only read when header.version >= 2.
        internal const val SYM_OFFSET_BYTECODE_OFFSET = 81
        internal const val SYM_OFFSET_BYTECODE_LENGTH = 85
        internal const val SYM_ENTRY_SIZE_V1 = 81
        internal const val SYM_ENTRY_SIZE = 89

        // jvmtiLineNumberEntry { jlocation(8); jint(4); } — struct alignment
        // requires sizeof = 16 for an array stride. The agent uses sizeof in
        // its write(), so each entry on disk is 16 bytes (4 of which are
        // padding after `line_number`).
        internal const val LINE_NUMBER_ENTRY_STRIDE = 16

        // jvmtiAddrLocationMap { const void* (8); jlocation (8); } — packed
        // size 16, no internal padding.
        internal const val ADDRESS_LOCATION_MAP_ENTRY_STRIDE = 16

        /**
         * Validates `"MIN"` magic and returns a parser. Returns `null` for
         * any buffer that doesn't open with the agent's magic.
         */
        fun parse(buffer: CommonByteBuffer): JitFile? {
            if (buffer.size < OFFSET_STRINGS_OFFSET + 8) return null
            if (buffer[0].toInt() != 'M'.code) return null
            if (buffer[1].toInt() != 'I'.code) return null
            if (buffer[2].toInt() != 'N'.code) return null
            return JitFile(buffer)
        }
    }
}

/** Parsed header of a JIT dump (`header_t` in `agent.c`). */
data class JitHeader(
    val version: UByte,
    val offset: ULong,
    val symsOffset: ULong,
    val stringsOffset: ULong,
)

/**
 * One `sym_entry_t` chain entry — a JIT-compiled method that the agent
 * captured. Backing data lives in the parent [JitFile.buffer]; per-symbol
 * tables (line numbers, address-location map, inline records, code bytes)
 * are read lazily on access.
 */
class JitSym internal constructor(
    private val file: JitFile,
    private val symbolOffset: ULong,
    val address: ULong,
    val codeSize: ULong,
    /**
     * Byte offset within the parent [JitFile.buffer] where this sym's
     * JIT-compiled native code starts. Exposed so editors can position a
     * global hex view at the right spot when this sym is selected.
     */
    val fileOffset: ULong,
    val version: UByte,
    private val signatureOffset: UInt,
    private val fileNameOffset: UInt,
    private val lineNumberTableOffset: UInt,
    private val lineNumberEntryCount: UInt,
    private val addressLocationMapOffset: UInt,
    private val mapLength: UInt,
    private val inlineOffset: UInt,
    private val inlineCount: UInt,
    private val bytecodeOffset: UInt = 0u,
    private val bytecodeLength: UInt = 0u,
) {
    /** Resolved source file name via the string table. */
    val sourceFileName: String by lazy { file.string(fileNameOffset) }

    private val signature: Sym? by lazy { symByStringOffset(signatureOffset) }

    fun sym(): Sym? = signature

    /** Decoded `(start_location, line_number)` pairs; one entry per JVMTI line record. */
    val lineNumberTable: List<Pair<ULong, UInt>> by lazy {
        val out = ArrayList<Pair<ULong, UInt>>(lineNumberEntryCount.toInt())
        val tableStart = (symbolOffset + lineNumberTableOffset.toULong()).toInt()
        for (i in 0 until lineNumberEntryCount.toInt()) {
            val recordOffset = tableStart + i * JitFile.LINE_NUMBER_ENTRY_STRIDE
            val startLocation = file.buffer.u8At(recordOffset)
            val line = file.buffer.u4At(recordOffset + 8)
            out += startLocation to line
        }
        out.toList()
    }

    /** Decoded `(code-byte-offset, bytecode-location)` pairs from the address-location map. */
    val addressLocationMap: List<Pair<ULong, ULong>> by lazy {
        val out = ArrayList<Pair<ULong, ULong>>(mapLength.toInt())
        val tableStart = (symbolOffset + addressLocationMapOffset.toULong()).toInt()
        for (i in 0 until mapLength.toInt()) {
            val recordOffset = tableStart + i * JitFile.ADDRESS_LOCATION_MAP_ENTRY_STRIDE
            val startAddress = file.buffer.u8At(recordOffset)
            val location = file.buffer.u8At(recordOffset + 8)
            out += (startAddress - address) to location
        }
        out.toList()
    }

    /** The JIT-compiled code bytes for this method. */
    val codeBlock: ByteArray by lazy {
        val n = codeSize.toInt()
        val start = fileOffset.toInt()
        ByteArray(n) { i -> file.buffer[start + i] }
    }

    /**
     * The method's JVM bytecode bytes captured via `GetBytecodes()` at
     * JIT-compile time. Empty for v0 / v1 dumps that pre-date the agent
     * v2 format extension; also empty for native methods (the JVM
     * reports no bytecode for those).
     */
    val bytecode: ByteArray by lazy {
        val n = bytecodeLength.toInt()
        if (n <= 0) {
            ByteArray(0)
        } else {
            val start = (symbolOffset + bytecodeOffset.toULong()).toInt()
            ByteArray(n) { i -> file.buffer[start + i] }
        }
    }

    /** Inline records (`compiled_method_load_inline_info_t` family). */
    val inlineRecords: List<InlineRecord> by lazy {
        val records = ArrayList<InlineRecord>(inlineCount.toInt())
        var p = (symbolOffset + inlineOffset.toULong()).toInt()
        for (recordIdx in 0 until inlineCount.toInt()) {
            val count = file.buffer.u4At(p); p += 4
            val inlines = ArrayList<InlinedAt>(count.toInt())
            for (i in 0 until count.toInt()) {
                val addr = file.buffer.u8At(p); p += 8
                val frameCount = file.buffer.u4At(p); p += 4
                val frames = ArrayList<Inlined>(frameCount.toInt())
                for (j in 0 until frameCount.toInt()) {
                    val methodStringOffset = file.buffer.u4At(p); p += 4
                    val pathStringOffset = file.buffer.u4At(p); p += 4
                    val bci = file.buffer.u4At(p); p += 4
                    frames += Inlined(
                        sym = symByStringOffset(methodStringOffset),
                        sourcePath = file.string(pathStringOffset),
                        bci = bci.toInt(),
                    )
                }
                inlines += InlinedAt(addr, frames.toList())
            }
            records += InlineRecord(recordIdx.toUInt(), inlines.toList())
        }
        records.toList()
    }

    private fun symByStringOffset(offset: UInt): Sym? {
        val raw = file.string(offset)
        val match = SIGNATURE_REGEX.find(raw) ?: return null
        val (classSignature, classGenericSignature, methodName, methodSignature, methodGenericSignature) =
            match.destructured
        return Sym(
            classSignature = classSignature,
            classGenericSignature = classGenericSignature.takeIf { it != "_" },
            methodName = methodName,
            methodSignature = methodSignature,
            methodGenericSignature = methodGenericSignature.takeIf { it != "_" },
        )
    }

    companion object {
        // Format produced by agent.c's method_string_representation:
        //   [<class_signature>:<class_generic>::<method_name>:<method_signature>:<method_generic>]
        private val SIGNATURE_REGEX: Regex =
            Regex("""^\[([^:.]+):([^:.]+)::([^:.]+):([^:.]+):([^\]]+)\]$""")
    }
}

/** One inlined frame inside a [InlinedAt]. */
data class Inlined(val sym: Sym?, val sourcePath: String, val bci: Int)

/** A point in the JIT code (`address`) whose stack at runtime is the [inlined] list. */
data class InlinedAt(val address: ULong, val inlined: List<Inlined>)

/** A parsed JVMTI symbol — class + method identity with optional generic forms. */
data class Sym(
    val classSignature: String,
    val classGenericSignature: String?,
    val methodName: String,
    val methodSignature: String,
    val methodGenericSignature: String?,
)

/** One inline-info group as written by the agent (per `JVMTI_CMLR_INLINE_INFO`). */
data class InlineRecord(val number: UInt, val inlines: List<InlinedAt>)

// -----------------------------------------------------------------------------
// Little-endian readers over CommonByteBuffer
// -----------------------------------------------------------------------------

private fun CommonByteBuffer.u1At(at: Int): UByte = this[at].toUByte()

@Suppress("unused") // kept for symmetry; not currently consumed
private fun CommonByteBuffer.u2At(at: Int): UShort {
    val lo = this[at].toInt() and 0xFF
    val hi = this[at + 1].toInt() and 0xFF
    return (lo or (hi shl 8)).toUShort()
}

private fun CommonByteBuffer.u4At(at: Int): UInt {
    val b0 = (this[at].toInt() and 0xFF).toUInt()
    val b1 = (this[at + 1].toInt() and 0xFF).toUInt()
    val b2 = (this[at + 2].toInt() and 0xFF).toUInt()
    val b3 = (this[at + 3].toInt() and 0xFF).toUInt()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun CommonByteBuffer.u8At(at: Int): ULong {
    var v = 0uL
    for (i in 0 until 8) {
        v = v or ((this[at + i].toInt() and 0xFF).toULong() shl (i * 8))
    }
    return v
}
