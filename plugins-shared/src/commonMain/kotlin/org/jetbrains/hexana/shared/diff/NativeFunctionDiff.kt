package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.disassembly.core.Disassembler
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.shared.nativeDisassemblyArch
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.line.LineDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One side's function whose body is in the file: the byte range comes from the symbol
 * table (`NativeFunctionRecord.fileOffset` + `size`), so only formats whose parser
 * yields function records (ELF, Mach-O, COFF objects) have these.
 */
internal class NativeFunctionBody(
    val name: String,
    val virtualAddress: ULong,
    val fileOffset: Int,
    val size: Int,
    /** `null` when the machine is not one the disassemblers support; the body is then compared as bytes. */
    val arch: Arch?,
    val hash: Long,
)

/** A row of the Functions tab: a function on either or both sides, by index into the side's body list. */
internal class NativeFunctionPair(
    val name: String,
    val left: Int?,
    val right: Int?,
    val classification: DiffClass,
    val detail: String,
)

/**
 * The function bodies of [provider], in address order, or `null` when the format has no
 * function records. Imports, nameless records and records whose range falls outside the
 * file are dropped; aliases at one address are kept once.
 */
internal fun nativeFunctionBodies(provider: NativeBinaryProvider): List<NativeFunctionBody>? {
    val records = provider.nativeFunctions ?: return null
    val bytes = provider.bytes
    val fileArch = nativeDisassemblyArch(provider)
    return records.asSequence()
        .filter { !it.imported && it.name.isNotBlank() }
        .mapNotNull { record ->
            val offset = record.fileOffset?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt() ?: return@mapNotNull null
            val size = record.size?.takeIf { it > 0uL && it <= Int.MAX_VALUE.toULong() }?.toInt() ?: return@mapNotNull null
            if (offset.toLong() + size > bytes.limit()) return@mapNotNull null
            NativeFunctionBody(
                name = record.demangledName?.takeIf { it.isNotBlank() } ?: record.name,
                virtualAddress = record.virtualAddress,
                fileOffset = offset,
                size = size,
                arch = if (record.isThumb && fileArch == Arch.ARM) Arch.THUMB else fileArch,
                hash = fnv1a(bytes, offset, size),
            )
        }
        .distinctBy { it.virtualAddress }
        .sortedBy { it.virtualAddress }
        .toList()
}

/**
 * Pairs the two sides' functions.
 *
 * Names anchor first: same-named functions pair in address order (static functions can
 * share a name). What is left pairs by identical body bytes, which catches a rename. The
 * rest are added / removed. A pair is identical when its bytes are, and modified otherwise
 * — [refineWithDisassembly] then demotes pairs whose only difference is addresses.
 */
internal fun pairNativeFunctions(left: List<NativeFunctionBody>, right: List<NativeFunctionBody>): List<NativeFunctionPair> {
    val pairs = ArrayList<NativeFunctionPair>()
    val rightByName = right.indices.groupBy { right[it].name }.mapValues { ArrayDeque(it.value) }
    val leftOnly = ArrayList<Int>()
    for (l in left.indices) {
        val r = rightByName[left[l].name]?.removeFirstOrNull()
        if (r == null) {
            leftOnly.add(l)
        } else {
            pairs.add(namedPair(left, right, l, r))
        }
    }
    val rightOnly = rightByName.values.flatten().sorted()

    // A body hash present exactly once on each side is a rename, not an add + remove.
    val uniqueRightByHash = rightOnly.groupBy { right[it].hash }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    val leftHashCounts = leftOnly.groupingBy { left[it].hash }.eachCount()
    val renamedRight = HashSet<Int>()
    for (l in leftOnly) {
        val body = left[l]
        val r = uniqueRightByHash[body.hash]?.takeIf { leftHashCounts[body.hash] == 1 }
        if (r == null) {
            pairs.add(NativeFunctionPair(body.name, l, null, DiffClass.REMOVED, formatSize(body.size)))
        } else {
            renamedRight.add(r)
            pairs.add(NativeFunctionPair(right[r].name, l, r, DiffClass.MOVED, "renamed from ${body.name}"))
        }
    }
    for (r in rightOnly) {
        if (r !in renamedRight) {
            pairs.add(NativeFunctionPair(right[r].name, null, r, DiffClass.ADDED, formatSize(right[r].size)))
        }
    }
    return pairs
}

private fun namedPair(left: List<NativeFunctionBody>, right: List<NativeFunctionBody>, l: Int, r: Int): NativeFunctionPair {
    val a = left[l]
    val b = right[r]
    return when {
        a.hash == b.hash && a.size == b.size -> NativeFunctionPair(a.name, l, r, DiffClass.IDENTICAL, "")
        a.size != b.size -> NativeFunctionPair(a.name, l, r, DiffClass.MODIFIED, "${formatSize(a.size)} → ${formatSize(b.size)}")
        else -> NativeFunctionPair(a.name, l, r, DiffClass.MODIFIED, "bytes differ")
    }
}

/**
 * Demotes same-size modified pairs whose instructions match once addresses are masked
 * ([normalizedKey]) to MOVED — code that only shifted because something before it grew.
 * Without that, relinking makes nearly every function look modified. Bounded by
 * [MAX_REFINE_BYTES] so a huge binary does not stall the tab; returns whether it ran.
 */
internal fun refineWithDisassembly(
    pairs: MutableList<NativeFunctionPair>,
    session: NativeFunctionSession,
    disassembler: Disassembler,
): Boolean {
    val candidates = pairs.indices.filter { i ->
        val p = pairs[i]
        p.classification == DiffClass.MODIFIED && p.left != null && p.right != null &&
            session.left[p.left].size == session.right[p.right].size &&
            session.left[p.left].arch != null && session.right[p.right].arch != null
    }
    if (candidates.sumOf { session.left[pairs[it].left!!].size.toLong() } > MAX_REFINE_BYTES) return false
    for (i in candidates) {
        val p = pairs[i]
        val l = decodeFunction(disassembler, session.leftBytes, session.left[p.left!!]).map { it.key }
        val r = decodeFunction(disassembler, session.rightBytes, session.right[p.right!!]).map { it.key }
        if (l == r) pairs[i] = NativeFunctionPair(p.name, p.left, p.right, DiffClass.MOVED, "addresses only")
    }
    return true
}

/**
 * Both sides of a native diff, parsed once and shared by the Functions tab and the detail
 * pane. The disassembler is loaded on first use, at most once.
 */
internal class NativeFunctionSession(input: BinaryDiffInput, private val host: BinaryDiffHost) {
    val leftBytes: CommonByteBuffer = input.left.duplicate()
    val rightBytes: CommonByteBuffer = input.right.duplicate()
    private val bodies by lazy {
        val l = (nativeOrGenericBinaryProvider(input.left.duplicate()) as? NativeBinaryProvider)?.let(::nativeFunctionBodies)
        val r = (nativeOrGenericBinaryProvider(input.right.duplicate()) as? NativeBinaryProvider)?.let(::nativeFunctionBodies)
        if (l != null && r != null) l to r else null
    }
    val available: Boolean get() = bodies != null
    val left: List<NativeFunctionBody> get() = bodies?.first.orEmpty()
    val right: List<NativeFunctionBody> get() = bodies?.second.orEmpty()

    private val lock = Mutex()
    private var disassembler: Disassembler? = null
    private var disassemblerFailed = false

    /**
     * Runs [block] with the host's disassembler, or returns `null` when there is none (or it
     * failed to load). Serialized: a decoder instance is not safe for concurrent use, and
     * the Functions tab and the detail pane can decode at the same time.
     */
    suspend fun <T> withDisassembler(block: (Disassembler) -> T): T? = lock.withLock {
        val d = disassembler ?: run {
            val loader = host.nativeDisassembler
            if (disassemblerFailed || loader == null) return@withLock null
            try {
                loader().also { disassembler = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disassemblerFailed = true
                return@withLock null
            }
        }
        block(d)
    }
}

internal suspend fun nativeFunctionsTab(input: BinaryDiffInput, session: NativeFunctionSession, ctx: DiffTabContext): DiffTabContent {
    if (!session.available) {
        return DiffTabContent(emptyMessage = "Per-function comparison needs a symbol table on both sides.")
    }
    val left = session.left
    val right = session.right
    val pairs = pairNativeFunctions(left, right).toMutableList()
    val refinedOrNull = session.withDisassembler { refineWithDisassembly(pairs, session, it) }
    val refined = refinedOrNull == true
    val changed = pairs.filter { it.classification != DiffClass.IDENTICAL }

    val header = buildList {
        add(DiffTextLine("${input.leftName}  →  ${input.rightName}"))
        fun count(c: DiffClass) = pairs.count { it.classification == c }
        add(
            DiffTextLine(
                "functions: +${count(DiffClass.ADDED)} added, -${count(DiffClass.REMOVED)} removed, " +
                    "~${count(DiffClass.MODIFIED)} modified, ${count(DiffClass.MOVED)} moved/renamed",
                bold = true,
            ),
        )
        when {
            refinedOrNull == null -> add(DiffTextLine("Compared as raw bytes — code that only moved shows as modified."))
            !refined -> add(DiffTextLine("Too much changed code to compare instruction by instruction; compared as raw bytes."))
        }
    }
    if (changed.isEmpty()) {
        return DiffTabContent(header, emptyMessage = "All ${pairs.size} functions are identical.")
    }
    fun sizeOf(p: NativeFunctionPair, side: List<NativeFunctionBody>, index: Int?) = index?.let { side[it].size }
    val table = TableTabDescriptor(
        name = "Functions",
        columns = listOf(
            ColumnDescriptor("Name", ColumnWidth.Measured("w".repeat(40))) { i -> CellValue(changed[i].name) },
            ColumnDescriptor("Change", ColumnWidth.Measured("Modified__")) { i -> CellValue(changed[i].classification.label()) },
            sizeColumn("Base") { i -> sizeOf(changed[i], left, changed[i].left) },
            sizeColumn("Head") { i -> sizeOf(changed[i], right, changed[i].right) },
            ColumnDescriptor("Δ", ColumnWidth.Measured("-9999.9 KB_")) { i ->
                val p = changed[i]
                val delta = (sizeOf(p, right, p.right) ?: 0) - (sizeOf(p, left, p.left) ?: 0)
                CellValue(delta, signedSize(delta))
            },
            ColumnDescriptor("Detail", ColumnWidth.Fill()) { i -> CellValue(changed[i].detail) },
        ),
        rowCount = changed.size,
        defaultSortColumn = 1,
        defaultSortAscending = true,
        showIdColumn = false,
        alwaysShowSearch = true,
        rowClickAction = { i -> changed[i].let { ctx.select(DiffItemRef(it.name, it.left, it.right)) } },
        rowTintProvider = { i -> changed[i].classification.toRowTint() },
    )
    return DiffTabContent(
        header = header,
        table = table,
        footer = hiddenFooter(pairs.size - changed.size, "function", "functions"),
    )
}

/**
 * The selected function's disassembly side by side, diffed with addresses masked so a
 * shifted call target lines up; the displayed text keeps the real addresses. Falls back
 * to a 16-bytes-per-row hex comparison when the host has no disassembler or the
 * architecture is unsupported.
 */
internal class NativeFunctionDetailSource(private val session: NativeFunctionSession) : DiffDetailSource {
    override val placeholder: String get() = "Select a function to compare its code."

    override suspend fun render(ref: DiffItemRef): SideBySideDiff {
        val l = ref.leftIndex?.let { session.left.getOrNull(it) }
        val r = ref.rightIndex?.let { session.right.getOrNull(it) }
        val title = buildString {
            append(ref.name)
            val sides = listOfNotNull(l?.let { "base ${hex(it.virtualAddress)}, ${formatSize(it.size)}" }, r?.let { "head ${hex(it.virtualAddress)}, ${formatSize(it.size)}" })
            if (sides.isNotEmpty()) append("    (").append(sides.joinToString("  →  ")).append(')')
        }
        if (l == null && r == null) return SideBySideDiff(title, emptyList(), "(function not found)")
        if ((l?.size ?: 0) > MAX_RENDER_BYTES || (r?.size ?: 0) > MAX_RENDER_BYTES) {
            return SideBySideDiff(title, emptyList(), "(function larger than ${formatSize(MAX_RENDER_BYTES)} — not rendered)")
        }
        val decodable = (l == null || l.arch != null) && (r == null || r.arch != null)
        val decoded = if (decodable) {
            session.withDisassembler { d ->
                l?.let { decodeFunction(d, session.leftBytes, it) }.orEmpty() to r?.let { decodeFunction(d, session.rightBytes, it) }.orEmpty()
            }
        } else {
            null
        }
        val (leftLines, rightLines) = decoded
            ?: (l?.let { hexLines(session.leftBytes, it) }.orEmpty() to r?.let { hexLines(session.rightBytes, it) }.orEmpty())
        val ops = LineDiff.diff(leftLines.map { it.key }, rightLines.map { it.key })
        val rows = sideBySideRows(ops, { leftLines[it].text }, { rightLines[it].text })
        return SideBySideDiff(if (decoded != null) title else "$title  [bytes]", rows)
    }
}

/** A rendered line: [text] is shown, [key] is what the line diff compares. */
internal class NativeCodeLine(val text: String, val key: String)

internal fun decodeFunction(disassembler: Disassembler, file: CommonByteBuffer, body: NativeFunctionBody): List<NativeCodeLine> {
    val arch = body.arch ?: return emptyList()
    val slice = file.slice(body.fileOffset, body.size)
    // Intel syntax for every architecture, as the virtualized listing does: it is the
    // backend's native dialect, which Capstone renders for all of them.
    val formatter = disassembler.formatter(arch, AssemblySyntax.INTEL)
    val instructions = with(disassembler) { slice.disassemble(body.virtualAddress, arch) }
    return instructions.map { insn ->
        val text = when (insn) {
            is DecodedInstruction.Valid -> formatter.format(insn)
            is DecodedInstruction.Unknown -> with(formatter) { slice.formatUnknown(insn) }
        }
        NativeCodeLine("${hex(insn.address)}  $text", normalizedKey(text))
    }.toList()
}

private fun hexLines(file: CommonByteBuffer, body: NativeFunctionBody): List<NativeCodeLine> =
    (0 until body.size step 16).map { start ->
        val end = minOf(start + 16, body.size)
        val hexBytes = (start until end).joinToString(" ") { i ->
            (file[body.fileOffset + i].toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        NativeCodeLine("+${start.toString(16).padStart(4, '0')}  $hexBytes", hexBytes)
    }

private val HEX_LITERAL = Regex("0x[0-9a-fA-F]+")

/**
 * Masks hex literals of 0x100 and up — call/branch targets, PC-relative displacements,
 * absolute data addresses — which move whenever anything before them changes size.
 * Smaller literals (stack offsets, most immediates) stay, so a real constant change
 * still shows. A heuristic: a changed large immediate is masked too.
 */
internal fun normalizedKey(text: String): String =
    HEX_LITERAL.replace(text) { m ->
        val digits = m.value.substring(2).trimStart('0')
        if (digits.length >= 3) "0x…" else m.value
    }

private fun hex(address: ULong): String = "0x" + address.toString(16)

private fun fnv1a(bytes: CommonByteBuffer, offset: Int, size: Int): Long {
    var hash = 0xcbf29ce484222325uL.toLong()
    for (i in offset until offset + size) {
        hash = (hash xor (bytes[i].toLong() and 0xFF)) * 0x100000001b3L
    }
    return hash
}

private const val MAX_RENDER_BYTES = 256 * 1024
private const val MAX_REFINE_BYTES = 16L * 1024 * 1024
