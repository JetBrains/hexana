package org.jetbrains.hexana.shared.diff

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMethodDef
import org.jetbrains.hexana.shared.CLR_IL_GUTTER_TOKENS
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ClrIlRow
import org.jetbrains.hexana.shared.ClrIlType
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.clrIlMethodRows
import org.jetbrains.hexana.shared.clrIlTypeHeaderRows
import org.jetbrains.hexana.shared.clrIlTypeRows
import org.jetbrains.hexana.shared.clrIlTypes
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.line.LineDiff
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOpKind

/**
 * Diff of two .NET assemblies (managed PE images): the PE/CLR size impact, plus per-type and
 * per-method comparisons whose detail pane shows the selected type's or method's IL side by side.
 *
 * Methods are compared by their rendered IL, with tokens resolved to names, not by bytes: adding
 * a type, a member reference or a user string renumbers metadata rows and heap offsets, which
 * changes the raw IL of methods that did not change at all. Those count as identical, and the
 * Methods tab reports how many there were.
 *
 * Accepts a pair only when both sides are managed; a managed and a native PE go to
 * [NativeDiffProvider].
 */
class ClrDiffProvider : BinaryDiffProvider {
    override fun accepts(input: BinaryDiffInput): Boolean =
        input.leftType == BinaryProviderType.Generic && input.rightType == BinaryProviderType.Generic &&
            isManagedPe(input.left) && isManagedPe(input.right)

    override fun session(input: BinaryDiffInput, host: BinaryDiffHost): BinaryDiffSession {
        val assemblies = ClrAssemblyDiff(input)
        return BinaryDiffSession(
            tabs = listOf(
                DiffTab("Size Impact", "Comparing binaries…") { nativeSizeTab(input) },
                DiffTab("Types", "Comparing types…") { ctx -> clrTypesTab(input, assemblies, ctx) },
                DiffTab("Methods", "Comparing IL…") { ctx -> clrMethodsTab(input, assemblies, ctx) },
            ),
            detail = ClrDetailSource(assemblies),
        )
    }
}

/** A header sniff: a PE image with a CLR runtime header. Does not decode metadata. */
internal fun isManagedPe(bytes: CommonByteBuffer): Boolean {
    if (NativeBinaryFormatDetector.detect(bytes.duplicate()) !is NativeBinaryFormat.Pe) return false
    return (nativeOrGenericBinaryProvider(bytes.duplicate()) as? PeBinaryProvider)?.isManaged == true
}

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

/** One side's decoded assembly. */
internal class ClrSide(val bytes: CommonByteBuffer, val metadata: ClrMetadata) {
    val types: List<ClrIlType> = clrIlTypes(metadata)

    /**
     * [metadata] rendering every signature type with its namespace. Display text abbreviates
     * types (`Widget`), which would make `A.Widget` and `B.Widget` compare equal; pairing and
     * fingerprints therefore use this, and only the shown text uses [metadata].
     */
    val qualified: ClrMetadata = metadata.copy(tokens = metadata.qualifiedTokens)

    fun typeName(method: ClrMethodDef): String = types.getOrNull(method.declaringTypeRow - 1)?.name ?: "<unknown type>"

    /** `Type::signature` as the tables show it. */
    fun methodLabel(method: ClrMethodDef): String = "${typeName(method)}::${method.signature}"

    /** `Type::signature` with qualified signature types — what pairs a method with its counterpart. */
    fun methodKey(method: ClrMethodDef): String = "${typeName(method)}::${qualifiedSignature(method)}"

    fun qualifiedSignature(method: ClrMethodDef): String = qualified.tokens.methodSignature(method)

    /** `Type::name`, which pairs an overload whose signature changed. */
    fun methodNameKey(method: ClrMethodDef): String = "${typeName(method)}::${method.name}"

    fun ilSize(method: ClrMethodDef): Int? = method.body?.codeSize
}

/** A row of the Methods tab: a method on either or both sides, by index into `ClrMetadata.methods`. */
internal class ClrMethodPair(
    val name: String,
    /** Declaring type on each side; they differ when an unchanged method moved to another type. */
    val leftTypeName: String?,
    val rightTypeName: String?,
    val left: Int?,
    val right: Int?,
    val classification: DiffClass,
    val detail: String,
)

internal class ClrMethodDiff(
    val pairs: List<ClrMethodPair>,
    /** Pairs whose IL bytes differ but whose rendered IL is the same: only tokens were renumbered. */
    val tokenOnly: Int,
)

/** A row of the Types tab, by index into each side's [ClrSide.types]. */
internal class ClrTypePair(
    val name: String,
    val left: Int?,
    val right: Int?,
    val classification: DiffClass,
    val detail: String,
)

/**
 * Both assemblies of a diff, decoded once and shared by the tabs and the detail pane. Token
 * resolution is not thread-safe, so everything that renders IL runs under one lock.
 */
internal class ClrAssemblyDiff(private val input: BinaryDiffInput) {
    private val lock = Mutex()
    private var parsed = false
    private var sides: Pair<ClrSide, ClrSide>? = null
    private var methods: ClrMethodDiff? = null

    /** Runs [block] over both sides, or returns `null` when either has no readable metadata. */
    suspend fun <T> withSides(block: suspend (ClrSide, ClrSide) -> T): T? = lock.withLock {
        val (l, r) = sidesLocked() ?: return@withLock null
        block(l, r)
    }

    /** The method comparison, computed on first call. */
    suspend fun methods(): ClrMethodDiff? = lock.withLock {
        methods ?: run {
            val (l, r) = sidesLocked() ?: return@withLock null
            diffClrMethods(l, r).also { methods = it }
        }
    }

    private suspend fun sidesLocked(): Pair<ClrSide, ClrSide>? {
        if (!parsed) {
            parsed = true
            val l = side(input.left)
            yield()
            val r = l?.let { side(input.right) }
            sides = if (l != null && r != null) l to r else null
        }
        return sides
    }

    private fun side(bytes: CommonByteBuffer): ClrSide? {
        val provider = nativeOrGenericBinaryProvider(bytes.duplicate()) as? PeBinaryProvider ?: return null
        val metadata = provider.clrMetadata ?: return null
        return ClrSide(provider.bytes, metadata)
    }
}

// ---------------------------------------------------------------------------
// IL lines and comparison keys
// ---------------------------------------------------------------------------

/**
 * A rendered IL line without its file-offset gutter. [key] decides whether two methods are
 * identical: only the RVA in the generated `// RVA 0x…` comment, which moves whenever an earlier
 * body changes size, is masked. [alignKey] is what the detail pane aligns on and also masks
 * every `IL_xxxx` label — the instruction's own and branch targets — so one inserted
 * instruction does not mark every later line, or every branch over it, as changed (the native
 * diff masks shifted addresses alike).
 *
 * Masking goes by token kind, never by text, so a string literal or an identifier that happens
 * to read `RVA 0x1234` or `IL_0010` is compared as written.
 */
internal class ClrIlLine(val text: String, val key: String, val alignKey: String)

/**
 * Lines of [display], keyed by [keyRows] — the same listing rendered with qualified type names
 * ([ClrSide.qualified]). Both renderings emit the same rows; should they ever not, the keys
 * come from [keyRows] alone.
 */
internal fun clrIlLines(display: List<ClrIlRow>, keyRows: List<ClrIlRow> = display): List<ClrIlLine> {
    val shown = if (display.size == keyRows.size) display else keyRows
    return keyRows.mapIndexed { i, row ->
        val content = row.tokens.drop(CLR_IL_GUTTER_TOKENS)
        val key = content.joinToString("") { t ->
            if (t.kind == RenderTokenKind.COMMENT) GENERATED_RVA.replace(t.text, "$1…") else t.text
        }
        val alignKey = content.joinToString("") { t ->
            when (t.kind) {
                RenderTokenKind.COMMENT -> GENERATED_RVA.replace(t.text, "$1…")
                RenderTokenKind.OFFSET_ANNOTATION, RenderTokenKind.LABEL_REF -> IL_LABEL.replace(t.text, "IL_…")
                else -> t.text
            }
        }
        ClrIlLine(shown[i].tokens.drop(CLR_IL_GUTTER_TOKENS).joinToString("") { it.text }, key, alignKey)
    }
}

/** The method header comment `clrIlMethodRows` emits: `// RVA 0x2050, code size …`. */
private val GENERATED_RVA = Regex("^(// RVA 0x)[0-9A-Fa-f]+(?=,)")
private val IL_LABEL = Regex("^IL_[0-9a-fA-F]+$")

/**
 * Identity of a list of keys: two independent 32-bit polynomial hashes over the lines' hash
 * codes, packed into a Long (Int maths, which is native on Kotlin/JS).
 */
private fun keysHash(keys: List<String>, from: Int = 0): Long {
    var a = 17
    var b = 0x2F0B3A49
    for (i in from until keys.size) {
        val h = keys[i].hashCode()
        a = a * 31 + h
        b = (b xor h) * 0x01000193
    }
    return (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
}

private class MethodFingerprint(
    /** Hash of the whole rendered method, signature line included. */
    val full: Long,
    /** Hash of the body only (no `.method` line), for rename detection; `null` without IL. */
    val body: Long?,
)

private fun fingerprint(side: ClrSide, method: ClrMethodDef): MethodFingerprint {
    val keys = clrIlLines(clrIlMethodRows(side.qualified, side.bytes, method)).map { it.key }
    return MethodFingerprint(keysHash(keys), if (method.body != null) keysHash(keys, from = 1) else null)
}

// ---------------------------------------------------------------------------
// Method diff
// ---------------------------------------------------------------------------

/**
 * Pairs and classifies every method. Pairing: same `Type::signature` (in row order, for the
 * rare duplicate), then same `Type::name` where that is unique among what is left on both sides
 * (a changed signature), then identical IL bodies unique on both sides (a rename). The rest are
 * added / removed. Renders every method's IL once, yielding between batches.
 */
internal suspend fun diffClrMethods(left: ClrSide, right: ClrSide): ClrMethodDiff {
    val lm = left.metadata.methods
    val rm = right.metadata.methods
    val lPrints = arrayOfNulls<MethodFingerprint>(lm.size)
    val rPrints = arrayOfNulls<MethodFingerprint>(rm.size)
    var rendered = 0
    suspend fun print(side: ClrSide, prints: Array<MethodFingerprint?>, index: Int, methods: List<ClrMethodDef>): MethodFingerprint {
        prints[index]?.let { return it }
        if (++rendered % YIELD_EVERY == 0) yield()
        return fingerprint(side, methods[index]).also { prints[index] = it }
    }

    val pairs = ArrayList<ClrMethodPair>()
    var tokenOnly = 0

    suspend fun pair(l: Int, r: Int, renamedFrom: String? = null) {
        val a = lm[l]
        val b = rm[r]
        val name = right.methodLabel(b)
        val same = print(left, lPrints, l, lm).full == print(right, rPrints, r, rm).full
        val classification: DiffClass
        val detail: String
        when {
            renamedFrom != null -> {
                classification = DiffClass.MOVED
                detail = "renamed from $renamedFrom"
            }
            same -> {
                classification = DiffClass.IDENTICAL
                detail = ""
                if (!sameIlBytes(left, a, right, b)) tokenOnly++
            }
            left.qualifiedSignature(a) != right.qualifiedSignature(b) -> {
                classification = DiffClass.MODIFIED
                // Abbreviated names can read the same while the types differ (A.Widget → B.Widget).
                detail = if (a.signature != b.signature) {
                    "signature: ${a.signature} → ${b.signature}"
                } else {
                    "signature: ${left.qualifiedSignature(a)} → ${right.qualifiedSignature(b)}"
                }
            }
            left.ilSize(a) != right.ilSize(b) -> {
                classification = DiffClass.MODIFIED
                detail = "IL ${sizeText(left.ilSize(a))} → ${sizeText(right.ilSize(b))}"
            }
            else -> {
                classification = DiffClass.MODIFIED
                detail = "IL changed"
            }
        }
        pairs += ClrMethodPair(name, left.typeName(a), right.typeName(b), l, r, classification, detail)
    }

    // 1. Same type and signature.
    val rightByKey = rm.indices.groupBy { right.methodKey(rm[it]) }.mapValues { ArrayDeque(it.value) }
    val leftOnly = ArrayList<Int>()
    for (l in lm.indices) {
        val r = rightByKey[left.methodKey(lm[l])]?.removeFirstOrNull()
        if (r == null) leftOnly += l else pair(l, r)
    }
    var rightOnly = rightByKey.values.flatten().sorted()

    // 2. Same type and name, unique on both sides: the signature changed.
    run {
        val leftByName = leftOnly.groupBy { left.methodNameKey(lm[it]) }.filterValues { it.size == 1 }
        val rightByName = rightOnly.groupBy { right.methodNameKey(rm[it]) }.filterValues { it.size == 1 }
        val matchedLeft = HashSet<Int>()
        val matchedRight = HashSet<Int>()
        for ((name, ls) in leftByName) {
            val r = rightByName[name]?.single() ?: continue
            pair(ls.single(), r)
            matchedLeft += ls.single()
            matchedRight += r
        }
        leftOnly.removeAll(matchedLeft)
        rightOnly = rightOnly.filter { it !in matchedRight }
    }

    // 3. Identical bodies, unique on both sides: a rename (or a move to another type).
    val rightByBody = HashMap<Long, MutableList<Int>>()
    for (r in rightOnly) print(right, rPrints, r, rm).body?.let { rightByBody.getOrPut(it) { ArrayList() } += r }
    val leftBodyCounts = HashMap<Long, Int>()
    for (l in leftOnly) print(left, lPrints, l, lm).body?.let { leftBodyCounts[it] = (leftBodyCounts[it] ?: 0) + 1 }
    val renamedRight = HashSet<Int>()
    for (l in leftOnly) {
        val body = lPrints[l]?.body
        val r = body?.takeIf { leftBodyCounts[it] == 1 }?.let { rightByBody[it]?.singleOrNull() }
        if (r == null) {
            pairs += ClrMethodPair(left.methodLabel(lm[l]), left.typeName(lm[l]), null, l, null, DiffClass.REMOVED, sizeText(left.ilSize(lm[l])))
        } else {
            renamedRight += r
            pair(l, r, renamedFrom = left.methodLabel(lm[l]))
        }
    }
    for (r in rightOnly) {
        if (r in renamedRight) continue
        pairs += ClrMethodPair(right.methodLabel(rm[r]), null, right.typeName(rm[r]), null, r, DiffClass.ADDED, sizeText(right.ilSize(rm[r])))
    }
    return ClrMethodDiff(pairs, tokenOnly)
}

private fun sameIlBytes(left: ClrSide, a: ClrMethodDef, right: ClrSide, b: ClrMethodDef): Boolean {
    val x = a.body?.codeRange
    val y = b.body?.codeRange
    if (x == null || y == null) return x == y
    if (x.size != y.size) return false
    return left.bytes.slice(x.offset, x.size).toByteArray()
        .contentEquals(right.bytes.slice(y.offset, y.size).toByteArray())
}

private fun sizeText(size: Int?): String = size?.let { formatSize(it) } ?: "no body"

// ---------------------------------------------------------------------------
// Type diff
// ---------------------------------------------------------------------------

/**
 * Pairs types by display name (in row order, for duplicates). A pair is modified when its
 * declaration, base type, interfaces or fields differ, or when any of its methods changed.
 */
internal fun diffClrTypes(left: ClrSide, right: ClrSide, methods: ClrMethodDiff): List<ClrTypePair> {
    class Counts {
        var added = 0
        var removed = 0
        var modified = 0
        var moved = 0
    }
    val counts = HashMap<String, Counts>()
    for (p in methods.pairs) {
        if (p.classification == DiffClass.IDENTICAL) continue
        // A method that moved to another type changes both: the source loses it, the target gains it.
        for (typeName in setOfNotNull(p.leftTypeName, p.rightTypeName)) {
            val c = counts.getOrPut(typeName) { Counts() }
            when (p.classification) {
                DiffClass.ADDED -> c.added++
                DiffClass.REMOVED -> c.removed++
                DiffClass.MODIFIED -> c.modified++
                DiffClass.MOVED -> c.moved++
                DiffClass.IDENTICAL -> Unit
            }
        }
    }
    fun methodSummary(name: String): String? = counts[name]?.let { c ->
        listOfNotNull(
            "+${c.added}".takeIf { c.added > 0 },
            "-${c.removed}".takeIf { c.removed > 0 },
            "~${c.modified}".takeIf { c.modified > 0 },
            "${c.moved} renamed or moved".takeIf { c.moved > 0 },
        ).joinToString(" ").let { "methods $it" }
    }

    val pairs = ArrayList<ClrTypePair>()
    val rightByName = right.types.indices.groupBy { right.types[it].name }.mapValues { ArrayDeque(it.value) }
    for (l in left.types.indices) {
        val type = left.types[l]
        val r = rightByName[type.name]?.removeFirstOrNull()
        if (r == null) {
            pairs += ClrTypePair(type.name, l, null, DiffClass.REMOVED, memberText(type))
            continue
        }
        val headerChanged = headerKeys(left, type) != headerKeys(right, right.types[r])
        val methodText = methodSummary(type.name)
        val detail = listOfNotNull("declaration or fields changed".takeIf { headerChanged }, methodText).joinToString("; ")
        val classification = if (detail.isEmpty()) DiffClass.IDENTICAL else DiffClass.MODIFIED
        pairs += ClrTypePair(type.name, l, r, classification, detail)
    }
    for (r in rightByName.values.flatten().sorted()) {
        pairs += ClrTypePair(right.types[r].name, null, r, DiffClass.ADDED, memberText(right.types[r]))
    }
    return pairs
}

private fun headerKeys(side: ClrSide, type: ClrIlType): List<String> =
    clrIlLines(clrIlTypeHeaderRows(side.qualified, type)).map { it.key }

private fun memberText(type: ClrIlType): String = "${type.memberCount} ${if (type.memberCount == 1) "member" else "members"}"

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------

private const val TYPE_PREFIX = "T:"
private const val METHOD_PREFIX = "M:"

private fun assemblyLine(left: ClrMetadata, right: ClrMetadata): DiffTextLine? {
    fun label(m: ClrMetadata) = listOfNotNull(m.assembly?.let { "${it.name} ${it.version}" }, m.targetFramework).joinToString(", ")
    val a = label(left)
    val b = label(right)
    if (a.isEmpty() && b.isEmpty()) return null
    return DiffTextLine(if (a == b) a else "$a  →  $b")
}

internal suspend fun clrTypesTab(input: BinaryDiffInput, assemblies: ClrAssemblyDiff, ctx: DiffTabContext): DiffTabContent {
    val methods = assemblies.methods() ?: return noMetadata()
    val result = assemblies.withSides { l, r -> Triple(diffClrTypes(l, r, methods), assemblyLine(l.metadata, r.metadata), l to r) }
        ?: return noMetadata()
    val (pairs, assemblyLine, sides) = result
    val (left, right) = sides
    val changed = pairs.filter { it.classification != DiffClass.IDENTICAL }
    val header = buildList {
        add(DiffTextLine("${input.leftName}  →  ${input.rightName}"))
        assemblyLine?.let(::add)
        add(DiffTextLine(countsLine("types", pairs.map { it.classification }), bold = true))
    }
    if (changed.isEmpty()) return DiffTabContent(header, emptyMessage = "All ${pairs.size} types are identical.")
    fun members(index: Int?, side: ClrSide) = index?.let { side.types[it].memberCount }
    val table = TableTabDescriptor(
        name = "Types",
        columns = listOf(
            ColumnDescriptor("Name", ColumnWidth.Measured("w".repeat(40))) { i -> CellValue(changed[i].name) },
            ColumnDescriptor("Change", ColumnWidth.Measured("Modified__")) { i -> CellValue(changed[i].classification.label()) },
            countColumn("Base members") { i -> members(changed[i].left, left) },
            countColumn("Head members") { i -> members(changed[i].right, right) },
            ColumnDescriptor("Detail", ColumnWidth.Fill()) { i -> CellValue(changed[i].detail) },
        ),
        rowCount = changed.size,
        defaultSortColumn = 1,
        defaultSortAscending = true,
        showIdColumn = false,
        alwaysShowSearch = true,
        rowClickAction = { i -> changed[i].let { ctx.select(DiffItemRef(TYPE_PREFIX + it.name, it.left, it.right)) } },
        rowTintProvider = { i -> changed[i].classification.toRowTint() },
    )
    return DiffTabContent(header, table, footer = hiddenFooter(pairs.size - changed.size, "type", "types"))
}

internal suspend fun clrMethodsTab(input: BinaryDiffInput, assemblies: ClrAssemblyDiff, ctx: DiffTabContext): DiffTabContent {
    val diff = assemblies.methods() ?: return noMetadata()
    val sides = assemblies.withSides { l, r -> l to r } ?: return noMetadata()
    val (left, right) = sides
    val pairs = diff.pairs
    val changed = pairs.filter { it.classification != DiffClass.IDENTICAL }
    val header = buildList {
        add(DiffTextLine("${input.leftName}  →  ${input.rightName}"))
        add(DiffTextLine(countsLine("methods", pairs.map { it.classification }), bold = true))
        if (diff.tokenOnly > 0) {
            add(
                DiffTextLine(
                    "${diff.tokenOnly} ${if (diff.tokenOnly == 1) "method differs" else "methods differ"} only in metadata " +
                        "tokens (renumbered rows or heap offsets) and ${if (diff.tokenOnly == 1) "is" else "are"} counted as identical.",
                ),
            )
        }
    }
    if (changed.isEmpty()) return DiffTabContent(header, emptyMessage = "All ${pairs.size} methods have identical IL.")
    fun ilSize(index: Int?, side: ClrSide) = index?.let { side.ilSize(side.metadata.methods[it]) }
    val table = TableTabDescriptor(
        name = "Methods",
        columns = listOf(
            ColumnDescriptor("Name", ColumnWidth.Measured("w".repeat(48))) { i -> CellValue(changed[i].name) },
            ColumnDescriptor("Change", ColumnWidth.Measured("Modified__")) { i -> CellValue(changed[i].classification.label()) },
            sizeColumn("Base IL") { i -> ilSize(changed[i].left, left) },
            sizeColumn("Head IL") { i -> ilSize(changed[i].right, right) },
            ColumnDescriptor("Δ", ColumnWidth.Measured("-9999.9 KB_")) { i ->
                val p = changed[i]
                val delta = (ilSize(p.right, right) ?: 0) - (ilSize(p.left, left) ?: 0)
                CellValue(delta, signedSize(delta))
            },
            ColumnDescriptor("Detail", ColumnWidth.Fill()) { i -> CellValue(changed[i].detail) },
        ),
        rowCount = changed.size,
        defaultSortColumn = 1,
        defaultSortAscending = true,
        showIdColumn = false,
        alwaysShowSearch = true,
        rowClickAction = { i -> changed[i].let { ctx.select(DiffItemRef(METHOD_PREFIX + it.name, it.left, it.right)) } },
        rowTintProvider = { i -> changed[i].classification.toRowTint() },
    )
    return DiffTabContent(header, table, footer = hiddenFooter(pairs.size - changed.size, "method", "methods"))
}

private fun noMetadata() = DiffTabContent(emptyMessage = "The .NET metadata of one or both assemblies could not be read.")

private fun countsLine(what: String, classes: List<DiffClass>): String {
    fun count(c: DiffClass) = classes.count { it == c }
    val moved = count(DiffClass.MOVED).takeIf { it > 0 }?.let { ", $it renamed" }.orEmpty()
    return "$what: +${count(DiffClass.ADDED)} added, -${count(DiffClass.REMOVED)} removed, ~${count(DiffClass.MODIFIED)} modified$moved"
}

private fun countColumn(name: String, value: (Int) -> Int?) =
    ColumnDescriptor(name, ColumnWidth.Measured("Head members_")) { i ->
        val v = value(i)
        if (v == null) CellValue(-1, "—") else CellValue(v, v.toString())
    }

// ---------------------------------------------------------------------------
// Detail pane
// ---------------------------------------------------------------------------

/**
 * The selected type's or method's IL side by side. Lines align on keys that ignore RVAs and
 * `IL_` labels, so an inserted instruction shows as its own added lines; the displayed text keeps
 * the real labels.
 */
internal class ClrDetailSource(private val assemblies: ClrAssemblyDiff) : DiffDetailSource {
    override val placeholder: String get() = "Select a type or method to compare its IL."

    override suspend fun render(ref: DiffItemRef): SideBySideDiff {
        val isType = ref.name.startsWith(TYPE_PREFIX)
        val name = ref.name.removePrefix(TYPE_PREFIX).removePrefix(METHOD_PREFIX)
        val lines = assemblies.withSides { l, r ->
            if (isType) {
                val lt = ref.leftIndex?.let { l.types.getOrNull(it) }
                val rt = ref.rightIndex?.let { r.types.getOrNull(it) }
                val a = lt?.let { clrIlLines(clrIlTypeRows(l.metadata, l.bytes, it), clrIlTypeRows(l.qualified, l.bytes, it)) }.orEmpty()
                yield()
                val b = rt?.let { clrIlLines(clrIlTypeRows(r.metadata, r.bytes, it), clrIlTypeRows(r.qualified, r.bytes, it)) }.orEmpty()
                Triple(a, b, name)
            } else {
                val lm = ref.leftIndex?.let { l.metadata.methods.getOrNull(it) }
                val rm = ref.rightIndex?.let { r.metadata.methods.getOrNull(it) }
                val sizes = listOfNotNull(lm?.let { "base IL ${sizeText(l.ilSize(it))}" }, rm?.let { "head IL ${sizeText(r.ilSize(it))}" })
                val title = if (sizes.isEmpty()) name else "$name    (${sizes.joinToString("  →  ")})"
                Triple(
                    lm?.let { clrIlLines(clrIlMethodRows(l.metadata, l.bytes, it), clrIlMethodRows(l.qualified, l.bytes, it)) }.orEmpty(),
                    rm?.let { clrIlLines(clrIlMethodRows(r.metadata, r.bytes, it), clrIlMethodRows(r.qualified, r.bytes, it)) }.orEmpty(),
                    title,
                )
            }
        } ?: return SideBySideDiff(name, emptyList(), "(.NET metadata could not be read)")
        val (left, right, title) = lines
        if (left.isEmpty() && right.isEmpty()) return SideBySideDiff(title, emptyList(), "(${if (isType) "type" else "method"} not found)")
        yield()
        var ops = LineDiff.diff(left.map { it.alignKey }, right.map { it.alignKey })
        // Masking labels hides a change that is only a branch target; show that one unmasked.
        if (ops.all { it.kind == LineOpKind.SAME } && left.map { it.key } != right.map { it.key }) {
            ops = LineDiff.diff(left.map { it.key }, right.map { it.key })
        }
        return SideBySideDiff(title, sideBySideRows(ops, { left[it].text }, { right[it].text }))
    }
}

private const val YIELD_EVERY = 64
