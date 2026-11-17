package org.jetbrains.hexana.providers

import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * DWARF-backed source locations for WASM modules.
 *
 * Queries take MODULE-ABSOLUTE byte offsets (the hex view / WAT / table-tab
 * convention); wasm DWARF line-program addresses are relative to the start of
 * the code section's content, so [addressBase] (that content offset) is
 * subtracted before lookup.
 *
 * Rows come from BOTH resolver passes, merged and sorted once, lazily —
 * mirroring [NativeDwarfSourceLocationProvider]. The CU-scoped pass alone
 * misses compilation units that describe their range via `DW_AT_ranges`
 * instead of low/high pc (rustc does this), which made every lookup return
 * null on Rust-produced modules.
 */
class DwarfSourceLocationProvider(
    private val buffer: CommonByteBuffer,
    dwarfAccessor: DwarfAccessor,
    private val addressBase: Long = 0L,
) : HexanaSourceLocationProvider {
    private val dwarfSourceLocationResolver = DwarfSourceLocationResolver(dwarfAccessor)

    private val bestLocations: List<DwarfSourceLocationResolver.AddressSourceLocation> by lazy {
        val cuScoped = runCatching {
            with(dwarfSourceLocationResolver) { buffer.resolveLocations() }
        }.getOrElse { emptyList() }
        val lineTableOnly = runCatching {
            with(dwarfSourceLocationResolver) { buffer.resolveLineTableLocations() }
        }.getOrElse { emptyList() }
        (cuScoped + lineTableOnly)
            .distinctBy { row ->
                val location = row.sourceLocation
                listOf(row.offset, location.file, location.line, location.column)
            }
            .sortedBy { it.offset }
    }

    override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? {
        val relative = byteOffset - addressBase
        if (relative < 0) return null
        val loc = bestLocations.findLast { it.offset <= relative.toULong() }?.sourceLocation ?: return null
        return HexanaSourceLocation(
            filePath = loc.file,
            line = loc.line,
            column = loc.column,
        )
    }

    override fun getFirstSourceLocationInRange(startOffset: Int, endOffset: Int): HexanaSourceLocation? {
        val end = endOffset - addressBase
        if (end <= 0) return null
        val start = (startOffset - addressBase).coerceAtLeast(0L).toULong()
        val loc = bestLocations
            .firstOrNull { it.offset >= start && it.offset < end.toULong() }
            ?.sourceLocation ?: return null
        return HexanaSourceLocation(
            filePath = loc.file,
            line = loc.line,
            column = loc.column,
        )
    }
}
