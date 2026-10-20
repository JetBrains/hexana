package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity
import org.jetbrains.hexana.providers.pe.PeOptionalHeader
import org.jetbrains.hexana.providers.pe.PeParser
import org.jetbrains.hexana.providers.pe.PeSection
import org.jetbrains.hexana.providers.pe.byteOrNull
import org.jetbrains.hexana.providers.pe.toBoundedInt
import org.jetbrains.hexana.providers.pe.u16LeOrNull
import org.jetbrains.hexana.providers.pe.u32LeOrNull

/**
 * The precompiled native code of a ReadyToRun (crossgen) image, mapped back to the methods it
 * was compiled from. Format: `docs/design/coreclr/botr/readytorun-format.md` in dotnet/runtime;
 * the entry point tables use the NativeFormat encoding of `src/coreclr/tools/Common/Internal/NativeFormat`.
 */
data class ClrReadyToRunImage(
    val majorVersion: Int,
    val minorVersion: Int,
    val flags: UInt,
    /** Target OS decoded from the COFF machine, e.g. `Windows`, `macOS`. */
    val targetOs: String,
    /** The plain COFF machine (`0x8664`, `0xAA64`, ...) after removing the OS override. */
    val targetMachine: Int,
    val headerRange: NativeByteRange,
    val sections: List<ClrReadyToRunSection>,
    val runtimeFunctions: List<ClrRuntimeFunction>,
    /** Compiled methods, each owning the runtime functions of its main body and its funclets. */
    val methods: List<ClrReadyToRunMethod>,
    /** Import sections: the cells the runtime patches, and the signature RVA table describing them. */
    val importSections: List<ClrReadyToRunImportSection>,
    /** Per-method exception handling blobs, delimited by the ExceptionInfo lookup table. */
    val exceptionInfo: List<NativeByteRange>,
    /**
     * True when function lengths come from the gap to the next function (no length in the
     * runtime function table or its unwind data), so they include alignment padding.
     */
    val codeSizesApproximate: Boolean,
    val diagnostics: List<NativeParserDiagnostic>,
) {
    /** A component of a composite image: its code lives in the composite executable, not here. */
    val isComponent: Boolean get() = flags and FLAG_COMPONENT != 0u

    companion object {
        const val FLAG_COMPONENT: UInt = 0x20u
    }
}

data class ClrReadyToRunSection(
    val type: Int,
    val name: String,
    val rva: UInt,
    val size: UInt,
    val range: NativeByteRange?,
)

data class ClrReadyToRunImportSection(
    val type: Int,
    val flags: Int,
    val entrySize: Int,
    val cells: NativeByteRange?,
    /** One signature RVA per cell; absent for sections without signatures (e.g. eager imports). */
    val signatureTable: NativeByteRange?,
    /** File offsets of the fixup signatures [signatureTable] points to; their sizes are implicit. */
    val signatureOffsets: List<Int>,
    /** File offset of the auxiliary data (GC ref maps for method call thunks), when present. */
    val auxiliaryDataOffset: Int?,
)

data class ClrRuntimeFunction(
    val index: Int,
    val beginRva: UInt,
    /** Code bytes, or null when the length could not be determined. */
    val codeRange: NativeByteRange?,
    val unwindRva: UInt,
)

data class ClrReadyToRunMethod(
    /** MethodDef row in this module, or null for a method of another module. */
    val methodDefRow: Int?,
    /** MemberRef row when the entry names a method through a reference (e.g. `List<MyStruct>.Add`). */
    val memberRefRow: Int?,
    /**
     * Assembly whose method this code was compiled from, when it is not this module: images in a
     * larger version bubble also carry instantiations of other assemblies' generic methods.
     */
    val foreignAssembly: String?,
    /** MethodDef or MemberRef token in [foreignAssembly]'s metadata, which this image does not include. */
    val foreignToken: UInt?,
    /** Generic instantiation (from InstanceMethodEntryPoints) rather than a plain MethodDef. */
    val isInstantiation: Boolean,
    /** Instantiated owner type, e.g. `Box<int>`; null for non-generic owners or when undecodable. */
    val ownerType: String?,
    /** Method type arguments, e.g. `[int]` for `Max<int>`. */
    val methodTypeArguments: List<String>,
    val firstRuntimeFunction: Int,
    val runtimeFunctionCount: Int,
    /** Main body first, then funclets; empty when no length could be determined. */
    val codeRanges: List<NativeByteRange>,
) {
    val codeSize: Int get() = codeRanges.sumOf { it.size }
}

object ClrReadyToRunParser {
    /**
     * Returns null when the image is not ReadyToRun. Malformed tables degrade to fewer methods
     * plus a diagnostic rather than failing.
     */
    fun parse(
        bytes: CommonByteBuffer,
        metadata: ClrMetadata,
        machine: Int,
        optionalHeader: PeOptionalHeader,
        sections: List<PeSection>,
    ): ClrReadyToRunImage? {
        val header = metadata.header
        if (header.managedNativeHeaderRva == 0u) return null
        val resolve = { rva: UInt -> PeParser.rvaToFileOffset(bytes, rva, optionalHeader, sections) }
        val offset = resolve(header.managedNativeHeaderRva) ?: return null
        if (bytes.u32LeOrNull(offset) != READY_TO_RUN_SIGNATURE) return null
        val sectionCount = bytes.u32LeOrNull(offset + 12)?.toBoundedInt()?.takeIf { it <= MAX_SECTIONS } ?: return null
        val diagnostics = mutableListOf<NativeParserDiagnostic>()
        val r2rSections = (0 until sectionCount).mapNotNull { index ->
            val at = offset + 16 + index * 12
            val type = bytes.u32LeOrNull(at)?.toInt() ?: return@mapNotNull null
            val rva = bytes.u32LeOrNull(at + 4) ?: return@mapNotNull null
            val size = bytes.u32LeOrNull(at + 8) ?: return@mapNotNull null
            val start = resolve(rva)
            val length = size.toBoundedInt()
            ClrReadyToRunSection(
                type = type,
                name = sectionName(type),
                rva = rva,
                size = size,
                range = if (start != null && length != null && length <= bytes.size - start) NativeByteRange(start, length) else null,
            )
        }
        val (os, target) = target(machine)
        val runtimeFunctions = parseRuntimeFunctions(bytes, r2rSections, target, resolve)
        val approximate = target !in EXACT_LENGTH_MACHINES
        val methods = Methods(bytes, metadata, r2rSections, runtimeFunctions, diagnostics).decode()
        return ClrReadyToRunImage(
            majorVersion = bytes.u16LeOrNull(offset + 4) ?: return null,
            minorVersion = bytes.u16LeOrNull(offset + 6) ?: return null,
            flags = bytes.u32LeOrNull(offset + 8) ?: return null,
            targetOs = os,
            targetMachine = target,
            headerRange = NativeByteRange(offset, 16 + sectionCount * 12),
            sections = r2rSections,
            runtimeFunctions = runtimeFunctions,
            methods = methods,
            importSections = parseImportSections(bytes, r2rSections, resolve),
            exceptionInfo = parseExceptionInfo(bytes, r2rSections, resolve),
            codeSizesApproximate = approximate,
            diagnostics = diagnostics,
        )
    }

    /**
     * Splits a ReadyToRun COFF machine into its target OS and plain machine. Non-Windows targets
     * XOR the machine with an OS constant (`IMAGE_FILE_MACHINE_*` overrides in the runtime).
     */
    fun target(machine: Int): Pair<String, Int> {
        if (machine in KNOWN_MACHINES) return "Windows" to machine
        for ((os, override) in OS_OVERRIDES) {
            val candidate = machine xor override
            if (candidate in KNOWN_MACHINES) return os to candidate
        }
        return "unknown OS" to machine
    }

    fun sectionName(type: Int): String = SECTION_NAMES[type] ?: "Section $type"

    // region Import sections and exception info

    /** `READYTORUN_IMPORT_SECTION`: cells (RVA, size), flags, type, entry size, signatures RVA, aux data RVA. */
    private fun parseImportSections(bytes: CommonByteBuffer, sections: List<ClrReadyToRunSection>, resolve: (UInt) -> Int?): List<ClrReadyToRunImportSection> {
        val table = sections.firstOrNull { it.type == SECTION_IMPORT_SECTIONS }?.range ?: return emptyList()
        return (0 until table.size / IMPORT_SECTION_SIZE).mapNotNull { index ->
            val at = table.offset + index * IMPORT_SECTION_SIZE
            val cellsRva = bytes.u32LeOrNull(at) ?: return@mapNotNull null
            val cellsSize = bytes.u32LeOrNull(at + 4) ?: return@mapNotNull null
            val entrySize = bytes.byteOrNull(at + 11) ?: return@mapNotNull null
            val signaturesRva = bytes.u32LeOrNull(at + 12) ?: return@mapNotNull null
            val auxiliaryRva = bytes.u32LeOrNull(at + 16) ?: return@mapNotNull null
            val cellCount = if (entrySize > 0) cellsSize / entrySize.toUInt() else 0u
            val signatureTable = rangeAt(bytes, resolve, signaturesRva, cellCount * 4u)
            ClrReadyToRunImportSection(
                type = bytes.byteOrNull(at + 10) ?: return@mapNotNull null,
                flags = bytes.u16LeOrNull(at + 8) ?: return@mapNotNull null,
                entrySize = entrySize,
                cells = rangeAt(bytes, resolve, cellsRva, cellsSize),
                signatureTable = signatureTable,
                signatureOffsets = signatureTable?.let { table ->
                    (0 until table.size / 4).mapNotNull { bytes.u32LeOrNull(table.offset + it * 4)?.takeIf { rva -> rva != 0u }?.let(resolve) }
                }.orEmpty(),
                auxiliaryDataOffset = if (auxiliaryRva != 0u) resolve(auxiliaryRva) else null,
            )
        }
    }

    /**
     * ExceptionInfo is a table of (method start RVA, EH info RVA) pairs sorted by method, ending in
     * a sentinel entry, so each method's EH info runs to the next entry's.
     */
    private fun parseExceptionInfo(bytes: CommonByteBuffer, sections: List<ClrReadyToRunSection>, resolve: (UInt) -> Int?): List<NativeByteRange> {
        val table = sections.firstOrNull { it.type == SECTION_EXCEPTION_INFO }?.range ?: return emptyList()
        val count = table.size / 8
        val infoRvas = (0 until count).map { bytes.u32LeOrNull(table.offset + it * 8 + 4) ?: return emptyList() }
        return infoRvas.zipWithNext().mapNotNull { (start, end) -> if (end > start) rangeAt(bytes, resolve, start, end - start) else null }
    }

    private fun rangeAt(bytes: CommonByteBuffer, resolve: (UInt) -> Int?, rva: UInt, size: UInt): NativeByteRange? {
        if (rva == 0u || size == 0u) return null
        val start = resolve(rva) ?: return null
        val length = size.toBoundedInt() ?: return null
        return if (length <= bytes.size - start) NativeByteRange(start, length) else null
    }

    // endregion

    // region Runtime functions

    private fun parseRuntimeFunctions(
        bytes: CommonByteBuffer,
        sections: List<ClrReadyToRunSection>,
        machine: Int,
        resolve: (UInt) -> Int?,
    ): List<ClrRuntimeFunction> {
        val table = sections.firstOrNull { it.type == SECTION_RUNTIME_FUNCTIONS }?.range ?: return emptyList()
        val entrySize = if (machine == MACHINE_AMD64) 12 else 8
        val count = (table.size / entrySize).coerceAtMost(MAX_RUNTIME_FUNCTIONS)
        val begins = UIntArray(count)
        val ends = arrayOfNulls<UInt>(count)
        val unwinds = UIntArray(count)
        for (index in 0 until count) {
            val at = table.offset + index * entrySize
            val begin = bytes.u32LeOrNull(at) ?: return emptyList()
            if (machine == MACHINE_AMD64) {
                ends[index] = bytes.u32LeOrNull(at + 4) ?: return emptyList()
                unwinds[index] = bytes.u32LeOrNull(at + 8) ?: return emptyList()
            } else {
                unwinds[index] = bytes.u32LeOrNull(at + 4) ?: return emptyList()
            }
            // Thumb code addresses carry the mode bit.
            begins[index] = if (machine == MACHINE_ARMNT) begin and 1u.inv() else begin
            when (machine) {
                MACHINE_ARM64 -> armFunctionLength(bytes, unwinds[index], resolve)?.let { ends[index] = begins[index] + it * 4u }
                MACHINE_ARMNT -> armFunctionLength(bytes, unwinds[index], resolve)?.let { ends[index] = begins[index] + it * 2u }
                MACHINE_I386 -> x86MethodSize(bytes, unwinds[index], resolve)?.let { ends[index] = begins[index] + it }
            }
        }
        // Without a recorded length, a function runs to the next one (the table is sorted by address).
        for (index in 0 until count) {
            if (ends[index] == null && index + 1 < count && begins[index + 1] > begins[index]) ends[index] = begins[index + 1]
        }
        return List(count) { index ->
            val begin = begins[index]
            val length = ends[index]?.takeIf { it >= begin }?.let { (it - begin).toBoundedInt() }
            val start = resolve(begin)
            ClrRuntimeFunction(
                index = index,
                beginRva = begin,
                codeRange = if (start != null && length != null && length <= bytes.size - start) NativeByteRange(start, length) else null,
                unwindRva = unwinds[index],
            )
        }
    }

    /**
     * Function length in instruction units from ARM/ARM64 unwind data: packed into the runtime
     * function entry when its flag bits are set, otherwise in the first word of the `.xdata` record.
     */
    private fun armFunctionLength(bytes: CommonByteBuffer, unwindData: UInt, resolve: (UInt) -> Int?): UInt? {
        if (unwindData and 3u != 0u) return (unwindData shr 2) and 0x7FFu
        val offset = resolve(unwindData) ?: return null
        return bytes.u32LeOrNull(offset)?.and(0x3FFFFu)
    }

    /**
     * x86 has no OS unwind data: the entry points at the method's GC info, whose header starts
     * with the method size as a big-endian 7-bit varint (`decodeUnsigned` in CoreCLR's gcdecoder).
     */
    private fun x86MethodSize(bytes: CommonByteBuffer, gcInfo: UInt, resolve: (UInt) -> Int?): UInt? {
        var offset = resolve(gcInfo) ?: return null
        var value = 0u
        repeat(5) {
            val byte = bytes.byteOrNull(offset++) ?: return null
            value = (value shl 7) or (byte and 0x7F).toUInt()
            if (byte and 0x80 == 0) return value
        }
        return null
    }

    // endregion

    // region Method entry points

    private class Methods(
        private val bytes: CommonByteBuffer,
        private val metadata: ClrMetadata,
        private val sections: List<ClrReadyToRunSection>,
        private val runtimeFunctions: List<ClrRuntimeFunction>,
        private val diagnostics: MutableList<NativeParserDiagnostic>,
    ) {
        private val native = NativeReader(bytes)

        /**
         * Names of the modules `ELEMENT_TYPE_MODULE_ZAPSIG` and `UpdateContext` refer to. Index `i`
         * in `1..n` is the module's own AssemblyRef `i`; past that, index `n + 1 + k` is AssemblyRef
         * `k` of the ManifestMetadata section. The offset was derived from crossgen output rather
         * than the format document, and holds for images referencing 1, 5 and 30 extra assemblies.
         */
        private val manifestAssemblyRefs: List<ClrAssemblyRef> by lazy {
            sections.firstOrNull { it.type == SECTION_MANIFEST_METADATA }?.range
                ?.let { ClrMetadataParser.parseStandalone(bytes, it).assemblyRefs }
                .orEmpty()
        }

        private fun moduleName(index: Int): String? {
            val own = metadata.assemblyRefs
            return when {
                index in 1..own.size -> own[index - 1].name
                index > own.size + 1 -> manifestAssemblyRefs.getOrNull(index - own.size - 2)?.name
                else -> null
            }
        }

        private class Entry(
            val methodDefRow: Int?,
            val memberRefRow: Int?,
            val foreignAssembly: String?,
            val foreignToken: UInt?,
            val isInstantiation: Boolean,
            val ownerType: String?,
            val methodTypeArguments: List<String>,
            val runtimeFunction: Int,
        )

        fun decode(): List<ClrReadyToRunMethod> {
            if (runtimeFunctions.isEmpty()) return emptyList()
            val entries = ArrayList<Entry>()
            try {
                sections.firstOrNull { it.type == SECTION_METHOD_DEF_ENTRY_POINTS }?.range?.let { decodeMethodDefEntryPoints(it, entries) }
                sections.firstOrNull { it.type == SECTION_INSTANCE_METHOD_ENTRY_POINTS }?.range?.let { decodeInstanceEntryPoints(it, entries) }
            } catch (_: MalformedNativeData) {
                diagnostics += NativeParserDiagnostic(
                    code = "clr.r2r.invalid_entry_points",
                    message = "ReadyToRun method entry point tables are malformed; some compiled methods are not attributed.",
                    severity = NativeParserSeverity.Warning,
                )
            }
            // A method owns its entry function plus the funclets after it, up to the next entry.
            val starts = entries.map { it.runtimeFunction }.filter { it in runtimeFunctions.indices }.distinct().sorted().toIntArray()
            val seen = HashSet<Int>()
            return entries.mapNotNull { entry ->
                val first = entry.runtimeFunction
                if (first !in runtimeFunctions.indices || !seen.add(first)) return@mapNotNull null
                val next = starts.firstAfter(first) ?: runtimeFunctions.size
                ClrReadyToRunMethod(
                    methodDefRow = entry.methodDefRow,
                    memberRefRow = entry.memberRefRow,
                    foreignAssembly = entry.foreignAssembly,
                    foreignToken = entry.foreignToken,
                    isInstantiation = entry.isInstantiation,
                    ownerType = entry.ownerType,
                    methodTypeArguments = entry.methodTypeArguments,
                    firstRuntimeFunction = first,
                    runtimeFunctionCount = next - first,
                    codeRanges = (first until next).mapNotNull { runtimeFunctions[it].codeRange },
                )
            }
        }

        private fun decodeMethodDefEntryPoints(section: NativeByteRange, entries: MutableList<Entry>) {
            val array = NativeArray(native, section.offset, section.offset + section.size)
            val count = array.count.coerceAtMost(metadata.methods.size)
            for (row in 1..count) {
                val offset = array.offsetOf(row - 1) ?: continue
                entries += Entry(
                    methodDefRow = row,
                    memberRefRow = null,
                    foreignAssembly = null,
                    foreignToken = null,
                    isInstantiation = false,
                    ownerType = null,
                    methodTypeArguments = emptyList(),
                    runtimeFunction = runtimeFunctionIndex(offset),
                )
            }
        }

        private fun decodeInstanceEntryPoints(section: NativeByteRange, entries: MutableList<Entry>) {
            val table = NativeHashtable(native, section.offset, section.offset + section.size)
            for (entryOffset in table.entries()) {
                entries += decodeInstanceEntry(entryOffset) ?: continue
            }
        }

        /**
         * An R2R method signature (`READYTORUN_METHOD_SIG_*` flags, then owner type, token and
         * instantiation) and its entry point. The token lives in the `UpdateContext` module, or —
         * when the owner type is wrapped in a module override — in the owner type's module.
         */
        private fun decodeInstanceEntry(offset: Int): Entry? {
            val signature = R2rSignatureCursor(bytes, offset, metadata, ::moduleName)
            val flags = signature.uint()
            var module: Int? = null
            if (flags and METHOD_SIG_UPDATE_CONTEXT != 0) module = signature.uint()
            val ownerType = if (flags and METHOD_SIG_OWNER_TYPE != 0) {
                signature.type(module).also { if (module == null) module = signature.outerModule }
            } else {
                null
            }
            if (flags and METHOD_SIG_SLOT_INSTEAD_OF_TOKEN != 0) return null
            val row = signature.uint()
            val methodTypeArguments = if (flags and METHOD_SIG_METHOD_INSTANTIATION != 0) {
                List(signature.count()) { signature.type(null) }
            } else {
                emptyList()
            }
            if (flags and METHOD_SIG_CONSTRAINED != 0) signature.type(null)
            val isMemberRef = flags and METHOD_SIG_MEMBER_REF_TOKEN != 0
            val foreign = module != null
            return Entry(
                methodDefRow = if (!foreign && !isMemberRef) row else null,
                memberRefRow = if (!foreign && isMemberRef) row else null,
                foreignAssembly = module?.let { moduleName(it) ?: "module #$it" },
                foreignToken = if (foreign) clrToken(if (isMemberRef) ClrTableId.MEMBER_REF else ClrTableId.METHOD_DEF, row) else null,
                isInstantiation = true,
                ownerType = ownerType,
                methodTypeArguments = methodTypeArguments,
                runtimeFunction = runtimeFunctionIndex(signature.position),
            )
        }

        /** Entry point encoding shared by both tables: the runtime function index, optionally with fixups. */
        private fun runtimeFunctionIndex(offset: Int): Int {
            val id = native.unsigned(offset).value
            return if (id and 1 != 0) id ushr 2 else id ushr 1
        }
    }

    // endregion
}

private class MalformedNativeData : RuntimeException()

/** Smallest element greater than [value] in this ascending array, or null. */
private fun IntArray.firstAfter(value: Int): Int? {
    var low = 0
    var high = size - 1
    var result: Int? = null
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid] > value) {
            result = this[mid]
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return result
}

/** Variable-length integers of the NativeFormat encoding (low bits of the first byte give the length). */
private class NativeReader(private val bytes: CommonByteBuffer) {
    class Decoded(val value: Int, val next: Int)

    fun byte(offset: Int): Int = bytes.byteOrNull(offset) ?: throw MalformedNativeData()

    fun unsigned(offset: Int): Decoded {
        val first = byte(offset)
        return when {
            first and 1 == 0 -> Decoded(first ushr 1, offset + 1)
            first and 2 == 0 -> Decoded((first ushr 2) or (byte(offset + 1) shl 6), offset + 2)
            first and 4 == 0 -> Decoded((first ushr 3) or (byte(offset + 1) shl 5) or (byte(offset + 2) shl 13), offset + 3)
            first and 8 == 0 -> Decoded(
                (first ushr 4) or (byte(offset + 1) shl 4) or (byte(offset + 2) shl 12) or (byte(offset + 3) shl 20),
                offset + 4,
            )
            first and 16 == 0 -> Decoded(bytes.u32LeOrNull(offset + 1)?.toInt() ?: throw MalformedNativeData(), offset + 5)
            else -> throw MalformedNativeData()
        }
    }

    fun signed(offset: Int): Decoded {
        val first = byte(offset)
        return when {
            first and 1 == 0 -> Decoded(first.toByte().toInt() shr 1, offset + 1)
            first and 2 == 0 -> Decoded((first ushr 2) or (byte(offset + 1).toByte().toInt() shl 6), offset + 2)
            first and 4 == 0 -> Decoded((first ushr 3) or (byte(offset + 1) shl 5) or (byte(offset + 2).toByte().toInt() shl 13), offset + 3)
            first and 8 == 0 -> Decoded(
                (first ushr 4) or (byte(offset + 1) shl 4) or (byte(offset + 2) shl 12) or (byte(offset + 3).toByte().toInt() shl 20),
                offset + 4,
            )
            first and 16 == 0 -> Decoded(bytes.u32LeOrNull(offset + 1)?.toInt() ?: throw MalformedNativeData(), offset + 5)
            else -> throw MalformedNativeData()
        }
    }

    fun u16(offset: Int): Int = bytes.u16LeOrNull(offset) ?: throw MalformedNativeData()

    fun u32(offset: Int): Int = bytes.u32LeOrNull(offset)?.toInt() ?: throw MalformedNativeData()
}

/** NativeFormat sparse array: a header, then per-16-element blocks encoded as small binary trees. */
private class NativeArray(private val reader: NativeReader, offset: Int, private val end: Int) {
    private val base: Int
    val count: Int
    private val entryIndexSize: Int

    init {
        val header = reader.unsigned(offset)
        base = header.next
        count = header.value ushr 2
        entryIndexSize = header.value and 3
    }

    /** File offset of element [index]'s data, or null when the element is absent. */
    fun offsetOf(index: Int): Int? {
        if (index < 0 || index >= count) return null
        var offset = when (entryIndexSize) {
            0 -> reader.byte(base + index / BLOCK_SIZE)
            1 -> reader.u16(base + 2 * (index / BLOCK_SIZE))
            else -> reader.u32(base + 4 * (index / BLOCK_SIZE))
        } + base
        var bit = BLOCK_SIZE shr 1
        while (bit > 0) {
            if (offset !in base until end) throw MalformedNativeData()
            val node = reader.unsigned(offset)
            if (index and bit != 0) {
                if (node.value and 2 != 0) {
                    offset += node.value ushr 2
                    bit = bit shr 1
                    continue
                }
            } else if (node.value and 1 != 0) {
                offset = node.next
                bit = bit shr 1
                continue
            }
            // A leaf: either the element itself or a miss.
            if (node.value and 3 == 0 && (node.value ushr 2) == (index and (BLOCK_SIZE - 1))) {
                offset = node.next
                break
            }
            return null
        }
        return offset
    }

    companion object {
        const val BLOCK_SIZE = 16
    }
}

/** NativeFormat hashtable: a header byte, a bucket offset table, then entries addressed by relative offsets. */
private class NativeHashtable(private val reader: NativeReader, offset: Int, private val end: Int) {
    private val base: Int = offset + 1
    private val bucketMask: Int
    private val entryIndexSize: Int

    init {
        val header = reader.byte(offset)
        val bucketShift = header ushr 2
        if (bucketShift > 30) throw MalformedNativeData()
        bucketMask = (1 shl bucketShift) - 1
        entryIndexSize = header and 3
        if (entryIndexSize > 2) throw MalformedNativeData()
    }

    /** File offsets of every entry, bucket by bucket. */
    fun entries(): List<Int> {
        val result = ArrayList<Int>()
        for (bucket in 0..bucketMask) {
            var position = bucketOffset(bucket) + base
            val bucketEnd = bucketOffset(bucket + 1) + base
            if (bucketEnd > end || position > bucketEnd) throw MalformedNativeData()
            while (position < bucketEnd) {
                position++ // low byte of the entry's hash code
                val delta = reader.signed(position)
                val entry = position + delta.value
                if (entry !in base until end) throw MalformedNativeData()
                result += entry
                position = delta.next
                if (result.size > MAX_INSTANCE_ENTRIES) throw MalformedNativeData()
            }
        }
        return result
    }

    private fun bucketOffset(bucket: Int): Int = when (entryIndexSize) {
        0 -> reader.byte(base + bucket)
        1 -> reader.u16(base + 2 * bucket)
        else -> reader.u32(base + 4 * bucket)
    }
}

/**
 * Reads R2R signatures: ECMA-335 compressed integers and type signatures, plus the crossgen
 * extensions (`ELEMENT_TYPE_MODULE_ZAPSIG`, `ELEMENT_TYPE_CANON_ZAPSIG`, ...). Types render as
 * short C#-like names (`Box<int>`); tokens of another module render as `?`.
 */
private class R2rSignatureCursor(
    private val bytes: CommonByteBuffer,
    var position: Int,
    private val metadata: ClrMetadata,
    private val moduleNames: (Int) -> String?,
) {
    /** Module override that wrapped the last top-level [type], or null when it was in this module. */
    var outerModule: Int? = null
        private set

    private fun u8(): Int = (bytes.byteOrNull(position) ?: throw MalformedNativeData()).also { position++ }

    fun uint(): Int {
        val first = u8()
        return when {
            first and 0x80 == 0 -> first
            first and 0xC0 == 0x80 -> ((first and 0x3F) shl 8) or u8()
            first and 0xE0 == 0xC0 -> ((first and 0x1F) shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
            else -> throw MalformedNativeData()
        }
    }

    fun count(): Int = uint().also { if (it > MAX_SIGNATURE_ITEMS) throw MalformedNativeData() }

    /** Renders one type; [module] is the module override in effect (null: this module). */
    fun type(module: Int?, depth: Int = 0): String {
        if (depth > MAX_SIGNATURE_DEPTH) throw MalformedNativeData()
        if (depth == 0) outerModule = null
        return when (val element = u8()) {
            in PRIMITIVE_NAMES -> PRIMITIVE_NAMES.getValue(element)
            0x0F -> type(module, depth + 1) + "*"
            0x10 -> "ref " + type(module, depth + 1)
            0x11, 0x12 -> typeToken(uint(), module)
            0x13 -> "!" + uint()
            0x1E -> "!!" + uint()
            0x14 -> {
                val elementType = type(module, depth + 1)
                val rank = uint()
                repeat(count()) { uint() }
                repeat(count()) { uint() }
                elementType + "[" + ",".repeat((rank - 1).coerceIn(0, 31)) + "]"
            }
            0x15 -> {
                val generic = type(module, depth + 1)
                val arguments = List(count()) { type(module, depth + 1) }
                generic + arguments.joinToString(", ", "<", ">")
            }
            0x1B -> {
                val callingConvention = u8()
                if (callingConvention and 0x10 != 0) uint()
                val parameters = count()
                type(module, depth + 1)
                repeat(parameters) { type(module, depth + 1) }
                "fnptr"
            }
            0x1D -> type(module, depth + 1) + "[]"
            0x1F, 0x20 -> {
                uint()
                type(module, depth + 1)
            }
            0x45 -> type(module, depth + 1)
            ELEMENT_TYPE_CANON_ZAPSIG -> "__Canon"
            ELEMENT_TYPE_MODULE_ZAPSIG -> {
                val override = uint()
                if (depth == 0) outerModule = override
                type(override, depth + 1).also { if (depth == 0) outerModule = override }
            }
            ELEMENT_TYPE_NATIVE_VALUETYPE_ZAPSIG -> type(module, depth + 1)
            else -> throw MalformedNativeData()
        }
    }

    /**
     * A `TypeDefOrRefOrSpecEncoded` value, named from this module's metadata when it is ours. Reads
     * only the already-decoded type lists, never [ClrMetadata.tokens], which is not thread-safe.
     */
    private fun typeToken(encoded: Int, module: Int?): String {
        val row = encoded ushr 2
        if (module != null) {
            // Another assembly's metadata is not in this image: name the token and where it lives.
            val table = when (encoded and 3) {
                0 -> ClrTableId.TYPE_DEF
                1 -> ClrTableId.TYPE_REF
                else -> ClrTableId.TYPE_SPEC
            }
            return "[${moduleNames(module) ?: "module #$module"}]0x" + clrToken(table, row).toString(16).uppercase().padStart(8, '0')
        }
        val name = when (encoded and 3) {
            0 -> metadata.typeDefs.getOrNull(row - 1)?.fullName
            1 -> metadata.typeRefs.getOrNull(row - 1)?.fullName
            else -> null
        } ?: return "?"
        // `System.Collections.Generic.List`1` -> `List`; arguments follow separately. Compiler-generated
        // names such as `Outer+<>c__DisplayClass0_0` contain `<`, so only the arity suffix is cut.
        val simple = if ('+' in name) name.substringAfterLast('+') else name.substringAfterLast('.')
        return simple.replace(GENERIC_ARITY_SUFFIX, "")
    }

    companion object {
        private val PRIMITIVE_NAMES = mapOf(
            0x01 to "void", 0x02 to "bool", 0x03 to "char", 0x04 to "sbyte", 0x05 to "byte", 0x06 to "short",
            0x07 to "ushort", 0x08 to "int", 0x09 to "uint", 0x0A to "long", 0x0B to "ulong", 0x0C to "float",
            0x0D to "double", 0x0E to "string", 0x16 to "typedref", 0x18 to "nint", 0x19 to "nuint", 0x1C to "object",
        )
    }
}

private const val READY_TO_RUN_SIGNATURE = 0x00525452u
private val GENERIC_ARITY_SUFFIX = Regex("`[0-9]+$")
private const val MAX_SECTIONS = 256
private const val MAX_RUNTIME_FUNCTIONS = 4_000_000
private const val MAX_INSTANCE_ENTRIES = 4_000_000
private const val MAX_SIGNATURE_ITEMS = 1024
private const val MAX_SIGNATURE_DEPTH = 32

private const val SECTION_IMPORT_SECTIONS = 101
private const val SECTION_RUNTIME_FUNCTIONS = 102
private const val SECTION_EXCEPTION_INFO = 104
private const val IMPORT_SECTION_SIZE = 20
private const val SECTION_METHOD_DEF_ENTRY_POINTS = 103
private const val SECTION_INSTANCE_METHOD_ENTRY_POINTS = 109
private const val SECTION_MANIFEST_METADATA = 112

private const val METHOD_SIG_METHOD_INSTANTIATION = 0x04
private const val METHOD_SIG_SLOT_INSTEAD_OF_TOKEN = 0x08
private const val METHOD_SIG_MEMBER_REF_TOKEN = 0x10
private const val METHOD_SIG_CONSTRAINED = 0x20
private const val METHOD_SIG_OWNER_TYPE = 0x40
private const val METHOD_SIG_UPDATE_CONTEXT = 0x80

private const val ELEMENT_TYPE_NATIVE_VALUETYPE_ZAPSIG = 0x3D
private const val ELEMENT_TYPE_CANON_ZAPSIG = 0x3E
private const val ELEMENT_TYPE_MODULE_ZAPSIG = 0x3F

private const val MACHINE_I386 = 0x014C
private const val MACHINE_AMD64 = 0x8664
private const val MACHINE_ARM64 = 0xAA64
private const val MACHINE_ARMNT = 0x01C4

/** Machines whose runtime functions record an exact code length (explicitly, in unwind data or GC info). */
private val EXACT_LENGTH_MACHINES = setOf(MACHINE_I386, MACHINE_AMD64, MACHINE_ARM64, MACHINE_ARMNT)

private val KNOWN_MACHINES = setOf(MACHINE_I386, MACHINE_AMD64, MACHINE_ARM64, MACHINE_ARMNT, 0x6264, 0x5064)

private val OS_OVERRIDES = listOf(
    "macOS" to 0x4644,
    "Linux" to 0x7B79,
    "FreeBSD" to 0xADC4,
    "NetBSD" to 0x1993,
    "SunOS" to 0x1992,
)

/** `ReadyToRunSectionType` names. */
private val SECTION_NAMES = mapOf(
    100 to "CompilerIdentifier",
    101 to "ImportSections",
    102 to "RuntimeFunctions",
    103 to "MethodDefEntryPoints",
    104 to "ExceptionInfo",
    105 to "DebugInfo",
    106 to "DelayLoadMethodCallThunks",
    107 to "AvailableTypes (obsolete)",
    108 to "AvailableTypes",
    109 to "InstanceMethodEntryPoints",
    110 to "InliningInfo",
    111 to "ProfileDataInfo",
    112 to "ManifestMetadata",
    113 to "AttributePresence",
    114 to "InliningInfo2",
    115 to "ComponentAssemblies",
    116 to "OwnerCompositeExecutable",
    117 to "PgoInstrumentationData",
    118 to "ManifestAssemblyMvids",
    119 to "CrossModuleInlineInfo",
    120 to "HotColdMap",
    121 to "MethodIsGenericMap",
    122 to "EnclosingTypeMap",
    123 to "TypeGenericInfoMap",
)
