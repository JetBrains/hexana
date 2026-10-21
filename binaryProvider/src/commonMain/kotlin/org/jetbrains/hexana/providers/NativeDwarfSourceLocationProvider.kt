package org.jetbrains.hexana.providers

import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver
import org.jetbrains.hexana.io.CommonByteBuffer

class NativeDwarfSourceLocationProvider(
    private val buffer: CommonByteBuffer,
    dwarfAccessor: DwarfAccessor,
) : NativeSourceLocationProvider {
    private val dwarfSourceLocationResolver = DwarfSourceLocationResolver(dwarfAccessor)
    private val locations by lazy {
        runCatching {
            with(dwarfSourceLocationResolver) {
                buffer.resolveLocations()
            }
        }.getOrElse { emptyList() }
    }
    private val lineTableLocations by lazy {
        runCatching {
            with(dwarfSourceLocationResolver) {
                buffer.resolveLineTableLocations()
            }
        }.getOrElse { emptyList() }
    }
    private val bestLocations: List<DwarfSourceLocationResolver.AddressSourceLocation> by lazy {
        (locations + lineTableLocations)
            .distinctBy { row ->
                val location = row.sourceLocation
                listOf(row.offset, location.file, location.line, location.column)
            }
            .sortedBy { it.offset }
    }

    private val table: AddressLineTable by lazy {
        AddressLineTable(bestLocations.map { row ->
            AddressLineRow(row.offset, row.sourceLocation.file, row.sourceLocation.line, row.sourceLocation.column)
        })
    }

    override val summary: NativeDebugInfoSummary get() = table.summary

    override fun getSourceLocationForVirtualAddress(virtualAddress: ULong): HexanaSourceLocation? =
        table.getSourceLocationForVirtualAddress(virtualAddress)

    override fun getVirtualAddressesForSourceLocation(filePath: String, line: Int): List<ULong> =
        table.getVirtualAddressesForSourceLocation(filePath, line)
}

/**
 * Source lines of native code by virtual address, whatever the debug format: DWARF line programs
 * for ELF and Mach-O, PDB C13 line records for PE. Every backend reports 1-based lines and columns
 * (0 for an unknown column), like [HexanaSourceLocation].
 */
interface NativeSourceLocationProvider {
    val summary: NativeDebugInfoSummary

    /** The row covering [virtualAddress]: the last one at or below it. */
    fun getSourceLocationForVirtualAddress(virtualAddress: ULong): HexanaSourceLocation?

    /** Addresses of every row for [line] of [filePath]; the path matches by suffix. */
    fun getVirtualAddressesForSourceLocation(filePath: String, line: Int): List<ULong>
}

data class AddressLineRow(val address: ULong, val file: String, val line: Int, val column: Int)

/** A line table sorted by address, looked up by binary search. */
class AddressLineTable(rows: List<AddressLineRow>) : NativeSourceLocationProvider {
    private val rows = rows.sortedBy { it.address }

    override val summary: NativeDebugInfoSummary by lazy {
        val files = rows.map { it.file }.distinct().sorted()
        NativeDebugInfoSummary(
            lineRowCount = rows.size,
            fileCount = files.size,
            firstAddress = rows.firstOrNull()?.address,
            lastAddress = rows.lastOrNull()?.address,
            sampleFiles = files.take(50),
        )
    }

    override fun getSourceLocationForVirtualAddress(virtualAddress: ULong): HexanaSourceLocation? {
        // Last row whose address is <= virtualAddress.
        var low = 0
        var high = rows.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (rows[middle].address <= virtualAddress) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        val row = rows.getOrNull(found) ?: return null
        return HexanaSourceLocation(filePath = row.file, line = row.line, column = row.column)
    }

    override fun getVirtualAddressesForSourceLocation(filePath: String, line: Int): List<ULong> {
        if (line <= 0) return emptyList()
        val normalizedFilePath = filePath.normalizedSourcePath()
        return rows
            .asSequence()
            .filter { row -> row.line == line && row.file.normalizedSourcePath().matchesSourcePath(normalizedFilePath) }
            .map { it.address }
            .distinct()
            .toList()
    }
}

data class NativeDebugInfoSummary(
    val lineRowCount: Int,
    val fileCount: Int,
    val firstAddress: ULong?,
    val lastAddress: ULong?,
    val sampleFiles: List<String>,
)

private fun String.normalizedSourcePath(): String =
    replace('\\', '/').trimEnd('/')

private fun String.matchesSourcePath(query: String): Boolean =
    this == query || endsWith("/$query") || query.endsWith("/$this")
