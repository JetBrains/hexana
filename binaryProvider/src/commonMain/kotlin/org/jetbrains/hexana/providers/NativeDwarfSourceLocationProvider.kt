package org.jetbrains.hexana.providers

import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver
import org.jetbrains.hexana.io.CommonByteBuffer

class NativeDwarfSourceLocationProvider(
    private val buffer: CommonByteBuffer,
    dwarfAccessor: DwarfAccessor,
) {
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

    val summary: NativeDebugInfoSummary by lazy {
        val rows = bestLocations
        val files = rows.map { it.sourceLocation.file }.distinct().sorted()
        NativeDebugInfoSummary(
            lineRowCount = rows.size,
            fileCount = files.size,
            firstAddress = rows.firstOrNull()?.offset,
            lastAddress = rows.lastOrNull()?.offset,
            sampleFiles = files.take(50),
        )
    }

    fun getSourceLocationForVirtualAddress(virtualAddress: ULong): HexanaSourceLocation? {
        val loc = bestLocations.findLast { it.offset <= virtualAddress }?.sourceLocation ?: return null

        return HexanaSourceLocation(
            filePath = loc.file,
            line = loc.line,
            column = loc.column,
        )
    }

    fun getVirtualAddressesForSourceLocation(filePath: String, line: Int): List<ULong> {
        if (line <= 0) return emptyList()
        val normalizedFilePath = filePath.normalizedSourcePath()
        return bestLocations
            .asSequence()
            .filter { row ->
                val location = row.sourceLocation
                location.line == line && location.file.normalizedSourcePath().matchesSourcePath(normalizedFilePath)
            }
            .map { it.offset }
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
