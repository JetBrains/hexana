package org.jetbrains.hexana.providers

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.pe.PeDebugEntry
import org.jetbrains.hexana.providers.pe.PeOptionalHeader
import org.jetbrains.hexana.providers.pe.PeParser
import org.jetbrains.hexana.providers.pe.PeSection
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMethodDef
import org.jetbrains.hexana.providers.pe.clr.ClrReadyToRunImage
import org.jetbrains.hexana.providers.pe.clr.ClrReadyToRunMethod
import org.jetbrains.hexana.providers.pe.clr.ClrTypeDef
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.io.CommonByteBuffer

/** Group names of the managed size breakdown; also the labels the Top tab shows. */
object ClrSizeGroups {
    const val NATIVE_CODE: String = "Native code (ReadyToRun)"
    const val FOREIGN_CODE: String = "Compiled from other assemblies"
    const val OTHER_NATIVE_CODE: String = "Other compiled code"
    const val CODE_GAPS: String = "Alignment and read-only data"
    const val READY_TO_RUN_DATA: String = "ReadyToRun data"
    const val IL_BODIES: String = "IL method bodies"
    const val METADATA: String = "CLR metadata"
    const val RESOURCES: String = "Managed resources"
    const val CLI_HEADER: String = "CLI header"
    const val STRONG_NAME: String = "Strong-name signature"
    const val PE_DATA: String = "PE data directories"
    const val PADDING: String = "Padding"
    const val UNATTRIBUTED: String = "Unattributed"
}

/**
 * Size breakdown of a managed PE image: every section, with the bytes inside it attributed to
 * what owns them — compiled methods (grouped namespace → type → method), ReadyToRun tables,
 * IL bodies, metadata tables and streams, resources and PE data directories. Whatever is left is an
 * explicit [ClrSizeGroups.UNATTRIBUTED] node, so the children of a section always add up to it.
 *
 * A byte is attributed once: categories are claimed in a fixed priority order and later claims are
 * clipped to what is still free (on Windows, for instance, the exception directory *is* the
 * ReadyToRun RuntimeFunctions table, and is reported as the latter).
 */
internal fun clrSizeAnalysis(
    bytes: CommonByteBuffer,
    sections: List<PeSection>,
    optionalHeader: PeOptionalHeader,
    debugEntries: List<PeDebugEntry>,
    metadata: ClrMetadata,
    readyToRun: ClrReadyToRunImage?,
): BinarySizeNode {
    val claims = clrSizeClaims(bytes, sections, optionalHeader, debugEntries, metadata, readyToRun)
    val children = sections.map { section ->
        val start = section.rawDataPointer.toLong()
        val end = start + section.rawDataSize.toLong()
        val root = TreeNode(section.name.ifBlank { "<unnamed>" })
        val inside = claims.filter { it.start >= start && it.end <= end }.sortedBy { it.start }
        for (claim in inside) root.add(claim.path, claim.start, claim.end - claim.start)
        if (inside.isNotEmpty()) addGaps(bytes, root, inside, start, end)
        root.toSizeNode(sizeOverride = section.rawDataSize.toInt(), offsetOverride = start.toInt())
    }
    return BinarySizeNode(name = "PE", size = bytes.size, offset = 0, children = children)
}

/** Every attributed byte range, without overlaps; [clrSizeAnalysis] arranges them per section. */
internal fun clrSizeClaims(
    bytes: CommonByteBuffer,
    sections: List<PeSection>,
    optionalHeader: PeOptionalHeader,
    debugEntries: List<PeDebugEntry>,
    metadata: ClrMetadata,
    readyToRun: ClrReadyToRunImage?,
): List<Claim> {
    val names = MethodNames(metadata)
    val categories = buildList {
        add(cliHeaderClaims(bytes, metadata, optionalHeader, sections))
        add(metadataTableClaims(metadata))
        add(metadataStreamClaims(metadata))
        if (readyToRun != null) {
            add(nativeCodeClaims(readyToRun, names))
            add(readyToRunDataClaims(bytes, readyToRun))
        }
        add(ilBodyClaims(metadata, names))
        add(resourceClaims(metadata))
        add(peDataClaims(bytes, optionalHeader, sections, debugEntries))
    }
    val firstPass = resolveOverlaps(categories)
    // Unwind/GC info, fixup signatures and GC ref maps are runs of blobs whose sizes are implicit:
    // each runs to the next one, and the last to the next byte something else already claimed.
    val implicit = readyToRun?.let { implicitSizeClaims(bytes, it, optionalHeader, sections, firstPass) }.orEmpty()
    return if (implicit.isEmpty()) firstPass else resolveOverlaps(categories + listOf(implicit))
}

// region Claims

/** File bytes `[start, end)` attributed to [path] (group names from the section down to the leaf). */
internal class Claim(val path: List<String>, val start: Long, val end: Long)

private fun claim(path: List<String>, range: NativeByteRange?): Claim? =
    range?.takeIf { it.size > 0 }?.let { Claim(path, it.offset.toLong(), it.offset.toLong() + it.size) }

private fun cliHeaderClaims(bytes: CommonByteBuffer, metadata: ClrMetadata, optionalHeader: PeOptionalHeader, sections: List<PeSection>): List<Claim> {
    val header = metadata.header
    return listOfNotNull(
        claim(listOf(ClrSizeGroups.CLI_HEADER), header.headerRange),
        claim(
            listOf(ClrSizeGroups.STRONG_NAME),
            PeParser.rvaRange(bytes, header.strongNameSignatureRva, header.strongNameSignatureSize, optionalHeader, sections),
        ),
    )
}

private fun metadataTableClaims(metadata: ClrMetadata): List<Claim> = buildList {
    val tablesStream = metadata.streams.firstOrNull { it.name == "#~" || it.name == "#-" }?.name ?: "#~"
    metadata.tablesHeader?.let { claim(listOf(ClrSizeGroups.METADATA, tablesStream, "Tables header"), it.range)?.let(::add) }
    for (table in metadata.tables) {
        claim(listOf(ClrSizeGroups.METADATA, tablesStream, table.name), table.range)?.let(::add)
    }
}

/** Whole streams and the metadata root; after clipping only what the tables do not cover remains. */
private fun metadataStreamClaims(metadata: ClrMetadata): List<Claim> = buildList {
    metadata.root?.let { claim(listOf(ClrSizeGroups.METADATA, "Metadata root"), it.range)?.let(::add) }
    for (stream in metadata.streams) {
        claim(listOf(ClrSizeGroups.METADATA, "Stream headers"), stream.headerRange)?.let(::add)
        val path = if (stream.name == "#~" || stream.name == "#-") {
            listOf(ClrSizeGroups.METADATA, stream.name, "Padding")
        } else {
            listOf(ClrSizeGroups.METADATA, stream.name)
        }
        claim(path, stream.contentRange)?.let(::add)
    }
}

private fun nativeCodeClaims(image: ClrReadyToRunImage, names: MethodNames): List<Claim> {
    val claims = ArrayList<Claim>()
    val owned = BooleanArray(image.runtimeFunctions.size)
    for (method in image.methods) {
        val path = listOf(ClrSizeGroups.NATIVE_CODE) + names.nativePath(method)
        for (range in method.codeRanges) claim(path, range)?.let(claims::add)
        for (index in method.firstRuntimeFunction until method.firstRuntimeFunction + method.runtimeFunctionCount) owned[index] = true
    }
    for (function in image.runtimeFunctions) {
        if (!owned[function.index]) claim(listOf(ClrSizeGroups.NATIVE_CODE, ClrSizeGroups.OTHER_NATIVE_CODE), function.codeRange)?.let(claims::add)
    }
    return claims
}

private fun readyToRunDataClaims(bytes: CommonByteBuffer, image: ClrReadyToRunImage): List<Claim> = buildList {
    val data = ClrSizeGroups.READY_TO_RUN_DATA
    claim(listOf(data, "Header"), image.headerRange)?.let(::add)
    for (section in image.sections) {
        // crossgen ends the RuntimeFunctions table with a 0xFFFFFFFF terminator the section size leaves out.
        val range = section.range?.let { range ->
            val end = range.offset + range.size
            val terminated = section.name == "RuntimeFunctions" && end + 4 <= bytes.size && (0 until 4).all { bytes[end + it] == 0xFF.toByte() }
            if (terminated) NativeByteRange(range.offset, range.size + 4) else range
        }
        claim(listOf(data, section.name), range)?.let(::add)
    }
    for (import in image.importSections) {
        claim(listOf(data, "Import cells"), import.cells)?.let(::add)
        claim(listOf(data, "Import signature tables"), import.signatureTable)?.let(::add)
    }
    for (info in image.exceptionInfo) claim(listOf(data, "Exception handling info"), info)?.let(::add)
}

private fun implicitSizeClaims(
    bytes: CommonByteBuffer,
    image: ClrReadyToRunImage,
    optionalHeader: PeOptionalHeader,
    sections: List<PeSection>,
    claimed: List<Claim>,
): List<Claim> {
    val claimedStarts = claimed.map { it.start }.sorted().toLongArray()
    // ARM/ARM64 entries whose low bits are set carry packed unwind data instead of an RVA.
    val armPacked = image.targetMachine == 0xAA64 || image.targetMachine == 0x01C4
    val unwind = image.runtimeFunctions.asSequence()
        .filter { !armPacked || it.unwindRva and 3u == 0u }
        .mapNotNull { PeParser.rvaRange(bytes, it.unwindRva, 1u, optionalHeader, sections)?.offset?.toLong() }
    val data = ClrSizeGroups.READY_TO_RUN_DATA
    return implicitBlobs(listOf(data, "Unwind and GC info"), unwind, claimedStarts) +
        implicitBlobs(listOf(data, "Import signatures"), image.importSections.asSequence().flatMap { it.signatureOffsets }.map { it.toLong() }, claimedStarts) +
        implicitBlobs(listOf(data, "GC ref maps"), image.importSections.asSequence().mapNotNull { it.auxiliaryDataOffset?.toLong() }, claimedStarts)
}

/** Blobs laid out back to back: each one ends where the next begins, or at the next claimed byte. */
private fun implicitBlobs(path: List<String>, offsets: Sequence<Long>, claimedStarts: LongArray): List<Claim> {
    val starts = offsets.distinct().sorted().toList()
    return starts.mapIndexed { index, start ->
        val next = starts.getOrNull(index + 1) ?: Long.MAX_VALUE
        val nextClaim = claimedStarts.firstAtOrAfter(start + 1) ?: Long.MAX_VALUE
        Claim(path, start, minOf(next, nextClaim, start + MAX_IMPLICIT_BLOB))
    }
}

private fun LongArray.firstAtOrAfter(value: Long): Long? {
    var low = 0
    var high = size - 1
    var result: Long? = null
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid] >= value) {
            result = this[mid]
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return result
}

private fun ilBodyClaims(metadata: ClrMetadata, names: MethodNames): List<Claim> = buildList {
    for (method in metadata.methods) {
        val body = method.body ?: continue
        val path = listOf(ClrSizeGroups.IL_BODIES) + names.ilPath(method)
        claim(path, body.range)?.let(::add)
        claim(path, body.exceptionSectionsRange)?.let(::add)
    }
}

private fun resourceClaims(metadata: ClrMetadata): List<Claim> = metadata.manifestResources.mapNotNull { resource ->
    // Include the 4-byte length prefix that precedes each embedded resource.
    val data = resource.dataRange ?: return@mapNotNull null
    claim(listOf(ClrSizeGroups.RESOURCES, resource.name), NativeByteRange(data.offset - 4, data.size + 4))
}

private fun peDataClaims(
    bytes: CommonByteBuffer,
    optionalHeader: PeOptionalHeader,
    sections: List<PeSection>,
    debugEntries: List<PeDebugEntry>,
): List<Claim> = buildList {
    for (directory in optionalHeader.dataDirectories) {
        // The CLR header is reported on its own; the certificate directory holds file offsets, not RVAs.
        if (directory.index == CLR_DIRECTORY || directory.index == CERTIFICATE_DIRECTORY) continue
        claim(listOf(ClrSizeGroups.PE_DATA, directory.name), PeParser.rvaRange(bytes, directory.rva, directory.size, optionalHeader, sections))?.let(::add)
    }
    for (entry in debugEntries) claim(listOf(ClrSizeGroups.PE_DATA, "Debug data"), entry.dataRange)?.let(::add)
}

/**
 * Clips every claim against the bytes claimed by earlier categories (and earlier claims of the
 * same category). Each category is sorted once and merged against the running coverage, so this
 * stays linear in the number of claims after sorting — CoreLib has around a hundred thousand.
 */
private fun resolveOverlaps(categories: List<List<Claim>>): List<Claim> {
    val result = ArrayList<Claim>()
    var covered = LongArray(0) // merged [start, end) pairs, ascending
    for (category in categories) {
        val sorted = category.sortedBy { it.start }
        val accepted = ArrayList<Claim>(sorted.size)
        var cursor = 0
        var claimedUntil = Long.MIN_VALUE
        for (claim in sorted) {
            var start = maxOf(claim.start, claimedUntil)
            val end = claim.end
            while (cursor < covered.size && covered[cursor + 1] <= start) cursor += 2
            var scan = cursor
            while (start < end) {
                if (scan >= covered.size || covered[scan] >= end) {
                    accepted += Claim(claim.path, start, end)
                    break
                }
                if (covered[scan] > start) accepted += Claim(claim.path, start, covered[scan])
                start = maxOf(start, covered[scan + 1])
                scan += 2
            }
            claimedUntil = maxOf(claimedUntil, end)
        }
        result += accepted
        covered = mergeCoverage(covered, accepted)
    }
    return result
}

private fun mergeCoverage(covered: LongArray, added: List<Claim>): LongArray {
    val starts = LongArray(covered.size / 2 + added.size)
    val ends = LongArray(starts.size)
    var count = 0
    var i = 0
    var j = 0
    while (i < covered.size || j < added.size) {
        val takeCovered = j >= added.size || (i < covered.size && covered[i] <= added[j].start)
        val (s, e) = if (takeCovered) (covered[i] to covered[i + 1]).also { i += 2 } else (added[j].start to added[j].end).also { j++ }
        if (count > 0 && s <= ends[count - 1]) {
            ends[count - 1] = maxOf(ends[count - 1], e)
        } else {
            starts[count] = s
            ends[count] = e
            count++
        }
    }
    return LongArray(count * 2) { if (it % 2 == 0) starts[it / 2] else ends[it / 2] }
}

/**
 * Adds the bytes no claim covers. A gap between two compiled methods is the code's alignment and
 * per-method read-only data (jump tables, literal pools), which the ReadyToRun tables do not
 * delimit, so it is reported under native code as [ClrSizeGroups.CODE_GAPS]. Elsewhere a gap's
 * trailing filler — zeros, or the `int3` (0xCC) crossgen aligns x86/x64 `.text` with — is
 * [ClrSizeGroups.PADDING] (alignment, the raw tail of a section past its virtual size) and
 * anything before it [ClrSizeGroups.UNATTRIBUTED].
 */
private fun addGaps(bytes: CommonByteBuffer, root: TreeNode, sorted: List<Claim>, start: Long, end: Long) {
    var cursor = start
    var previous: Claim? = null
    fun gap(until: Long, next: Claim?) {
        if (until <= cursor) return
        if (previous?.path?.first() == ClrSizeGroups.NATIVE_CODE && next?.path?.first() == ClrSizeGroups.NATIVE_CODE) {
            root.add(listOf(ClrSizeGroups.NATIVE_CODE, ClrSizeGroups.CODE_GAPS), cursor, until - cursor)
            return
        }
        val filler = bytes[(until - 1).toInt()].takeIf { it == 0.toByte() || it == INT3 }
        var fillerFrom = until
        if (filler != null) while (fillerFrom > cursor && bytes[(fillerFrom - 1).toInt()] == filler) fillerFrom--
        if (fillerFrom > cursor) root.add(listOf(ClrSizeGroups.UNATTRIBUTED), cursor, fillerFrom - cursor)
        if (until > fillerFrom) root.add(listOf(ClrSizeGroups.PADDING), fillerFrom, until - fillerFrom)
    }
    for (claim in sorted) {
        gap(claim.start, claim)
        if (claim.end >= cursor) {
            cursor = claim.end
            previous = claim
        }
    }
    gap(end, null)
}

// endregion

// region Naming

/** Namespace → type → method paths, shared by the IL and native code groups so they line up. */
private class MethodNames(private val metadata: ClrMetadata) {
    private val typePaths = HashMap<Int, List<String>>()

    fun ilPath(method: ClrMethodDef): List<String> = typePath(method.declaringTypeRow) + method.signature

    fun nativePath(method: ClrReadyToRunMethod): List<String> {
        method.foreignAssembly?.let { assembly ->
            val token = "0x" + (method.foreignToken ?: 0u).toString(16).uppercase().padStart(8, '0')
            val owner = method.ownerType?.let { "$it::" }.orEmpty()
            return listOf(ClrSizeGroups.FOREIGN_CODE, assembly, owner + token + typeArguments(method))
        }
        method.memberRefRow?.let { row ->
            val reference = metadata.memberRefs.getOrNull(row - 1)
            val name = (method.ownerType ?: reference?.parent ?: "?") + "::" + (reference?.name ?: "?") + typeArguments(method)
            return listOf(ClrSizeGroups.FOREIGN_CODE, reference?.parent?.substringBefore('`') ?: "?", name)
        }
        val definition = metadata.methods.getOrNull((method.methodDefRow ?: 0) - 1)
            ?: return listOf(ClrSizeGroups.OTHER_NATIVE_CODE)
        if (!method.isInstantiation) return ilPath(definition)
        val owner = method.ownerType ?: metadata.typeDefs.getOrNull(definition.declaringTypeRow - 1)?.let(::shortName) ?: "?"
        return typePath(definition.declaringTypeRow) + "$owner::${definition.name}${typeArguments(method)}"
    }

    private fun typeArguments(method: ClrReadyToRunMethod): String =
        if (method.methodTypeArguments.isEmpty()) "" else method.methodTypeArguments.joinToString(", ", "<", ">")

    /** `[namespace, Outer+Inner<T>]`; nested types take the namespace of their outermost type. */
    private fun typePath(typeRow: Int): List<String> = typePaths.getOrPut(typeRow) {
        val type = metadata.typeDefs.getOrNull(typeRow - 1) ?: return@getOrPut listOf("<unknown type>")
        var outermost = type
        for (depth in 0 until MAX_NESTING) {
            outermost = outermost.enclosingTypeRow?.let { metadata.typeDefs.getOrNull(it - 1) } ?: break
        }
        val namespace = outermost.namespace.ifEmpty { "<global namespace>" }
        listOf(namespace, displayName(type).removePrefix("${outermost.namespace}."))
    }

    private fun displayName(type: ClrTypeDef): String {
        if (type.genericParameters.isEmpty()) return type.fullName
        return type.fullName.replace(GENERIC_ARITY, "") + type.genericParameters.joinToString(", ", "<", ">")
    }

    private fun shortName(type: ClrTypeDef): String = displayName(type).let { name ->
        if ('+' in name) name.substringAfterLast('+') else name.removePrefix("${type.namespace}.")
    }
}

// endregion

// region Tree

private class TreeNode(val name: String) {
    val children = LinkedHashMap<String, TreeNode>()
    var size = 0L
    var offset = Long.MAX_VALUE

    /** Adds [length] bytes starting at [start] to every node along [path]. */
    fun add(path: List<String>, start: Long, length: Long) {
        var node = this
        for (segment in path) {
            node.size += length
            node.offset = minOf(node.offset, start)
            node = node.children.getOrPut(segment) { TreeNode(segment) }
        }
        node.size += length
        node.offset = minOf(node.offset, start)
    }

    fun toSizeNode(sizeOverride: Int? = null, offsetOverride: Int? = null): BinarySizeNode = BinarySizeNode(
        name = name,
        size = sizeOverride ?: size.toInt(),
        offset = offsetOverride ?: offset.takeIf { it != Long.MAX_VALUE }?.toInt() ?: 0,
        children = children.values.sortedByDescending { it.size }.map { it.toSizeNode() },
    )
}

// endregion

private const val CERTIFICATE_DIRECTORY = 4
private const val CLR_DIRECTORY = 14
private const val MAX_NESTING = 32
private const val MAX_IMPLICIT_BLOB = 64 * 1024L
private const val INT3: Byte = 0xCC.toByte()
private val GENERIC_ARITY = Regex("`[0-9]+")
