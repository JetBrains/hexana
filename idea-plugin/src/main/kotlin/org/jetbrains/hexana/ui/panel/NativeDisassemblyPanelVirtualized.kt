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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
// that range to Capstone for decoding. The view is a pure read-through of
// our own knowledge of the binary format.
// ---------------------------------------------------------------------------

internal data class DisasmInstruction(
    val virtualAddress: ULong,
    val fileOffset: Long,
    val bytes: List<Int>,
    val mnemonic: String,
    val operands: String,
)

internal data class DisasmFunction(
    val name: String,
    val virtualAddress: ULong,
    val fileOffset: Long,
    val size: Long,
    val sectionName: String?,
    val instructions: List<DisasmInstruction>,
)

internal data class DisasmUnit(val functions: List<DisasmFunction>) {
    val totalBytes: Long = functions.sumOf { it.size }
}

internal sealed interface DisasmLoadState {
    data object Loading : DisasmLoadState
    data class Loaded(val unit: DisasmUnit) : DisasmLoadState
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
 * [ChunkTree] over a decoded [DisasmUnit]:
 * - One top-level [BinaryChunk.SectionHeader] for the `.text` group.
 * - For every function: a [BinaryChunk.SectionHeader] (the foldable label) and
 *   a [BinaryChunk.Element] (its instruction body), in that order.
 */
internal class NativeDisasmChunkTree(unit: DisasmUnit) : ChunkTree {

    private val chunks: List<BinaryChunk> = buildList {
        val functions = unit.functions
        val firstOffset = functions.minOfOrNull { it.fileOffset } ?: 0L
        val lastOffset = functions.maxOfOrNull { it.fileOffset + it.size } ?: firstOffset

        add(
            BinaryChunk.SectionHeader(
                id = ChunkId.Header(DisasmSectionKinds.TEXT),
                byteRange = firstOffset..lastOffset,
                estimatedLines = 1,
                kind = DisasmSectionKinds.TEXT,
                elementCount = functions.size,
                totalSize = unit.totalBytes,
            ),
        )
        functions.forEachIndexed { idx, fn ->
            val range = fn.fileOffset..(fn.fileOffset + fn.size)
            add(
                BinaryChunk.SectionHeader(
                    id = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION, fn.name),
                    byteRange = range,
                    estimatedLines = 1,
                    kind = DisasmSectionKinds.FUNCTION,
                    elementCount = 1,
                    totalSize = fn.size,
                ),
            )
            add(
                BinaryChunk.Element(
                    id = ChunkId.Symbolic(DisasmSectionKinds.FUNCTION_BODY, fn.name),
                    byteRange = range,
                    estimatedLines = fn.instructions.size,
                    kind = DisasmSectionKinds.FUNCTION_BODY,
                    indexInSection = idx,
                    displayName = fn.name,
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
}

/**
 * [ChunkRenderer] for the read-only disassembly view. Reads from the decoded
 * [DisasmUnit] directly; the [BinaryDocumentSnapshot] parameter exists only
 * to satisfy the [ChunkRenderer] contract — instruction bytes are already
 * baked into the unit.
 *
 * The renderer carries the active [arch] + [syntax] so it can post-process
 * mnemonic / operand text into the requested dialect. Decoding is dialect-
 * independent (the WASM backend renders one canonical form per arch — Intel
 * for x86/x86_64, GAS for ARM, debug for RISC-V); the renderer is the only
 * layer that adapts to user-selected syntax.
 */
internal class NativeDisasmChunkRenderer(
    private val unit: DisasmUnit,
    private val arch: Arch,
    private val syntax: AssemblySyntax,
) : ChunkRenderer {

    private val functionsByName: Map<String, DisasmFunction> = unit.functions.associateBy { it.name }

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
        val fn = functionsByName[name] ?: return TokenStream.EMPTY
        val tokens = mutableListOf<RenderToken>()
        tokens += RenderToken("${fn.name}:", RenderTokenKind.LABEL_REF, fn.fileOffset)
        val entryHex = fn.virtualAddress.toString(16).padStart(8, '0')
        tokens += RenderToken("  ", RenderTokenKind.WHITESPACE)
        tokens += RenderToken(
            text = "; entry=0x$entryHex, ${chunk.totalSize} bytes" +
                (fn.sectionName?.let { " [$it]" } ?: ""),
            kind = RenderTokenKind.COMMENT,
            sourceOffset = fn.fileOffset,
        )
        return TokenStream(tokens)
    }

    private fun renderFunctionBody(chunk: BinaryChunk.Element): TokenStream {
        val fn = unit.functions.getOrNull(chunk.indexInSection) ?: return TokenStream.EMPTY
        val tokens = mutableListOf<RenderToken>()
        for (instr in fn.instructions) {
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
 * Builds the [DisasmUnit] for the virtualized panel.
 *
 * Two sourcing modes, with the symbol path preferred:
 *
 * 1. **Symbol-driven** — pulls function records from
 *    [NativeBinaryProvider.nativeFunctions] (built by `NativeFunctionDiscovery`
 *    over the ELF/Mach-O/PE sym + dynsym tables). Each non-imported symbol
 *    with a known `(fileOffset, size)` becomes a [DisasmFunction] named after
 *    the symbol.
 *
 * 2. **Stripped fallback** — when the symbol path yields no candidates (the
 *    binary is stripped, or the symbol source isn't recognised), enumerate
 *    the format's executable sections and emit one synthetic [DisasmFunction]
 *    per section. The synthetic name uses `<section-name>` framing so the
 *    UI makes the absence of symbols visible.
 *
 * Both paths feed the same Capstone decode loop downstream.
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

    val functions = ranges.mapNotNull { range ->
        val instructions = decodeRange(disassembler, formatter, bytes, bytesLimit, range, arch)
            ?: return@mapNotNull null
        DisasmFunction(
            name = range.displayName,
            virtualAddress = range.virtualAddress,
            fileOffset = range.fileOffset,
            size = range.size,
            sectionName = range.sectionName,
            instructions = instructions,
        )
    }

    if (functions.all { it.instructions.isEmpty() }) {
        return DisasmLoadState.Error(
            "Disassembly: Capstone returned no instructions for any range — check the arch detection (${arch.name})."
        )
    }
    return DisasmLoadState.Loaded(DisasmUnit(functions))
}

/**
 * Contiguous byte range to feed Capstone for one logical "function" — either
 * a real symbol or a synthetic section-wide fallback for stripped binaries.
 */
private data class DisasmRange(
    val displayName: String,
    val virtualAddress: ULong,
    val fileOffset: Long,
    val size: Long,
    val sectionName: String?,
)

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
    // Capstone over the whole binary. The renderer applies the dialect
    // transform on the cached `DisasmUnit`.
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
                    VirtualizedDisasmListing(current.unit, provider, syntax, state)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VirtualizedDisasmListing(
    unit: DisasmUnit,
    provider: NativeBinaryProvider,
    syntax: AssemblySyntax,
    @Suppress("UNUSED_PARAMETER") state: HexViewStateHolder,
) {
    // Snapshot satisfies the ChunkRenderer contract; the renderer reads from
    // `unit` directly.
    val snapshot = remember(unit, provider) {
        binaryDocumentOf(provider.bytes).snapshot()
    }
    val arch = remember(provider) { provider.archForDisasm() }
    val tree = remember(unit) { NativeDisasmChunkTree(unit) }
    // Keyed on `syntax` so a dialect switch rebuilds the renderer instance
    // and invalidates per-chunk token caches downstream (their `remember`
    // keys include `renderer` via `unit, i, syntax`).
    val renderer = remember(unit, arch, syntax) { NativeDisasmChunkRenderer(unit, arch, syntax) }
    val listState = rememberLazyListState()

    val foldedState: MutableState<Set<ChunkId>> = remember(unit) { mutableStateOf(emptySet()) }
    var folded by foldedState

    if (tree.totalChunks == 0) {
        Text("Disassembly: absent. No instructions were returned.", modifier = Modifier.padding(8.dp))
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
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
                            val tokens = remember(unit, i, syntax) {
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