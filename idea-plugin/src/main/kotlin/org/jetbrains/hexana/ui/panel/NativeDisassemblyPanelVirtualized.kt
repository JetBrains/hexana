package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.disassembly.capstone.CapstoneWasmDisassembler
import org.jetbrains.hexana.disassembly.capstone.loadFromClasspath
import org.jetbrains.hexana.disassembly.core.Arch
import org.jetbrains.hexana.disassembly.core.AssemblyFormatter
import org.jetbrains.hexana.disassembly.core.AssemblySyntax
import org.jetbrains.hexana.disassembly.core.DecodedInstruction
import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.format.ChunkId
import org.jetbrains.hexana.document.format.ChunkRenderer
import org.jetbrains.hexana.document.format.ChunkTree
import org.jetbrains.hexana.document.format.RenderToken
import org.jetbrains.hexana.document.format.RenderTokenKind
import org.jetbrains.hexana.document.format.SectionKind
import org.jetbrains.hexana.document.format.TokenStream
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Text

// ---------------------------------------------------------------------------
// Disassembly model (self-contained)
//
// This panel does NOT borrow from the llvm-objdump-shaped types in
// `NativeDisassemblyPanel.kt`. Instead it pulls functions straight from the
// binary's symbol tables — `provider.nativeFunctions` is produced by
// `NativeFunctionDiscovery`, which already walks sym / dynsym and resolves
// names through the string table. Each function's `(fileOffset, size)`
// designates a contiguous range of bytes inside the .text section; we hand
// that range to Capstone for decoding.
//
// Decoding is virtualised the same way as the WAT viewer's per-function
// rendering: the upfront load discovers function ranges + brings up the
// Capstone module, but does NOT decode any bodies. Each function body is
// decoded the first time its chunk is rendered (= the first time the
// LazyColumn item enters the composition window) and cached on the
// [DisasmCatalog] so subsequent recompositions, fold toggles, and dialect
// switches reuse the work. On a Skiko-sized binary this turns an O(N)
// upfront Capstone hit into O(visible-chunks) per scroll-stop.
// ---------------------------------------------------------------------------

internal data class DisasmInstruction(
    val virtualAddress: ULong,
    val fileOffset: Long,
    val bytes: List<Int>,
    val mnemonic: String,
    val operands: String,
)

/**
 * Contiguous byte range to feed Capstone for one logical "function" — either
 * a real symbol or a synthetic section-wide fallback for stripped binaries.
 * Carries no decoded instructions; those live in [DisasmCatalog]'s cache and
 * are produced on demand per chunk.
 */
internal data class DisasmRange(
    val displayName: String,
    val virtualAddress: ULong,
    val fileOffset: Long,
    val size: Long,
    val sectionName: String?,
    /** Per-symbol arch override; non-null when a symbol's mode differs from the file-level arch. */
    val archOverride: Arch? = null,
)

/**
 * Lazy disassembly catalog: the ranges to render plus the Capstone module
 * needed to decode any single range on demand. The catalog owns the
 * per-range decoded-instruction cache, which is what makes the panel
 * virtualised — only ranges whose chunk has been composed at least once
 * are ever fed to Capstone.
 *
 * Lifetime: one per `(provider, arch)`. Dialect switches do NOT rebuild
 * the catalog (the formatter is fixed at the backend's native dialect and
 * the renderer post-processes downstream), so the decoded-instruction
 * cache survives Intel ↔ AT&T toggles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DisasmCatalog(
    val arch: Arch,
    private val disassembler: CapstoneWasmDisassembler,
    private val formatter: AssemblyFormatter,
    private val bytes: CommonByteBuffer,
    private val bytesLimit: Int,
    val ranges: List<DisasmRange>,
) {
    val totalBytes: Long = ranges.sumOf { it.size }

    /**
     * LRU-bounded decoded-body cache. The eviction policy keeps the working
     * set under control on huge stripped binaries where the user could
     * otherwise touch tens of thousands of 8 KiB chunks during navigation
     * and pin every one of them in memory. Eldest-on-access (the
     * `accessOrder = true` ctor flag) means re-opening a recently-closed
     * function does NOT evict it; the cache only churns once more than
     * [MAX_CACHED_RANGES] distinct ranges have been touched.
     *
     * Not thread-safe by itself: `LinkedHashMap.get` mutates the access
     * order on every read, so even reader threads are writers from the
     * map's perspective. Every access — read or write — goes through
     * `synchronized(cache) { ... }`. The critical sections are O(1) and
     * uncontended in practice (Capstone writes are already serialised on
     * [capstoneDispatcher]; the only readers are AsyncFunctionBody calls
     * via [cached]).
     */
    private val cache = object : LinkedHashMap<Int, List<DisasmInstruction>>(
        16,
        0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, List<DisasmInstruction>>?): Boolean =
            size > MAX_CACHED_RANGES
    }

    /**
     * Single-slot dispatcher that serialises every Capstone WASM call. The
     * module is not thread-safe (single linear-memory heap, stateful
     * `alloc` / `free_result` / `dealloc`), so all decode work must run
     * with parallelism 1. [Dispatchers.IO.limitedParallelism] is the right
     * primitive: it shares the IO thread pool, so the panel does not own a
     * dedicated thread for its lifetime, while still guaranteeing that no
     * two decodes execute concurrently. Coroutines queueing behind an
     * in-flight decode *suspend* rather than block on a monitor, which
     * keeps the Default pool free to drive AnnotatedString builds for
     * chunks whose decode has already landed.
     */
    private val capstoneDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    val rangesByName: Map<String, Int> = ranges
        .withIndex()
        .associate { (i, r) -> r.displayName to i }

    /**
     * Like [rangesByName] but keyed on the disambiguated names produced by
     * [NativeDisasmChunkTree.uniqueNames]. For non-duplicate names the key
     * is identical to [rangesByName]; for duplicates it includes the `#N`
     * suffix so the renderer can resolve every chunk header.
     */
    fun rangeIndexForUniqueName(uniqueName: String): Int? {
        // Strip the disambiguation suffix to get the original displayName,
        // then verify via rangesByName. For the common non-duplicate case
        // this is a single map lookup.
        val hashIdx = uniqueName.lastIndexOf('#')
        if (hashIdx < 0) return rangesByName[uniqueName]
        val baseName = uniqueName.substring(0, hashIdx)
        val suffix = uniqueName.substring(hashIdx + 1).toIntOrNull() ?: return rangesByName[uniqueName]
        // Walk ranges to find the Nth occurrence of baseName
        var count = 0
        for ((i, r) in ranges.withIndex()) {
            if (r.displayName == baseName) {
                if (count == suffix) return i
                count++
            }
        }
        return null
    }

    /**
     * Decoded instructions for [rangeIndex]; computed lazily and cached.
     * Suspending because Capstone is single-threaded: concurrent callers
     * queue on [capstoneDispatcher] and the runtime hands them out one at
     * a time. Cache hits short-circuit before any dispatch, so a hot
     * path scroll-back to a previously-decoded chunk returns immediately
     * on the calling thread.
     */
    suspend fun decode(rangeIndex: Int): List<DisasmInstruction> {
        synchronized(cache) { cache[rangeIndex] }?.let { return it }
        val range = ranges.getOrNull(rangeIndex) ?: return emptyList()
        return withContext(capstoneDispatcher) {
            synchronized(cache) { cache[rangeIndex] }?.let { return@withContext it }
            val decoded = decodeRange(disassembler, formatter, bytes, bytesLimit, range, arch)
                ?: emptyList()
            synchronized(cache) { cache[rangeIndex] = decoded }
            decoded
        }
    }

    /**
     * Non-suspending peek into the decoded-instruction cache. Returns the
     * decoded list when [decode] has already populated it, `null` when the
     * cache is still cold or the entry has been evicted. The renderer reads
     * through this so its `render` contract stays non-suspending; callers
     * that need the decode are expected to have awaited [decode] before
     * invoking the renderer.
     */
    fun cached(rangeIndex: Int): List<DisasmInstruction>? = synchronized(cache) { cache[rangeIndex] }

    companion object {
        /**
         * Upper bound on the number of decoded ranges held in memory at
         * once. With `MAX_RANGE_BYTES = 8 KiB` per range, ~2300 x86
         * instructions per range, and ~100 B of decoded-object overhead
         * per instruction, 256 caps the cache at roughly 60 MiB worst
         * case — comfortable for an IDE plugin. Evicted ranges decode
         * again on the next unfold, which costs a few ms per chunk.
         */
        const val MAX_CACHED_RANGES: Int = 256
    }
}

internal sealed interface DisasmLoadState {
    data object Loading : DisasmLoadState
    data class Loaded(val catalog: DisasmCatalog) : DisasmLoadState
    data class Error(val message: String) : DisasmLoadState
}

/**
 * Lightweight result of the fast first phase: range discovery + arch
 * detection. Enough to show the folded function list immediately.
 */
internal data class DisasmRangeDiscovery(
    val arch: Arch,
    val ranges: List<DisasmRange>,
    val totalBytes: Long,
    val bytes: CommonByteBuffer,
    val bytesLimit: Int,
)

// ---------------------------------------------------------------------------
// Section kinds + chunk tree + renderer
// ---------------------------------------------------------------------------

/**
 * Section kinds for the read-only virtualized disassembly view. Per-function
 * folding is enabled by emitting each function as a [FUNCTION] sticky header
 * plus a [FUNCTION_BODY] element underneath — the panel folds the body away
 * by suppressing the element while the header stays visible. The top-level
 * [TEXT] header folds the whole listing.
 */
internal object DisasmSectionKinds {
    val TEXT: SectionKind = SectionKind("disasm.text")
    val FUNCTION: SectionKind = SectionKind("disasm.function")
    val FUNCTION_BODY: SectionKind = SectionKind("disasm.function-body")
}

/**
 * [ChunkTree] over a [DisasmCatalog]'s ranges:
 * - One top-level [BinaryChunk.SectionHeader] for the `.text` group.
 * - For every range: a [BinaryChunk.SectionHeader] (the foldable label) and
 *   a [BinaryChunk.Element] (its instruction body), in that order.
 *
 * No Capstone work happens here — the tree is purely a layout descriptor.
 * Body decoding is deferred until [NativeDisasmChunkRenderer] is asked for
 * a specific FUNCTION_BODY chunk (= the chunk's LazyColumn item enters
 * composition), at which point [DisasmCatalog.decode] is invoked.
 *
 * `FUNCTION_BODY.estimatedLines` is a size/byte estimate (no decoded
 * instruction count is available at construction time). It only matters
 * for prefetch heuristics; line layout is exact once the renderer runs.
 */
internal class NativeDisasmChunkTree(ranges: List<DisasmRange>, totalBytes: Long) : ChunkTree {

    /**
     * Disambiguated display names for each range, in the same order as
     * [ranges]. Duplicate names get `#1`, `#2`, … suffixes so that every
     * [ChunkId] used as a LazyColumn key is unique. Exposed so that
     * callers (search, fold-state init) can build matching [ChunkId]s.
     */
    val uniqueNames: List<String> = run {
        val nameCounts = HashMap<String, Int>()
        ranges.map { range ->
            val count = nameCounts.getOrDefault(range.displayName, 0)
            nameCounts[range.displayName] = count + 1
            if (count == 0) range.displayName else "${range.displayName}#$count"
        }
    }

    /**
     * One catalog decode per range still produces the full, correctly-aligned
     * instruction list (no byte-aligned mid-instruction garbage). The tree
     * sub-divides the *displayed* instruction list into windows of
     * [INSTRUCTIONS_PER_WINDOW]; each window is a separate LazyColumn item
     * with its own small Compose Text. Compose virtualises the items, so the
     * EDT-bound layout cost is bounded regardless of function size.
     *
     * Window count is estimated up front from `range.size / AVG_INSTR_BYTES`;
     * trailing over-estimated windows render empty when their instruction
     * slice is out of bounds (cheap — empty Text item).
     */
    private val chunks: List<BinaryChunk> = buildList {
        val firstOffset = ranges.minOfOrNull { it.fileOffset } ?: 0L
        val lastOffset = ranges.maxOfOrNull { it.fileOffset + it.size } ?: firstOffset

        add(
            BinaryChunk.SectionHeader(
                id = ChunkId.Header(DisasmSectionKinds.TEXT),
                byteRange = firstOffset..lastOffset,
                estimatedLines = 1,
                kind = DisasmSectionKinds.TEXT,
                elementCount = ranges.size,
                totalSize = totalBytes,
            ),
        )
        ranges.forEachIndexed { rangeIdx, range ->
            val byteRange = range.fileOffset..(range.fileOffset + range.size)
            val uniqueName = uniqueNames[rangeIdx]
            add(
                BinaryChunk.SectionHeader(
                    id = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION, uniqueName),
                    byteRange = byteRange,
                    estimatedLines = 1,
                    kind = DisasmSectionKinds.FUNCTION,
                    elementCount = 1,
                    totalSize = range.size,
                ),
            )
            val estimatedInstrCount = (range.size / AVG_INSTR_BYTES).coerceAtLeast(1L)
            val windowCount = ((estimatedInstrCount + INSTRUCTIONS_PER_WINDOW - 1) /
                INSTRUCTIONS_PER_WINDOW).coerceAtLeast(1L).toInt()
            for (windowIdx in 0 until windowCount) {
                add(
                    BinaryChunk.Element(
                        id = chunkBodyId(uniqueName, windowIdx),
                        byteRange = byteRange,
                        estimatedLines = INSTRUCTIONS_PER_WINDOW.toInt(),
                        kind = DisasmSectionKinds.FUNCTION_BODY,
                        indexInSection = rangeIdx,
                        displayName = range.displayName,
                    ),
                )
            }
        }
    }

    override val totalChunks: Int get() = chunks.size

    override fun chunkAt(index: Int): BinaryChunk = chunks[index]

    override fun chunkContainingByte(offset: Long): Int? {
        chunks.forEachIndexed { i, c -> if (offset in c.byteRange) return i }
        return null
    }

    override fun chunkForId(id: ChunkId): Int? {
        chunks.forEachIndexed { i, c -> if (c.id == id) return i }
        return null
    }

    private companion object {
        // 3 B/instr balances arches: over-estimates ARM64 (4 B) and roughly
        // matches x86 (3-4 B avg), Thumb (2-3 B), RV+C (2-3 B). Slight
        // over-estimate is safe — trailing windows render empty. Under-
        // estimate would drop the function's tail; never do that.
        const val AVG_INSTR_BYTES: Long = 3L
    }
}

/**
 * Instruction-window size for FUNCTION_BODY sub-chunking. Each window
 * becomes one LazyColumn item with one Compose `Text(AnnotatedString)`.
 * Compose virtualises the items; the EDT-bound layout cost is bounded
 * to W instructions per visible window. At 256: ~256 lines × ~80 chars
 * ≈ 20 KiB of AnnotatedString per item — well within what Compose
 * `Text` lays out quickly.
 */
internal const val INSTRUCTIONS_PER_WINDOW: Long = 256L

/**
 * ChunkId factory for a windowed FUNCTION_BODY. Window 0 uses the bare
 * displayName so existing search-by-name and goto-offset resolution
 * (which build the id from a range's displayName alone) keep landing
 * on the first window. Higher windows append `" [+w<idx>]"` so their
 * ids are distinct LazyColumn keys.
 */
internal fun chunkBodyId(displayName: String, windowIdx: Int): ChunkId.Symbolic =
    if (windowIdx == 0) {
        ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, displayName)
    } else {
        ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, "$displayName [+w$windowIdx]")
    }

/**
 * Recovers the window index from a [chunkBodyId]-shaped name. Returns
 * 0 for names without the `[+w<idx>]` suffix (= window 0).
 */
internal fun windowIdxFromName(name: String): Int {
    val marker = " [+w"
    val markerStart = name.lastIndexOf(marker)
    if (markerStart < 0 || !name.endsWith("]")) return 0
    val idxStr = name.substring(markerStart + marker.length, name.length - 1)
    return idxStr.toIntOrNull() ?: 0
}

/**
 * Strips the `[+w<idx>]` suffix from a windowed name, returning the
 * underlying range displayName. Used by `rangesByName` lookups so all
 * windows of a function resolve back to its single catalog range.
 */
internal fun displayNameFromWindowed(name: String): String {
    val marker = " [+w"
    val markerStart = name.lastIndexOf(marker)
    return if (markerStart >= 0 && name.endsWith("]")) name.substring(0, markerStart) else name
}

/**
 * [ChunkRenderer] for the read-only disassembly view. Reads function bodies
 * through the [catalog]'s lazy decoder — the first time a body chunk is
 * rendered, the catalog feeds the corresponding [DisasmRange] to Capstone
 * and memoises the result. Subsequent renders of the same chunk hit the
 * cache. The [BinaryDocumentSnapshot] parameter exists only to satisfy the
 * [ChunkRenderer] contract; the source of truth for instruction bytes is
 * the catalog's byte buffer.
 *
 * The renderer carries the active [arch] + [syntax] so it can post-process
 * mnemonic / operand text into the requested dialect. Decoding is dialect-
 * independent (the WASM backend renders one canonical form per arch — Intel
 * for x86/x86_64, GAS for ARM, debug for RISC-V); the renderer is the only
 * layer that adapts to user-selected syntax. A dialect toggle rebuilds the
 * renderer but NOT the catalog, so the decoded-instruction cache survives.
 */
internal class NativeDisasmChunkRenderer(
    private val catalog: DisasmCatalog,
    private val arch: Arch,
    private val syntax: AssemblySyntax,
) : ChunkRenderer {

    override fun render(snapshot: BinaryDocumentSnapshot, chunk: BinaryChunk): TokenStream =
        when (chunk) {
            is BinaryChunk.SectionHeader -> when (chunk.kind) {
                DisasmSectionKinds.TEXT -> renderTextHeader(chunk)
                DisasmSectionKinds.FUNCTION -> renderFunctionHeader(chunk)
                else -> TokenStream.EMPTY
            }
            is BinaryChunk.Element -> when (chunk.kind) {
                DisasmSectionKinds.FUNCTION_BODY -> renderFunctionBody(chunk)
                else -> TokenStream.EMPTY
            }
        }

    private fun renderTextHeader(chunk: BinaryChunk.SectionHeader): TokenStream {
        val tokens = mutableListOf<RenderToken>()
        tokens += RenderToken(
            text = "; .text — ${chunk.elementCount} functions, ${chunk.totalSize} bytes",
            kind = RenderTokenKind.COMMENT,
            sourceOffset = chunk.byteRange.first,
        )
        return TokenStream(tokens)
    }

    private fun renderFunctionHeader(chunk: BinaryChunk.SectionHeader): TokenStream {
        val name = (chunk.id as? ChunkId.Symbolic)?.name ?: return TokenStream.EMPTY
        val rangeIdx = catalog.rangeIndexForUniqueName(name) ?: return TokenStream.EMPTY
        val range = catalog.ranges.getOrNull(rangeIdx) ?: return TokenStream.EMPTY
        val tokens = mutableListOf<RenderToken>()
        tokens += RenderToken("${range.displayName}:", RenderTokenKind.LABEL_REF, range.fileOffset)
        val entryHex = range.virtualAddress.toString(16).padStart(8, '0')
        tokens += RenderToken("  ", RenderTokenKind.WHITESPACE)
        tokens += RenderToken(
            text = "; entry=0x$entryHex, ${chunk.totalSize} bytes" +
                (range.sectionName?.let { " [$it]" } ?: ""),
            kind = RenderTokenKind.COMMENT,
            sourceOffset = range.fileOffset,
        )
        return TokenStream(tokens)
    }

    private fun renderFunctionBody(chunk: BinaryChunk.Element): TokenStream {
        // Pre-decoded read from the catalog cache. The caller (typically
        // `AsyncFunctionBody`) awaits `catalog.decode` before invoking
        // the renderer, so by the time we get here the cache is warm.
        // If somebody invokes `render` synchronously on a cold cache we
        // degrade silently to an empty body rather than block the EDT
        // on a Capstone call.
        val instructions = catalog.cached(chunk.indexInSection) ?: return TokenStream.EMPTY
        if (instructions.isEmpty()) return TokenStream.EMPTY
        // Slice by instruction window: chunk.id encodes the window index
        // via [chunkBodyId]. Trailing over-estimated windows (start past
        // actual instruction count) render empty — no Compose layout cost
        // beyond a single placeholder Text.
        val name = (chunk.id as? ChunkId.Symbolic)?.name ?: return TokenStream.EMPTY
        val windowIdx = windowIdxFromName(name)
        val start = windowIdx * INSTRUCTIONS_PER_WINDOW.toInt()
        if (start >= instructions.size) return TokenStream.EMPTY
        val end = (start + INSTRUCTIONS_PER_WINDOW.toInt()).coerceAtMost(instructions.size)
        val window = instructions.subList(start, end)
        val tokens = mutableListOf<RenderToken>()
        for (instr in window) {
            val (mnemonic, operands) = retargetSyntax(arch, syntax, instr.mnemonic, instr.operands)
            val addrText = instr.virtualAddress.toString(16).padStart(8, '0')
            tokens += RenderToken("  $addrText: ", RenderTokenKind.OFFSET_ANNOTATION, instr.fileOffset)
            tokens += RenderToken(
                text = instr.bytes.toHexByteText().padEnd(BYTE_COLUMN_WIDTH),
                kind = RenderTokenKind.RAW_BYTES,
                sourceOffset = instr.fileOffset,
            )
            tokens += RenderToken(" ", RenderTokenKind.WHITESPACE)
            tokens += RenderToken(mnemonic, RenderTokenKind.KEYWORD, instr.fileOffset)
            if (operands.isNotBlank()) {
                tokens += RenderToken(" ", RenderTokenKind.WHITESPACE)
                tokens += RenderToken(operands, RenderTokenKind.IDENTIFIER, instr.fileOffset)
            }
            tokens += LINE_BREAK
        }
        return TokenStream(tokens)
    }

    private companion object {
        const val BYTE_COLUMN_WIDTH = 24
        val LINE_BREAK = RenderToken("", RenderTokenKind.LINE_BREAK)
    }
}

private fun List<Int>.toHexByteText(): String = joinToString(" ") {
    (it and 0xFF).toString(16).padStart(2, '0')
}

// ---------------------------------------------------------------------------
// Architecture derivation (self-contained — mirrors the format-aware logic
// in NativeDisassemblyPanel.kt so the two panels stay independent).
// ---------------------------------------------------------------------------

private fun NativeBinaryProvider.archForDisasm(): Arch =
    when (val format = nativeFormat) {
        is NativeBinaryFormat.Elf -> {
            val elfProvider = this as? ElfBinaryProvider
            val profile = elfProvider?.armProfile
            val baseArch = elfArch(format.machine, format.wordSize, profile)
            // Fallback heuristic: if the file is EM_ARM and has no .ARM.attributes but the
            // overwhelming majority of STT_FUNC symbols have odd addresses, treat it as Thumb.
            if (baseArch == Arch.ARM && profile == null) {
                val fns = nativeFunctions.orEmpty().filter { !it.imported }
                if (fns.size >= 2 && fns.count { it.isThumb } > fns.size * 9 / 10) Arch.THUMB
                else baseArch
            } else {
                baseArch
            }
        }
        is NativeBinaryFormat.Pe -> peArch(format.machine)
        is NativeBinaryFormat.Coff -> peArch(format.machine)
        is NativeBinaryFormat.MachO -> machOArch(format.cpuType)
        is NativeBinaryFormat.Archive,
        is NativeBinaryFormat.FatMachO,
        -> Arch.X86_64
    }

private fun elfArch(machine: Int?, wordSize: NativeBinaryWordSize, armProfile: Char?): Arch = when (machine) {
    0x03 -> Arch.X86
    0x28 -> when (armProfile) {
        'M' -> Arch.THUMB
        else -> Arch.ARM
    }
    0x3E -> Arch.X86_64
    0xB7 -> Arch.ARM64
    0xF3 -> if (wordSize == NativeBinaryWordSize.Bits32) Arch.RISC_V_32 else Arch.RISC_V_64
    else -> Arch.X86_64
}

private fun peArch(machine: Int?): Arch = when (machine) {
    0x014C -> Arch.X86
    0x01C0, 0x01C4 -> Arch.ARM
    0x8664 -> Arch.X86_64
    0xAA64 -> Arch.ARM64
    0x5032 -> Arch.RISC_V_32
    0x5064 -> Arch.RISC_V_64
    else -> Arch.X86_64
}

private fun machOArch(cpuType: Int?): Arch = when (cpuType) {
    0x00000007 -> Arch.X86
    0x01000007 -> Arch.X86_64
    0x0000000C -> Arch.ARM
    0x0100000C -> Arch.ARM64
    0x01000018 -> Arch.RISC_V_64
    else -> Arch.X86_64
}

// ---------------------------------------------------------------------------
// Capstone-backed loader
// ---------------------------------------------------------------------------

/**
 * Fast first phase: discovers function ranges from the binary's symbol
 * tables. No Capstone work — pure metadata walk. Returns immediately
 * so the UI can show the folded function list while Capstone loads in
 * the background.
 */
internal fun discoverDisasmRanges(
    provider: NativeBinaryProvider,
): DisasmRangeDiscovery? {
    val arch = provider.archForDisasm()
    val ranges = provider.collectDisasmRanges()
    if (ranges.isEmpty()) return null
    return DisasmRangeDiscovery(
        arch = arch,
        ranges = ranges,
        totalBytes = ranges.sumOf { it.size },
        bytes = provider.bytes,
        bytesLimit = provider.bytes.size,
    )
}

/**
 * Expensive second phase: loads the Capstone WASM module and builds the
 * [DisasmCatalog]. Runs on a background thread; the UI is already showing
 * the folded function list from [discoverDisasmRanges] while this executes.
 */
internal suspend fun loadCapstoneDisassembly(
    discovery: DisasmRangeDiscovery,
): DisasmLoadState {
    val arch = discovery.arch
    val ranges = discovery.ranges

    // Backend + perf gating are driven by IntelliJ Registry keys —
    // toggleable live via Help → Find Action → Registry. The Capstone WASM
    // module (in `disassembly/capstone-wasm`) has no IntelliJ dependency,
    // so we bridge the backend choice to the system property that module
    // already reads. Changes take effect on the next panel open / Retry.
    val redlineBackend = com.intellij.openapi.util.registry.Registry.`is`(
        "hexana.disassembly.backend.redline",
    )
    val backend = if (redlineBackend) "redline" else "aot"
    System.setProperty("hexana.disassembly.backend", backend)
    org.jetbrains.hexana.HexanaLog.LOG.warn(
        "[disasm] loading Capstone WASM module, backend=$backend, " +
            "arch=${arch.name}, ranges=${ranges.size}",
    )
    val disassembler = try {
        CapstoneWasmDisassembler.loadFromClasspath()
    } catch (t: Throwable) {
        return DisasmLoadState.Error("Disassembly: failed to load Capstone WASM module. ${t.message}")
    }
    // Use the backend's native dialect for both decode and formatter — the
    // renderer adapts to the user-selected dialect downstream.
    val formatter = disassembler.formatter(arch, AssemblySyntax.INTEL)

    val catalog = DisasmCatalog(
        arch = arch,
        disassembler = disassembler,
        formatter = formatter,
        bytes = discovery.bytes,
        bytesLimit = discovery.bytesLimit,
        ranges = ranges,
    )

    // Probe the first range so arch-detection failures surface as a load-time
    // error instead of a wall of empty bodies during scroll. The result lands
    // in the catalog cache, so this work is not duplicated when that chunk
    // later enters composition.
    val probe = catalog.decode(0)
    if (probe.isEmpty() && ranges.size == 1) {
        return DisasmLoadState.Error(
            "Disassembly: Capstone returned no instructions for any range — " +
                "check the arch detection (${arch.name})."
        )
    }
    return DisasmLoadState.Loaded(catalog)
}

private fun NativeBinaryProvider.collectDisasmRanges(): List<DisasmRange> {
    val fileArch = archForDisasm()
    val symbolRanges = nativeFunctions
        .orEmpty()
        .filter { !it.imported && it.fileOffset != null && (it.size ?: 0uL) > 0uL && it.name.isNotBlank() }
        .distinctBy { it.virtualAddress }
        .sortedBy { it.virtualAddress }
        .map { fn ->
            // For EM_ARM files that are not globally M-profile, honour per-symbol Thumb mode.
            val archOverride = if (fn.isThumb && fileArch == Arch.ARM) Arch.THUMB else null
            DisasmRange(
                displayName = fn.demangledName?.takeIf { it.isNotBlank() } ?: fn.name,
                virtualAddress = fn.virtualAddress,
                fileOffset = fn.fileOffset!!,
                size = (fn.size ?: 0uL).toLong(),
                sectionName = fn.sectionName,
                archOverride = archOverride,
            )
        }
    return if (symbolRanges.isNotEmpty()) symbolRanges else executableSectionRanges()
}

/**
 * Stripped-binary fallback: every executable section becomes one big
 * synthetic "function" whose `displayName` is `<section-name>` so the UI
 * surfaces that the entry isn't symbol-derived.
 */
private fun NativeBinaryProvider.executableSectionRanges(): List<DisasmRange> = when (this) {
    is ElfBinaryProvider -> sections.orEmpty()
        .filter { (it.flags and ELF_SHF_EXECINSTR) != 0uL && it.size > 0uL && it.contentRange != null }
        .mapNotNull { sec ->
            val fileOffset = sec.fileOffset.toLongOrNullSafe() ?: return@mapNotNull null
            val size = sec.size.toLongOrNullSafe() ?: return@mapNotNull null
            DisasmRange(
                displayName = "<${sec.name.ifBlank { "section#${sec.index}" }}>",
                virtualAddress = sec.address,
                fileOffset = fileOffset,
                size = size,
                sectionName = sec.name.takeIf { it.isNotBlank() },
            )
        }
    is MachOBinaryProvider -> sections.orEmpty()
        .filter {
            (it.name == "__text" || (it.flags and MACHO_CODE_FLAG_MASK) != 0u) &&
                it.size > 0uL && it.contentRange != null
        }
        .mapNotNull { sec ->
            val fileOffset = sec.fileOffset.toLongOrNullSafe() ?: return@mapNotNull null
            val size = sec.size.toLongOrNullSafe() ?: return@mapNotNull null
            val qualified = buildString {
                val seg = sec.segmentName.takeIf { it.isNotBlank() }
                val name = sec.name.takeIf { it.isNotBlank() } ?: "section#${sec.index}"
                if (seg != null) append(seg).append(',')
                append(name)
            }
            DisasmRange(
                displayName = "<$qualified>",
                virtualAddress = sec.address,
                fileOffset = fileOffset,
                size = size,
                sectionName = qualified,
            )
        }
    is PeBinaryProvider -> sections.orEmpty()
        .filter { (it.characteristics and PE_CODE_CHAR_MASK) != 0u && it.rawDataSize > 0u }
        .map { sec ->
            DisasmRange(
                displayName = "<${sec.name.ifBlank { "section#${sec.index}" }}>",
                // PE virtual addresses are RVAs in section headers; without the
                // ImageBase load address (and any base-of-code shift) this is
                // good enough for offset display — symbol resolution is moot in
                // the stripped case anyway.
                virtualAddress = sec.virtualAddress.toULong(),
                fileOffset = sec.rawDataPointer.toLong(),
                size = sec.rawDataSize.toLong(),
                sectionName = sec.name.takeIf { it.isNotBlank() },
            )
        }
    is CoffBinaryProvider -> sections.orEmpty()
        .filter { (it.characteristics and PE_CODE_CHAR_MASK) != 0u && it.rawDataSize > 0u }
        .map { sec ->
            DisasmRange(
                displayName = "<${sec.name.ifBlank { "section#${sec.index}" }}>",
                // COFF object section `value`s are section-relative; the section's own
                // RVA is the right display base.
                virtualAddress = sec.virtualAddress.toULong(),
                fileOffset = sec.rawDataPointer.toLong(),
                size = sec.rawDataSize.toLong(),
                sectionName = sec.name.takeIf { it.isNotBlank() },
            )
        }
    else -> emptyList()
}

private const val ELF_SHF_EXECINSTR: ULong = 0x4uL

// Mach-O S_ATTR_PURE_INSTRUCTIONS (0x80000000) | S_ATTR_SOME_INSTRUCTIONS (0x00000400).
private const val MACHO_CODE_FLAG_MASK: UInt = 0x80000400u

// PE IMAGE_SCN_CNT_CODE (0x00000020) | IMAGE_SCN_MEM_EXECUTE (0x20000000).
private const val PE_CODE_CHAR_MASK: UInt = 0x20000020u

private fun ULong.toLongOrNullSafe(): Long? =
    if (this <= Long.MAX_VALUE.toULong()) toLong() else null

private fun decodeRange(
    disassembler: CapstoneWasmDisassembler,
    formatter: AssemblyFormatter,
    bytes: CommonByteBuffer,
    bytesLimit: Int,
    range: DisasmRange,
    arch: Arch,
): List<DisasmInstruction>? {
    if (range.size <= 0L || range.fileOffset < 0L) return null
    if (range.fileOffset > Int.MAX_VALUE || range.size > Int.MAX_VALUE) return null
    if (range.fileOffset + range.size > bytesLimit) return null
    val effectiveArch = range.archOverride ?: arch
    val sliced = bytes.slice(range.fileOffset.toInt(), range.size.toInt())
    return with(disassembler) {
        sliced.disassemble(baseAddress = range.virtualAddress, arch = effectiveArch)
    }.map { decoded ->
        decodeToDisasmInstruction(decoded, sliced, formatter, range.fileOffset)
    }.toList()
}

private fun decodeToDisasmInstruction(
    decoded: DecodedInstruction,
    sliced: CommonByteBuffer,
    formatter: AssemblyFormatter,
    functionFileOffset: Long,
): DisasmInstruction {
    val bytesList = (decoded.byteRange.first..decoded.byteRange.last).map { i ->
        sliced[i].toInt() and 0xFF
    }
    val mnemonic: String
    val operands: String
    when (decoded) {
        is DecodedInstruction.Valid -> {
            mnemonic = formatter.mnemonic(decoded)
            operands = formatter.operands(decoded)
        }
        is DecodedInstruction.Unknown -> {
            mnemonic = ".byte"
            operands = bytesList.joinToString(", ") { "0x${it.toString(16).padStart(2, '0')}" }
        }
    }
    return DisasmInstruction(
        virtualAddress = decoded.address,
        fileOffset = functionFileOffset + decoded.byteRange.first.toLong(),
        bytes = bytesList,
        mnemonic = mnemonic,
        operands = operands,
    )
}

// ---------------------------------------------------------------------------
// Compose surface
// ---------------------------------------------------------------------------

/**
 * Read-only virtualized disassembly panel built on the chunked-editor
 * pipeline that the WAT viewer uses. Function discovery and disassembly are
 * driven entirely by Hexana's own knowledge of the binary format —
 * `provider.nativeFunctions` for the function list (sym / dynsym + string
 * table) and Capstone for the actual decode.
 */
@Composable
internal fun NativeDisassemblyPanelVirtualized(
    provider: NativeBinaryProvider,
    state: HexViewStateHolder,
) {
    var result by remember(provider) { mutableStateOf<DisasmLoadState>(DisasmLoadState.Loading) }
    var syntax by remember(provider) { mutableStateOf(defaultSyntaxFor(provider.archForDisasm())) }
    var reloadRequest by remember(provider) { mutableStateOf(0) }

    // Single-phase background load: discovery + Capstone + probe decode all
    // run on IO. The UI shows a spinner until the catalog is ready, then
    // composes the full panel once — no partial/nullable-catalog recomposition.
    val activityTracker = DisasmActivityTracker.getInstance(state.project)
    LaunchedEffect(provider, reloadRequest) {
        result = DisasmLoadState.Loading
        activityTracker.begin()
        try {
            result = withContext(Dispatchers.IO) {
                val disc = discoverDisasmRanges(provider)
                    ?: return@withContext DisasmLoadState.Error(
                        "Disassembly: no symbol-discovered functions and no executable sections detected."
                    )
                loadCapstoneDisassembly(disc)
            }
        } finally {
            activityTracker.end()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DisasmSyntaxBar(provider.archForDisasm(), syntax) { syntax = it }
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = result) {
                DisasmLoadState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is DisasmLoadState.Error ->
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(current.message)
                        Text(
                            text = "Retry",
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable { reloadRequest++ },
                        )
                    }
                is DisasmLoadState.Loaded ->
                    VirtualizedDisasmListing(current.catalog, provider, syntax, state)
            }
        }
    }
}

@Composable
private fun DisasmSyntaxBar(
    arch: Arch,
    selected: AssemblySyntax,
    onSelected: (AssemblySyntax) -> Unit,
) {
    val choices = syntaxChoicesFor(arch)
    if (choices.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text("Syntax:")
        for (mode in choices) {
            val label = syntaxLabel(mode)
            val rendered = if (mode == selected) "[$label]" else label
            Text(
                rendered,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { onSelected(mode) },
            )
        }
    }
}

private fun defaultSyntaxFor(arch: Arch): AssemblySyntax = when (arch) {
    Arch.X86, Arch.X86_64 -> AssemblySyntax.INTEL
    Arch.ARM, Arch.THUMB, Arch.ARM64 -> AssemblySyntax.ARM_GAS
    Arch.RISC_V_32, Arch.RISC_V_64 -> AssemblySyntax.RISC_V_GAS
}

// Syntax choices the panel surfaces: only dialects whose output differs from
// the backend's native rendering after our post-processor runs. x86 has both
// Intel (backend native) and AT&T (post-processed); ARM and RISC-V have a
// single supported display dialect today, so the bar hides itself.
private fun syntaxChoicesFor(arch: Arch): List<AssemblySyntax> = when (arch) {
    Arch.X86, Arch.X86_64 -> listOf(AssemblySyntax.INTEL, AssemblySyntax.AT_T)
    Arch.ARM, Arch.THUMB, Arch.ARM64 -> listOf(AssemblySyntax.ARM_GAS)
    Arch.RISC_V_32, Arch.RISC_V_64 -> listOf(AssemblySyntax.RISC_V_GAS)
}

private fun syntaxLabel(syntax: AssemblySyntax): String = when (syntax) {
    AssemblySyntax.AT_T -> "AT&T"
    AssemblySyntax.INTEL -> "Intel"
    AssemblySyntax.ARM_GAS -> "GAS"
    AssemblySyntax.ARM_VENDOR -> "Vendor"
    AssemblySyntax.RISC_V_GAS -> "GAS"
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

internal sealed interface DisasmSearchResult {
    val label: String
    val detail: String
    val chunkId: ChunkId

    data class Symbol(
        override val label: String,
        override val detail: String,
        override val chunkId: ChunkId,
    ) : DisasmSearchResult

    data class StringHit(
        override val label: String,
        override val detail: String,
        override val chunkId: ChunkId,
    ) : DisasmSearchResult
}

/**
 * Finds the FUNCTION_BODY chunk whose byteRange contains [offset]. We walk
 * only Element chunks with kind FUNCTION_BODY rather than using the generic
 * [ChunkTree.chunkContainingByte] because the top-level TEXT SectionHeader
 * also covers the whole .text extent and would shadow every offset.
 */
private fun NativeDisasmChunkTree.findFunctionBodyContaining(offset: Long): ChunkId? {
    for (i in 0 until totalChunks) {
        val chunk = chunkAt(i)
        if (chunk is BinaryChunk.Element && chunk.kind == DisasmSectionKinds.FUNCTION_BODY) {
            if (offset in chunk.byteRange) return chunk.id
        }
    }
    return null
}


private fun computeSearchResults(
    query: String,
    catalog: DisasmCatalog,
    provider: NativeBinaryProvider,
    tree: NativeDisasmChunkTree,
    limit: Int = 50,
): List<DisasmSearchResult> {
    if (query.isBlank()) return emptyList()
    val q = query.trim()

    // 0x-prefixed queries are handled by the common searchChunksByOffset in
    // VirtualizedChunkPanel — skip them here to avoid duplicate "Go to offset" items.
    if (q.startsWith("0x", ignoreCase = true)) return emptyList()

    val qLower = q.lowercase()
    val results = mutableListOf<DisasmSearchResult>()

    for ((rangeIdx, range) in catalog.ranges.withIndex()) {
        if (results.size >= limit) break
        if (range.displayName.contains(qLower, ignoreCase = true)) {
            val vaddrHex = range.virtualAddress.toString(16)
            // Use the disambiguated name from the chunk tree so the
            // ChunkId matches the actual chunk identity.
            val uniqueName = tree.uniqueNames.getOrNull(rangeIdx)
                ?: range.displayName
            results += DisasmSearchResult.Symbol(
                label = range.displayName,
                detail = "fn · 0x$vaddrHex · ${range.size} B",
                chunkId = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, uniqueName),
            )
        }
    }

    val remaining = limit - results.size
    if (remaining > 0) {
        var stringHits = 0
        for (record in provider.strings) {
            if (stringHits >= remaining) break
            if (!record.value.contains(qLower, ignoreCase = true)) continue
            val bodyId = tree.findFunctionBodyContaining(record.offset.toLong()) ?: continue
            val offsetHex = record.offset.toString(16)
            results += DisasmSearchResult.StringHit(
                label = record.value.let { if (it.length > 60) it.take(57) + "..." else it },
                detail = "str · file 0x$offsetHex",
                chunkId = bodyId,
            )
            stringHits++
        }
    }

    return results
}


// ---------------------------------------------------------------------------
// Go-to-offset
// ---------------------------------------------------------------------------

internal sealed interface DisasmGotoResolution {
    data class Hit(val chunkId: ChunkId) : DisasmGotoResolution
    data object NotInText : DisasmGotoResolution
    data object Invalid : DisasmGotoResolution
}

/** Parses [raw] as a hex (`0x…`) or decimal unsigned 64-bit integer. Returns null on failure. */
private fun parseGotoInput(raw: String): ULong? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    return if (s.startsWith("0x", ignoreCase = true)) {
        s.drop(2).toULongOrNull(16)
    } else {
        s.toULongOrNull(10)
    }
}

/**
 * Resolves a raw offset/address string to the FUNCTION_BODY chunk that contains it.
 *
 * Resolution order:
 * 1. File offset — cast to Long (if <= Long.MAX_VALUE) and search [NativeDisasmChunkTree].
 * 2. Virtual address — walk [DisasmCatalog.ranges] for a range that spans the address.
 */
internal fun resolveGoto(
    raw: String,
    catalog: DisasmCatalog,
    tree: NativeDisasmChunkTree,
): DisasmGotoResolution {
    val value = parseGotoInput(raw) ?: return DisasmGotoResolution.Invalid

    // 1. Try as file offset.
    if (value <= Long.MAX_VALUE.toULong()) {
        val chunkId = tree.findFunctionBodyContaining(value.toLong())
        if (chunkId != null) return DisasmGotoResolution.Hit(chunkId)
    }

    // 2. Try as virtual address — use disambiguated names so the ChunkId
    //    matches the actual chunk identity in the tree.
    for ((rangeIdx, range) in catalog.ranges.withIndex()) {
        val rangeEnd = range.virtualAddress + range.size.toULong()
        if (value >= range.virtualAddress && value < rangeEnd) {
            val uniqueName = tree.uniqueNames.getOrNull(rangeIdx)
                ?: range.displayName
            return DisasmGotoResolution.Hit(
                ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, uniqueName)
            )
        }
    }

    return DisasmGotoResolution.NotInText
}


@Composable
private fun VirtualizedDisasmListing(
    catalog: DisasmCatalog,
    provider: NativeBinaryProvider,
    syntax: AssemblySyntax,
    state: HexViewStateHolder,
) {
    val snapshot = remember(catalog, provider) {
        binaryDocumentOf(provider.bytes).snapshot()
    }
    val arch = catalog.arch
    val tree = remember(catalog) { NativeDisasmChunkTree(catalog.ranges, catalog.totalBytes) }
    val renderer = remember(catalog, arch, syntax) {
        NativeDisasmChunkRenderer(catalog, arch, syntax)
    }

    // Start with every FUNCTION sticky header folded so the function list
    // is visible immediately without any Capstone work.
    val foldedState: MutableState<Set<ChunkId>> = remember(catalog, tree) {
        val initial: Set<ChunkId> = tree.uniqueNames
            .mapTo(HashSet(tree.uniqueNames.size)) {
                ChunkId.Symbolic(DisasmSectionKinds.FUNCTION, it)
            }
        mutableStateOf(initial)
    }

    if (tree.totalChunks == 0) {
        Text("Disassembly: absent. No instructions were returned.", modifier = Modifier.padding(8.dp))
        return
    }

    val disasmConfig = remember(catalog, provider, tree) {
        VirtualizedChunkPanelConfig(
            sectionDisplayName = { kind ->
                when (kind) {
                    DisasmSectionKinds.TEXT -> ".text"
                    DisasmSectionKinds.FUNCTION -> "function"
                    DisasmSectionKinds.FUNCTION_BODY -> "body"
                    else -> kind.id
                }
            },
            colorForToken = ::defaultColorFor,
            searchPlaceholder = "Search symbol, string, or hex offset (0x…)",
            computeSearchResults = { query ->
                computeSearchResults(query, catalog, provider, tree).map { result ->
                    SimpleChunkSearchResult(
                        label = result.label,
                        detail = result.detail,
                        chunkId = result.chunkId,
                    )
                }
            },
            sectionParent = mapOf(
                DisasmSectionKinds.FUNCTION to DisasmSectionKinds.TEXT,
                DisasmSectionKinds.FUNCTION_BODY to DisasmSectionKinds.FUNCTION,
            ),
            onOffsetClicked = { offset -> state.scrollHexViewTo(offset, setCursor = true) },
        )
    }

    VirtualizedChunkPanel(
        chunkTree = tree,
        config = disasmConfig,
        foldedSectionsExternal = foldedState,
        sectionHeaderContent = { chunk, folded, onToggle ->
            FoldableSectionHeader(
                tokens = renderer.render(snapshot, chunk).tokens,
                folded = folded,
                onToggle = onToggle,
            )
        },
        onSearchResultPicked = { result, navigator ->
            val chunkId = result.chunkId
            if (chunkId is ChunkId.Symbolic && chunkId.sectionKind == DisasmSectionKinds.FUNCTION_BODY) {
                val funcName = displayNameFromWindowed(chunkId.name)
                val funcHeaderId = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION, funcName)
                foldedState.value = foldedState.value - funcHeaderId
            }
            navigator.gotoChunk(chunkId)
        },
        elementContent = { chunk, _ ->
            item(key = chunk.id) {
                AsyncFunctionBody(chunk, catalog, renderer, snapshot, state.project)
            }
        },
        showSectionShortcutBar = false,
    )
}

/**
 * Sticky, click-to-fold section header. Renders the chunk's tokens plus a
 * leading triangle indicator. The bar background mirrors the WAT panel's
 * sticky-header chrome so the two views feel like the same surface.
 */
@Composable
private fun FoldableSectionHeader(
    tokens: List<RenderToken>,
    folded: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val textColor = HexanaTheme.textColor
    val accentColor = HexanaTheme.hexTextColor
    val styleForKind: (RenderTokenKind) -> SpanStyle? = { kind ->
        when (kind) {
            RenderTokenKind.KEYWORD, RenderTokenKind.LABEL_REF ->
                SpanStyle(fontWeight = FontWeight.Bold)
            RenderTokenKind.OFFSET_ANNOTATION,
            RenderTokenKind.RAW_BYTES,
            RenderTokenKind.COMMENT -> SpanStyle(color = accentColor)
            else -> null
        }
    }
    val lines = remember(tokens) { splitTokensIntoLines(tokens) }
    val headerStyle = TextStyle(
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (folded) "▶" else "▼",
            style = headerStyle.copy(color = accentColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            for (line in lines) {
                Text(text = line.toAnnotatedString(styleForKind), style = headerStyle)
            }
        }
    }
}

/**
 * Decodes + splits tokens into lines for a FUNCTION_BODY chunk. Capstone
 * decode and token splitting run off-EDT via [Dispatchers.Default] so the
 * EDT stays free during fast scroll.
 *
 * Each line is rendered as a [SelectableTokenRow] so that content tokens
 * (mnemonic + operands) are multiline-selectable while offset/byte columns
 * are excluded from selection — matching the WAT and java-methods views.
 * With windows capped at [INSTRUCTIONS_PER_WINDOW] (256) lines and only
 * visible windows composed by LazyColumn, the per-line composable count
 * stays bounded.
 */
@Composable
private fun AsyncFunctionBody(
    chunk: BinaryChunk.Element,
    catalog: DisasmCatalog,
    renderer: NativeDisasmChunkRenderer,
    snapshot: BinaryDocumentSnapshot,
    project: com.intellij.openapi.project.Project,
) {
    var lines by remember(chunk.id, renderer) {
        mutableStateOf<List<List<RenderToken>>?>(null)
    }
    LaunchedEffect(chunk.id, renderer) {
        val tracker = DisasmActivityTracker.getInstance(project)
        tracker.begin()
        try {
            val perfTracing = com.intellij.openapi.util.registry.Registry.`is`(
                "hexana.disassembly.perf.logging",
            )
            val t0 = if (perfTracing) System.nanoTime() else 0L
            catalog.decode(chunk.indexInSection)
            val tAfterDecode = if (perfTracing) System.nanoTime() else 0L
            val rendered = withContext(Dispatchers.Default) {
                val tokens = renderer.render(snapshot, chunk).tokens
                splitTokensIntoLines(tokens)
            }
            val tAfterRender = if (perfTracing) System.nanoTime() else 0L
            lines = rendered
            if (perfTracing) {
                org.jetbrains.hexana.HexanaLog.LOG.warn(
                    "[disasm-perf] chunk=${chunk.id} " +
                        "decodeMs=${(tAfterDecode - t0) / 1_000_000} " +
                        "renderMs=${(tAfterRender - tAfterDecode) / 1_000_000} " +
                        "lines=${rendered.size}",
                )
            }
        } finally {
            tracker.end()
        }
    }
    val current = lines
    if (current == null) {
        DecodingPlaceholder()
    } else {
        Column {
            for (line in current) {
                SelectableTokenRow(tokens = line)
            }
        }
    }
}


@Composable
private fun DecodingPlaceholder() {
    Text(
        text = "  // decoding…",
        style = TextStyle(color = HexanaTheme.hexTextColor, fontFamily = FontFamily.Monospace),
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

private fun List<RenderToken>.toAnnotatedString(
    styleForKind: (RenderTokenKind) -> SpanStyle?,
): AnnotatedString = buildAnnotatedString {
    for (token in this@toAnnotatedString) {
        val style = styleForKind(token.kind)
        if (style == null) append(token.text) else withStyle(style) { append(token.text) }
    }
}

// ---------------------------------------------------------------------------
// Syntax dialect adaptation
//
// The Capstone WASM backend ships one Display impl per arch:
//   - yaxpeax-x86  → Intel-flavoured
//   - yaxpeax-arm  → GAS-flavoured
//   - riscv-decode → debug-format
// To make the syntax toggle do something visible on x86, we post-process the
// Intel output into AT&T-style here. This is *approximate* — we cover the
// transforms most users notice (operand order, register `%` prefix, immediate
// `$` prefix, memory `disp(%base,%idx,scale)` form) but skip mnemonic size-
// suffix derivation (`b/w/l/q`) because that requires per-instruction operand-
// width knowledge that yaxpeax doesn't expose to us. Anything we don't
// recognise passes through untouched.
//
// ARM / RISC-V have no second dialect choice surfaced today, so the toggle
// only fires on x86 / x86_64.
// ---------------------------------------------------------------------------

internal fun retargetSyntax(
    arch: Arch,
    syntax: AssemblySyntax,
    mnemonic: String,
    operands: String,
): Pair<String, String> {
    if (syntax != AssemblySyntax.AT_T) return mnemonic to operands
    if (arch != Arch.X86 && arch != Arch.X86_64) return mnemonic to operands
    return mnemonic to intelOperandsToAtt(operands, isBranch = mnemonic.looksLikeX86Branch())
}

private fun String.looksLikeX86Branch(): Boolean {
    if (isEmpty()) return false
    if (startsWith("j")) return true // jmp, je, jne, jz, jnz, jg, jl, ja, jb, ...
    if (startsWith("loop")) return true // loop, loope, loopne, loopz, loopnz
    return this == "call" || this == "callq" || this == "callw"
}

private fun intelOperandsToAtt(operands: String, isBranch: Boolean): String {
    if (operands.isBlank()) return operands
    val parts = splitTopLevelCommas(operands)
    val converted = parts.map { intelOperandToAtt(it.trim(), isBranch) }
    // AT&T orders src,dst; Intel orders dst,src. Branches keep their single
    // target unchanged.
    val ordered = if (converted.size >= 2 && !isBranch) converted.reversed() else converted
    return ordered.joinToString(", ")
}

private fun splitTopLevelCommas(operands: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    for (c in operands) {
        when (c) {
            '[', '(' -> { depth++; sb.append(c) }
            ']', ')' -> { depth--; sb.append(c) }
            ',' -> if (depth == 0) {
                out += sb.toString()
                sb.clear()
            } else {
                sb.append(c)
            }
            else -> sb.append(c)
        }
    }
    if (sb.isNotEmpty() || out.isEmpty()) out += sb.toString()
    return out
}

private fun intelOperandToAtt(operand: String, isBranch: Boolean): String {
    if (operand.isEmpty()) return operand
    if (operand.contains('[')) return intelMemoryToAtt(operand)
    if (operand in X86_REGISTERS) return "%$operand"
    if (looksLikeNumericLiteral(operand)) return if (isBranch) operand else "\$$operand"
    return operand
}

private fun looksLikeNumericLiteral(s: String): Boolean {
    if (s.isEmpty()) return false
    val stripped = when {
        s.startsWith('-') || s.startsWith('+') -> s.drop(1)
        else -> s
    }
    if (stripped.isEmpty()) return false
    if (stripped.startsWith("0x") || stripped.startsWith("0X")) {
        return stripped.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
    return stripped.all { it.isDigit() }
}

private fun intelMemoryToAtt(operand: String): String {
    var s = operand.trim()
    // Strip size qualifiers ("qword ptr [...]", "byte ptr [...]", or just
    // bare "qword [...]"). AT&T encodes size in the mnemonic suffix, which
    // we leave alone — see file-header comment.
    for (qualifier in PTR_SIZE_QUALIFIERS) {
        if (s.startsWith(qualifier, ignoreCase = true)) {
            s = s.removePrefix(qualifier).trim()
            break
        }
    }
    // Optional segment override: "fs:[...]" → "%fs:..."
    var segmentPrefix = ""
    val segMatch = SEGMENT_OVERRIDE_REGEX.find(s)
    if (segMatch != null) {
        segmentPrefix = "%${segMatch.groupValues[1]}:"
        s = s.substring(segMatch.range.last + 1)
    }
    if (!s.startsWith("[") || !s.endsWith("]")) return segmentPrefix + s
    val inner = s.substring(1, s.length - 1).trim()

    val tokens = tokenizeMemoryExpression(inner)
    var base: String? = null
    var index: String? = null
    var scale: String? = null
    var displacement: String? = null
    var i = 0
    while (i < tokens.size) {
        val tok = tokens[i]
        if (tok == "+" || tok == "-" || tok == "*") { i++; continue }
        val nextIsScale = i + 2 < tokens.size && tokens[i + 1] == "*" && looksLikeNumericLiteral(tokens[i + 2])
        if (tok in X86_REGISTERS) {
            if (nextIsScale) {
                index = tok
                scale = tokens[i + 2]
                i += 3
                continue
            }
            if (base == null) base = tok else if (index == null) index = tok
            i++
            continue
        }
        if (looksLikeNumericLiteral(tok)) {
            val signed = i > 0 && tokens[i - 1] == "-"
            displacement = if (signed) "-$tok" else tok
            i++
            continue
        }
        i++
    }

    val sb = StringBuilder()
    sb.append(segmentPrefix)
    if (displacement != null) sb.append(displacement)
    if (base != null || index != null) {
        sb.append('(')
        if (base != null) sb.append("%$base")
        if (index != null) {
            sb.append(',').append("%$index")
            if (scale != null) sb.append(',').append(scale)
        }
        sb.append(')')
    }
    return sb.toString().ifEmpty { operand }
}

private fun tokenizeMemoryExpression(inner: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        when {
            c.isWhitespace() -> {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                i++
            }
            c == '+' || c == '-' || c == '*' -> {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                out += c.toString()
                i++
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
}

private val SEGMENT_OVERRIDE_REGEX: Regex = Regex("^([cdsefg]s):")

private val PTR_SIZE_QUALIFIERS: List<String> = listOf(
    "byte ptr", "word ptr", "dword ptr", "qword ptr", "tbyte ptr",
    "xmmword ptr", "ymmword ptr", "zmmword ptr", "fword ptr",
    "byte", "word", "dword", "qword", "tbyte",
    "xmmword", "ymmword", "zmmword", "fword",
)

private val X86_REGISTERS: Set<String> = setOf(
    // 64-bit GPRs
    "rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp",
    "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15",
    // 32-bit GPRs
    "eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp",
    "r8d", "r9d", "r10d", "r11d", "r12d", "r13d", "r14d", "r15d",
    // 16-bit GPRs
    "ax", "bx", "cx", "dx", "si", "di", "bp", "sp",
    "r8w", "r9w", "r10w", "r11w", "r12w", "r13w", "r14w", "r15w",
    // 8-bit GPRs
    "al", "bl", "cl", "dl", "ah", "bh", "ch", "dh",
    "sil", "dil", "bpl", "spl",
    "r8b", "r9b", "r10b", "r11b", "r12b", "r13b", "r14b", "r15b",
    // Segment / instruction-pointer
    "cs", "ds", "es", "fs", "gs", "ss",
    "rip", "eip", "ip",
    // x87 stack
    "st", "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7",
    // SIMD
    "xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7",
    "xmm8", "xmm9", "xmm10", "xmm11", "xmm12", "xmm13", "xmm14", "xmm15",
    "ymm0", "ymm1", "ymm2", "ymm3", "ymm4", "ymm5", "ymm6", "ymm7",
    "ymm8", "ymm9", "ymm10", "ymm11", "ymm12", "ymm13", "ymm14", "ymm15",
    "zmm0", "zmm1", "zmm2", "zmm3", "zmm4", "zmm5", "zmm6", "zmm7",
    "zmm8", "zmm9", "zmm10", "zmm11", "zmm12", "zmm13", "zmm14", "zmm15",
)