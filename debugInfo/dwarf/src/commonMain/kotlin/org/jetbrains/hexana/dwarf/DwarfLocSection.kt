package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Represents the `.debug_loc` section which contains location lists
 * referenced by `DW_AT_location` attributes with `DW_FORM_sec_offset`
 * or `DW_FORM_data4`/`DW_FORM_data8` forms (DWARF v4).
 *
 * A location list describes where a variable is stored across different
 * ranges of the program counter. Each entry maps an address range
 * `[start, end)` to a [DwarfLocationDescription].
 */
class DwarfLocSection(
    contentOffset: Int,
    contentSize: Int,
    accessor: DwarfAccessor,
) : DwarfSection(contentOffset, contentSize, accessor)

/**
 * A single entry in a DWARF location list: the variable is described
 * by [location] when the program counter is within `[startAddress, endAddress)`.
 */
data class DwarfLocationListEntry(
    val startAddress: Long,
    val endAddress: Long,
    val location: DwarfLocationDescription,
)

/**
 * Reads a DWARF v4 location list starting at [locListOffset] within
 * the `.debug_loc` section.
 *
 * The DWARF v4 location list format consists of a series of entries:
 * - Two addresses (`address_size` bytes each): start and end of the range
 * - A 2-byte length followed by a DWARF expression of that length
 *
 * The list is terminated by a pair of zero addresses `(0, 0)`.
 * A base-address-selection entry has `start == 0xFFFFFFFF` (for 32-bit).
 *
 * @param locListOffset offset from the start of `.debug_loc` section content
 * @param addressSize the target address size in bytes (typically 4 for wasm32)
 * @return a list of [DwarfLocationListEntry], or an empty list on error
 */
fun CommonByteBuffer.readLocationList(
    locSection: DwarfLocSection,
    locListOffset: Int,
    addressSize: Int,
): List<DwarfLocationListEntry> {
    val entries = mutableListOf<DwarfLocationListEntry>()
    val sectionStart = locSection.contentOffset
    val sectionEnd = sectionStart + locSection.contentSize

    val seekTarget = sectionStart + locListOffset
    if (seekTarget < sectionStart || seekTarget >= sectionEnd) return entries
    position(seekTarget)

    var baseAddress = 0L
    val maxAddress = if (addressSize == 4) 0xFFFFFFFFL else -1L // all-ones sentinel

    while (position() < sectionEnd) {
        // Need at least 2 × addressSize bytes for the address pair
        if (position() + 2 * addressSize > sectionEnd) break
        val start = when (addressSize) {
            4 -> getIntLE().toLong() and 0xFFFFFFFFL
            8 -> getLongLE()
            else -> return entries
        }
        val end = when (addressSize) {
            4 -> getIntLE().toLong() and 0xFFFFFFFFL
            8 -> getLongLE()
            else -> return entries
        }

        // End-of-list marker
        if (start == 0L && end == 0L) break

        // Base address selection entry
        if (start == maxAddress) {
            baseAddress = end
            continue
        }

        // Normal entry: read the location expression
        if (position() + 2 > sectionEnd) break
        val exprLength = getShortLE().toUShort().toInt()
        if (exprLength == 0) continue
        if (exprLength > sectionEnd - position()) return entries

        val exprBytes = ByteArray(exprLength)
        get(exprBytes, 0, exprLength)

        val locationDesc = DwarfExpressionParser.parse(exprBytes, addressSize)
        entries.add(
            DwarfLocationListEntry(
                startAddress = baseAddress + start,
                endAddress = baseAddress + end,
                location = locationDesc,
            )
        )
    }
    return entries
}
