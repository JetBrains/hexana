package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeStringRecord
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.colorPalette

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
     * Per-range decoded-body cache. Concurrent because Compose may pull
     * decode results from the main thread while a background prefetcher
     * (if ever added) writes from an IO dispatcher. The map is read-mostly
     * — each range is decoded at most once for the panel's lifetime.
     */
    private val cache = ConcurrentHashMap<Int, List<DisasmInstruction>>()

    val rangesByName: Map<String, Int> = ranges
        .withIndex()
        .associate { (i, r) -> r.displayName to i }

    /** Decoded instructions for [rangeIndex]; computed and cached on first call. */
    fun decode(rangeIndex: Int): List<DisasmInstruction> {
        cache[rangeIndex]?.let { return it }
        val range = ranges.getOrNull(rangeIndex) ?: return emptyList()
        val decoded = decodeRange(disassembler, formatter, bytes, bytesLimit, range, arch)
            ?: emptyList()
        cache[rangeIndex] = decoded
        return decoded
    }
}

internal sealed interface DisasmLoadState {
    data object Loading : DisasmLoadState
    data class Loaded(val catalog: DisasmCatalog) : DisasmLoadState
    data class Error(val message: String) : DisasmLoadState
}

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
        ranges.forEachIndexed { idx, range ->
            val byteRange = range.fileOffset..(range.fileOffset + range.size)
            add(
                BinaryChunk.SectionHeader(
                    id = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION, range.displayName),
                    byteRange = byteRange,
                    estimatedLines = 1,
                    kind = DisasmSectionKinds.FUNCTION,
                    elementCount = 1,
                    totalSize = range.size,
                ),
            )
            add(
                BinaryChunk.Element(
                    id = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, range.displayName),
                    byteRange = byteRange,
                    estimatedLines = (range.size / AVG_INSTR_BYTES).coerceAtLeast(1L).toInt(),
                    kind = DisasmSectionKinds.FUNCTION_BODY,
                    indexInSection = idx,
                    displayName = range.displayName,
                ),
            )
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
        // Rough average across the supported arches: x86 ~3.5 B, ARM/AArch64 4 B,
        // RISC-V 2-4 B with compressed extension. Used only for LazyColumn's
        // estimatedLines heuristic, so a coarse value is fine.
        const val AVG_INSTR_BYTES: Long = 4L
    }
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
        val rangeIdx = catalog.rangesByName[name] ?: return TokenStream.EMPTY
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
        // Lazy decode: the first render of this chunk pays the Capstone cost;
        // subsequent renders (recompositions, fold toggles, dialect switches)
        // hit the catalog cache.
        val instructions = catalog.decode(chunk.indexInSection)
        if (instructions.isEmpty()) return TokenStream.EMPTY
        val tokens = mutableListOf<RenderToken>()
        for (instr in instructions) {
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
        is NativeBinaryFormat.Elf -> elfArch(format.machine, format.wordSize)
        is NativeBinaryFormat.Pe -> peArch(format.machine)
        is NativeBinaryFormat.Coff -> peArch(format.machine)
        is NativeBinaryFormat.MachO -> machOArch(format.cpuType)
        is NativeBinaryFormat.Archive,
        is NativeBinaryFormat.FatMachO,
        -> Arch.X86_64
    }

private fun elfArch(machine: Int?, wordSize: NativeBinaryWordSize): Arch = when (machine) {
    0x03 -> Arch.X86
    0x28 -> Arch.ARM
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
 * Builds the [DisasmCatalog] for the virtualised panel — discovers the
 * function ranges, brings up the Capstone module, and returns. No bodies
 * are decoded here; per-body Capstone calls happen lazily inside
 * [NativeDisasmChunkRenderer] when each chunk first enters composition.
 *
 * Two range-sourcing modes, with the symbol path preferred:
 *
 * 1. **Symbol-driven** — pulls function records from
 *    [NativeBinaryProvider.nativeFunctions] (built by `NativeFunctionDiscovery`
 *    over the ELF/Mach-O/PE sym + dynsym tables). Each non-imported symbol
 *    with a known `(fileOffset, size)` becomes a [DisasmRange] named after
 *    the symbol.
 *
 * 2. **Stripped fallback** — when the symbol path yields no candidates (the
 *    binary is stripped, or the symbol source isn't recognised), enumerate
 *    the format's executable sections and emit one synthetic [DisasmRange]
 *    per section. The synthetic name uses `<section-name>` framing so the
 *    UI makes the absence of symbols visible.
 *
 * A single probe decode runs on the first range so arch-detection errors
 * surface immediately as a load-time `Error` rather than as silent empty
 * function bodies during scroll.
 */
internal suspend fun loadCapstoneDisassembly(
    provider: NativeBinaryProvider,
): DisasmLoadState {
    val arch = provider.archForDisasm()
    val ranges = provider.collectDisasmRanges()
    if (ranges.isEmpty()) {
        return DisasmLoadState.Error(
            "Disassembly: no symbol-discovered functions and no executable sections detected."
        )
    }

    val disassembler = try {
        CapstoneWasmDisassembler.loadFromClasspath()
    } catch (t: Throwable) {
        return DisasmLoadState.Error("Disassembly: failed to load Capstone WASM module. ${t.message}")
    }
    // Use the backend's native dialect for both decode and formatter — the
    // renderer adapts to the user-selected dialect downstream.
    val formatter = disassembler.formatter(arch, AssemblySyntax.INTEL)
    val bytes = provider.bytes
    val bytesLimit = bytes.size

    val catalog = DisasmCatalog(
        arch = arch,
        disassembler = disassembler,
        formatter = formatter,
        bytes = bytes,
        bytesLimit = bytesLimit,
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
    val symbolRanges = nativeFunctions
        .orEmpty()
        .filter { !it.imported && it.fileOffset != null && (it.size ?: 0uL) > 0uL && it.name.isNotBlank() }
        .distinctBy { it.virtualAddress }
        .sortedBy { it.virtualAddress }
        .map { fn ->
            DisasmRange(
                displayName = fn.demangledName?.takeIf { it.isNotBlank() } ?: fn.name,
                virtualAddress = fn.virtualAddress,
                fileOffset = fn.fileOffset!!,
                size = (fn.size ?: 0uL).toLong(),
                sectionName = fn.sectionName,
            )
        }
    if (symbolRanges.isNotEmpty()) return symbolRanges
    return executableSectionRanges()
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
    val sliced = bytes.slice(range.fileOffset.toInt(), range.size.toInt())
    return with(disassembler) {
        sliced.disassemble(baseAddress = range.virtualAddress, arch = arch)
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

    // Decoding is dialect-independent — switching syntax must not re-run
    // Capstone over the binary. The renderer applies the dialect transform
    // on the catalog's cached instructions; the catalog survives toggles.
    LaunchedEffect(provider, reloadRequest) {
        result = DisasmLoadState.Loading
        result = withContext(Dispatchers.IO) {
            loadCapstoneDisassembly(provider)
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
    Arch.ARM, Arch.ARM64 -> AssemblySyntax.ARM_GAS
    Arch.RISC_V_32, Arch.RISC_V_64 -> AssemblySyntax.RISC_V_GAS
}

// Syntax choices the panel surfaces: only dialects whose output differs from
// the backend's native rendering after our post-processor runs. x86 has both
// Intel (backend native) and AT&T (post-processed); ARM and RISC-V have a
// single supported display dialect today, so the bar hides itself.
private fun syntaxChoicesFor(arch: Arch): List<AssemblySyntax> = when (arch) {
    Arch.X86, Arch.X86_64 -> listOf(AssemblySyntax.INTEL, AssemblySyntax.AT_T)
    Arch.ARM, Arch.ARM64 -> listOf(AssemblySyntax.ARM_GAS)
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

/**
 * Mirrors the VirtualizedDisasmListing render loop exactly — same hideAll /
 * hideBody logic, same items that produce a stickyHeader or item call — so the
 * returned index is the LazyColumn item index that [target] occupies after
 * [folded] is applied. Returns null when [target] is not visible (hidden by a
 * fold or not present in the tree).
 */
private fun NativeDisasmChunkTree.computeLazyItemIndex(folded: Set<ChunkId>, target: ChunkId): Int? {
    var visibleIndex = 0
    var hideAll = false
    var hideBody = false
    for (i in 0 until totalChunks) {
        val chunk = chunkAt(i)
        when (chunk) {
            is BinaryChunk.SectionHeader -> when (chunk.kind) {
                DisasmSectionKinds.TEXT -> {
                    hideAll = chunk.id in folded
                    if (chunk.id == target) return visibleIndex
                    visibleIndex++
                }
                DisasmSectionKinds.FUNCTION -> {
                    if (!hideAll) {
                        hideBody = chunk.id in folded
                        if (chunk.id == target) return visibleIndex
                        visibleIndex++
                    }
                }
                else -> Unit
            }
            is BinaryChunk.Element -> {
                if (!hideAll && !hideBody) {
                    if (chunk.id == target) return visibleIndex
                    visibleIndex++
                }
            }
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
    val q = query.trim().lowercase()
    val results = mutableListOf<DisasmSearchResult>()

    for (range in catalog.ranges) {
        if (results.size >= limit) break
        if (range.displayName.contains(q, ignoreCase = true)) {
            val vaddrHex = range.virtualAddress.toString(16)
            results += DisasmSearchResult.Symbol(
                label = range.displayName,
                detail = "fn · 0x$vaddrHex · ${range.size} B",
                chunkId = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, range.displayName),
            )
        }
    }

    val remaining = limit - results.size
    if (remaining > 0) {
        var stringHits = 0
        for (record in provider.strings) {
            if (stringHits >= remaining) break
            if (!record.value.contains(q, ignoreCase = true)) continue
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

@Composable
private fun DisasmSearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    results: List<DisasmSearchResult>,
    onResultPicked: (DisasmSearchResult) -> Unit,
) {
    val textColor = HexanaTheme.textColor
    val accentColor = HexanaTheme.hexTextColor
    val hoverBg = if (JewelTheme.isDark) {
        JewelTheme.colorPalette.grayOrNull(3) ?: Color(0xFF393B40)
    } else {
        JewelTheme.colorPalette.grayOrNull(12) ?: Color(0xFFEBECF0)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text("Search symbol or string") },
            leadingIcon = {
                Icon(
                    AllIconsKeys.Actions.Search,
                    contentDescription = null,
                    iconClass = AllIcons::class.java,
                    modifier = Modifier.size(16.dp),
                )
            },
            trailingIcon = if (query.text.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange(TextFieldValue("")) }) {
                        Icon(
                            AllIconsKeys.Actions.Close,
                            contentDescription = "Clear search",
                            iconClass = AllIcons::class.java,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                null
            },
        )

        if (results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .background(
                        if (JewelTheme.isDark) {
                            JewelTheme.colorPalette.grayOrNull(2) ?: Color(0xFF2B2D30)
                        } else {
                            JewelTheme.colorPalette.grayOrNull(14) ?: Color(0xFFFFFFFF)
                        }
                    ),
            ) {
                items(results) { result ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResultPicked(result) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = result.label,
                            style = TextStyle(color = textColor, fontFamily = FontFamily.Monospace),
                        )
                        Text(
                            text = result.detail,
                            style = TextStyle(color = accentColor, fontFamily = FontFamily.Monospace),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(hoverBg),
                    )
                }
            }
        }
    }
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

    // 2. Try as virtual address.
    for (range in catalog.ranges) {
        val rangeEnd = range.virtualAddress + range.size.toULong()
        if (value >= range.virtualAddress && value < rangeEnd) {
            return DisasmGotoResolution.Hit(
                ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, range.displayName)
            )
        }
    }

    return DisasmGotoResolution.NotInText
}

/** Error color used for inline "not in .text" / "invalid number" feedback. */
private val GOTO_ERROR_COLOR = Color(0xFFD05050)

@Composable
private fun DisasmGotoBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
            placeholder = { Text("Go to offset (0x… or decimal)") },
        )
        DefaultButton(
            onClick = onSubmit,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text("Go")
        }
        if (error != null) {
            Text(
                text = error,
                style = androidx.compose.ui.text.TextStyle(color = GOTO_ERROR_COLOR),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VirtualizedDisasmListing(
    catalog: DisasmCatalog,
    provider: NativeBinaryProvider,
    syntax: AssemblySyntax,
    @Suppress("UNUSED_PARAMETER") state: HexViewStateHolder,
) {
    // Snapshot satisfies the ChunkRenderer contract; the renderer reads
    // instructions through the catalog's lazy decoder.
    val snapshot = remember(catalog, provider) {
        binaryDocumentOf(provider.bytes).snapshot()
    }
    val arch = catalog.arch
    val tree = remember(catalog) { NativeDisasmChunkTree(catalog.ranges, catalog.totalBytes) }
    // Keyed on `syntax` so a dialect switch rebuilds the renderer instance
    // (which invalidates per-chunk token caches downstream via the `remember`
    // key on `chunk.id, renderer`). The decoded-instruction cache lives on
    // the catalog and survives this rebuild — Capstone is not re-run.
    val renderer = remember(catalog, arch, syntax) {
        NativeDisasmChunkRenderer(catalog, arch, syntax)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val foldedState: MutableState<Set<ChunkId>> = remember(catalog) { mutableStateOf(emptySet()) }
    var folded by foldedState

    var searchQuery by remember(catalog) { mutableStateOf(TextFieldValue("")) }
    val searchResults = remember(catalog, searchQuery.text, provider) {
        computeSearchResults(searchQuery.text, catalog, provider, tree)
    }

    var gotoInput by remember(catalog) { mutableStateOf(TextFieldValue("")) }
    var gotoError by remember(catalog) { mutableStateOf<String?>(null) }

    if (tree.totalChunks == 0) {
        Text("Disassembly: absent. No instructions were returned.", modifier = Modifier.padding(8.dp))
        return
    }

    // Unfold ancestors of [targetChunkId] then scroll to it without animation.
    // Always unfolds the top-level TEXT header; for FUNCTION_BODY targets, also
    // unfolds the sibling FUNCTION header so the body is actually visible.
    fun jumpTo(targetChunkId: ChunkId) {
        var newFolded = folded - ChunkId.Header(DisasmSectionKinds.TEXT)
        if (targetChunkId is ChunkId.Symbolic && targetChunkId.sectionKind == DisasmSectionKinds.FUNCTION_BODY) {
            newFolded = newFolded - ChunkId.Symbolic(DisasmSectionKinds.FUNCTION, targetChunkId.name)
        }
        folded = newFolded
        val itemIndex = tree.computeLazyItemIndex(newFolded, targetChunkId) ?: return
        scope.launch { listState.scrollToItem(itemIndex) }
    }

    fun submitGoto() {
        when (val res = resolveGoto(gotoInput.text, catalog, tree)) {
            is DisasmGotoResolution.Hit -> { gotoError = null; jumpTo(res.chunkId) }
            DisasmGotoResolution.NotInText -> gotoError = "not in .text"
            DisasmGotoResolution.Invalid -> gotoError = "invalid number"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DisasmSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            results = searchResults,
            onResultPicked = { result ->
                jumpTo(result.chunkId)
                searchQuery = TextFieldValue("")
            },
        )
        DisasmGotoBar(
            value = gotoInput,
            onValueChange = { gotoInput = it; gotoError = null },
            error = gotoError,
            onSubmit = ::submitGoto,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // Walk the chunk tree once, tracking two fold scopes:
            //   - `hideAll` mirrors the top-level .text fold state and suppresses
            //     every per-function header + body below it.
            //   - `hideBody` mirrors the current function header's fold state and
            //     suppresses only that function's FUNCTION_BODY element.
            var hideAll = false
            var hideBody = false
            for (i in 0 until tree.totalChunks) {
                val chunk = tree.chunkAt(i)
                when (chunk) {
                    is BinaryChunk.SectionHeader -> when (chunk.kind) {
                        DisasmSectionKinds.TEXT -> {
                            hideAll = chunk.id in folded
                            val isFolded = hideAll
                            stickyHeader(key = chunk.id) {
                                FoldableSectionHeader(
                                    tokens = renderer.render(snapshot, chunk).tokens,
                                    folded = isFolded,
                                    onToggle = { folded = toggleFold(folded, chunk.id) },
                                )
                            }
                        }
                        DisasmSectionKinds.FUNCTION -> {
                            if (!hideAll) {
                                hideBody = chunk.id in folded
                                val isFolded = hideBody
                                stickyHeader(key = chunk.id) {
                                    FoldableSectionHeader(
                                        tokens = renderer.render(snapshot, chunk).tokens,
                                        folded = isFolded,
                                        onToggle = { folded = toggleFold(folded, chunk.id) },
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                    is BinaryChunk.Element -> {
                        if (!hideAll && !hideBody) {
                            item(key = chunk.id) {
                                // Body chunks decode lazily through `catalog.decode`
                                // — only the chunks LazyColumn actually composes
                                // (= visible + small prefetch window) ever hit
                                // Capstone. The `remember` key freezes the rendered
                                // token list per (chunk.id, renderer) so scroll
                                // through already-decoded bodies is allocation-free.
                                val tokens = remember(chunk.id, renderer) {
                                    renderer.render(snapshot, chunk).tokens
                                }
                                ChunkLines(tokens)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun toggleFold(set: Set<ChunkId>, id: ChunkId): Set<ChunkId> =
    if (id in set) set - id else set + id

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
    val indicator = if (folded) "▶" else "▼"
    val monospace = TextStyle(color = textColor, fontFamily = FontFamily.Monospace)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$indicator ",
            style = monospace.copy(color = accentColor),
        )
        Column {
            for (line in lines) {
                Text(text = line.toAnnotatedString(styleForKind), style = monospace)
            }
        }
    }
}

@Composable
private fun ChunkLines(tokens: List<RenderToken>) {
    val textColor = HexanaTheme.textColor
    val accentColor = HexanaTheme.hexTextColor
    val styleForKind: (RenderTokenKind) -> SpanStyle? = { kind ->
        when (kind) {
            RenderTokenKind.KEYWORD, RenderTokenKind.LABEL_REF -> SpanStyle(fontWeight = FontWeight.Bold)
            RenderTokenKind.OFFSET_ANNOTATION,
            RenderTokenKind.RAW_BYTES,
            RenderTokenKind.COMMENT -> SpanStyle(color = accentColor)
            else -> null
        }
    }
    val lines = remember(tokens) { splitTokensIntoLines(tokens) }
    Column {
        for (line in lines) {
            Text(
                text = line.toAnnotatedString(styleForKind),
                style = TextStyle(color = textColor, fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

private fun splitTokensIntoLines(tokens: List<RenderToken>): List<List<RenderToken>> {
    if (tokens.isEmpty()) return emptyList()
    val result = mutableListOf<List<RenderToken>>()
    var current = mutableListOf<RenderToken>()
    for (token in tokens) {
        if (token.kind == RenderTokenKind.LINE_BREAK) {
            result += current
            current = mutableListOf()
        } else {
            current += token
        }
    }
    if (current.isNotEmpty()) result += current
    return result
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