package org.jetbrains.hexana.providers

import org.jetbrains.hexana.dwarf.DwarfAccessor
import org.jetbrains.hexana.dwarf.DwarfSourceLocationResolver
import org.jetbrains.hexana.io.CommonByteBuffer

class DwarfSourceLocationProvider(private val buffer: CommonByteBuffer, private val dwarfAccessor: DwarfAccessor) : HexanaSourceLocationProvider {
    private val dwarfSourceLocationResolver = DwarfSourceLocationResolver(dwarfAccessor)

    override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? {
        val loc = with(dwarfSourceLocationResolver) {
            buffer.resolveLocation(byteOffset)
        }?: return null

        return HexanaSourceLocation(
            filePath = loc.file,
            line = loc.line,
            column = loc.column,
        )
    }
}