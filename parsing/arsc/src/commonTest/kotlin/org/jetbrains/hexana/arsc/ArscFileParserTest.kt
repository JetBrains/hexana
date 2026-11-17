package org.jetbrains.hexana.arsc

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArscFileParserTest {

    @Test
    fun detectsResourceTable() {
        assertTrue(ArscFileParser.isArsc(ArscTestBytes.sampleTable().toCommonByteBuffer()))
    }

    @Test
    fun rejectsNonResourceTables() {
        // Empty, too short to hold a chunk header.
        assertFalse(ArscFileParser.isArsc(ByteArray(4).toCommonByteBuffer()))
        // Right chunk type and a plausible size, but no room for a child chunk.
        val oversized = byteArrayOf(0x02, 0x00, 0x0C, 0x00, 0x00, 0x10, 0x00, 0x00, 0x01, 0, 0, 0)
        assertFalse(ArscFileParser.isArsc(oversized.toCommonByteBuffer()))
        // DEX magic.
        val dex = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x39, 0x00, 0, 0, 0, 0)
        assertFalse(ArscFileParser.isArsc(dex.toCommonByteBuffer()))
        // Consistent table header, but no string pool chunk behind it.
        val poolless = byteArrayOf(0x02, 0x00, 0x0C, 0x00, 0x14, 0x00, 0x00, 0x00, 0x01, 0, 0, 0) +
            byteArrayOf(0x02, 0x02, 0x10, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertFalse(ArscFileParser.isArsc(poolless.toCommonByteBuffer()))
    }

    @Test
    fun detectsTruncatedResourceTable() {
        // The declared sizes still describe the whole table, so detection has to
        // accept it for `parse` to list what survived - see
        // `parsesTruncatedTablePartially`.
        val truncated = ArscTestBytes.sampleTable().let { it.copyOf(it.size - 120) }
        assertTrue(ArscFileParser.isArsc(truncated.toCommonByteBuffer()))
    }

    @Test
    fun parsesTableHeaderAndValueStrings() {
        val table = parseSample()
        assertEquals(1, table.packageCount)
        assertEquals(ArscTestBytes.VALUE_STRINGS, table.valueStrings.strings)
        assertFalse(table.valueStrings.isUtf8)
    }

    @Test
    fun parsesUtf8ValueStrings() {
        val table = parseSample(utf8Strings = true)
        assertTrue(table.valueStrings.isUtf8)
        assertEquals(ArscTestBytes.VALUE_STRINGS, table.valueStrings.strings)
        assertEquals(ArscTestBytes.KEY_NAMES, table.packages.single().keyStrings.strings)
    }

    @Test
    fun parsesPackageMetadata() {
        val pkg = parseSample().packages.single()
        assertEquals(ArscTestBytes.PACKAGE_ID, pkg.id)
        assertEquals(ArscTestBytes.PACKAGE_NAME, pkg.name)
        assertEquals(ArscTestBytes.TYPE_NAMES, pkg.typeStrings.strings)
        assertEquals(ArscTestBytes.KEY_NAMES, pkg.keyStrings.strings)
    }

    @Test
    fun parsesTypeSpecs() {
        val spec = parseSample().packages.single().typeSpecs.single()
        assertEquals(ArscTestBytes.TYPE_STRING_ID, spec.typeId)
        assertEquals("string", spec.typeName)
        assertEquals(2, spec.entryCount)
    }

    @Test
    fun parsesEntriesOfDefaultConfiguration() {
        val table = parseSample()
        val strings = table.packages.single().types.first { it.typeName == "string" }
        assertEquals("default", strings.configuration.qualifier)
        assertEquals(listOf("app_name", "greeting"), strings.entries.map { it.key })
        assertEquals(listOf(0, 1), strings.entries.map { it.entryId })
        assertEquals(
            listOf("Hello world", "Hi there"),
            strings.entries.map { table.formatEntryValue(it) },
        )
    }

    @Test
    fun skipsUndefinedEntriesOfAlternateConfiguration() {
        val table = parseSample()
        val french = table.packages.single().types
            .first { it.typeName == "string" && it.configuration.qualifier == "fr" }
        assertEquals("fr", french.configuration.language)
        // The type declares both entries, but only the second one is defined.
        assertEquals(2, french.entryCount)
        val entry = french.entries.single()
        assertEquals(1, entry.entryId)
        assertEquals("greeting", entry.key)
        assertEquals("Bonjour", table.formatEntryValue(entry))
    }

    @Test
    fun formatsScalarValueTypes() {
        val table = parseSample()
        assertEquals("16dp", valueOf(table, "dimen"))
        assertEquals("true", valueOf(table, "bool"))
        assertEquals("#ff3ddc84", valueOf(table, "color"))
    }

    @Test
    fun parsesSparseIndexAndCompactEntries() {
        val table = parseSample()
        val sparse = table.packages.single().types
            .first { it.typeName == "bool" && it.configuration.qualifier == "fil" }
        // A packed three-letter language code round-trips.
        assertEquals("fil", sparse.configuration.language)
        val entry = sparse.entries.single()
        assertTrue(entry.isCompact)
        assertEquals(2, entry.entryId)
        assertEquals("enabled", entry.key)
        assertEquals("false", table.formatEntryValue(entry))
    }

    @Test
    fun parsesSixteenBitEntryOffsets() {
        val table = parseSample()
        val dimens = table.packages.single().types
            .first { it.typeName == "dimen" && it.configuration.qualifier == "hdpi" }
        val entry = dimens.entries.single()
        assertEquals(1, entry.entryId)
        assertEquals("margin", entry.key)
        assertEquals("50%", table.formatEntryValue(entry))
    }

    @Test
    fun parsesComplexEntry() {
        val table = parseSample()
        val style = table.packages.single().types.first { it.typeName == "style" }
        val entry = style.entries.single()
        assertTrue(entry.isComplex)
        assertNull(entry.value)
        assertEquals("AppTheme", entry.key)
        assertEquals(ArscTestBytes.FRAMEWORK_PARENT, entry.parentRef)
        assertEquals(2, entry.mapValues.size)
        assertEquals(
            "<bag of 2 item(s), parent @0x01030005>",
            table.formatEntryValue(entry),
        )
        assertEquals(
            listOf("@com.example.app:color/accent", "42"),
            entry.mapValues.map { table.formatValue(it.value) },
        )
    }

    @Test
    fun resolvesResourceNames() {
        val table = parseSample()
        assertEquals(
            "com.example.app:string/greeting",
            table.resourceName(resourceId(ArscTestBytes.PACKAGE_ID, ArscTestBytes.TYPE_STRING_ID, 1)),
        )
        // Framework IDs are defined in another table, so they stay unresolved.
        assertNull(table.resourceName(ArscTestBytes.FRAMEWORK_PARENT))
        assertEquals("@0x01030005", table.formatResourceReference(ArscTestBytes.FRAMEWORK_PARENT))
    }

    @Test
    fun flattensResources() {
        val table = parseSample()
        val resources = table.resources()
        assertEquals(table.resourceCount, resources.size)
        assertEquals(9, resources.size)

        val appName = resources.first()
        assertEquals(0x7f020000, appName.id)
        assertEquals("com.example.app", appName.packageName)
        assertEquals("string", appName.typeName)
        assertEquals("app_name", appName.name)
        assertEquals("default", appName.configuration.qualifier)
        assertEquals("Hello world", table.formatEntryValue(appName.entry))

        val french = resources.first { it.configuration.qualifier == "fr" }
        assertEquals(0x7f020001, french.id)
        assertEquals("Bonjour", table.formatEntryValue(french.entry))
    }

    @Test
    fun rendersConfigurationQualifiers() {
        assertEquals("default", ArscConfiguration.DEFAULT.qualifier)
        val config = ArscConfiguration.DEFAULT.copy(
            language = "en",
            country = "US",
            orientation = ArscConfiguration.ORIENTATION_LAND,
            density = 240,
            sdkVersion = 21,
        )
        assertEquals("en-rUS-land-hdpi-v21", config.qualifier)
        assertEquals("anydpi", ArscConfiguration.DEFAULT.copy(density = ArscConfiguration.DENSITY_ANY).qualifier)
        assertEquals("tvdpi", ArscConfiguration.DEFAULT.copy(density = 213).qualifier)
        assertEquals("300dpi", ArscConfiguration.DEFAULT.copy(density = 300).qualifier)
    }

    @Test
    fun parsesTruncatedTablePartially() {
        val full = ArscTestBytes.sampleTable()
        val fullTypeCount = ArscFileParser.parse(full.toCommonByteBuffer()).packages.single().types.size
        // Cut the tail off without touching the declared chunk sizes, which is
        // what a partially written or repacked file looks like.
        val truncated = full.copyOf(full.size - 120)
        val table = ArscFileParser.parse(truncated.toCommonByteBuffer())

        assertEquals(1, table.packageCount)
        assertEquals(ArscTestBytes.VALUE_STRINGS, table.valueStrings.strings)
        val types = table.packages.single().types
        assertTrue(types.isNotEmpty(), "expected the leading type chunks to survive truncation")
        assertTrue(
            types.size < fullTypeCount,
            "expected the trailing type chunks to be lost, got ${types.size} of $fullTypeCount",
        )
        assertEquals("Hello world", table.formatEntryValue(types.first().entries.first()))
    }

    @Test
    fun resolvesTypeNamesThroughTypeIdOffset() {
        val offset = 0x20
        val pkg = ArscFileParser.parse(
            ArscTestBytes.sampleTable(typeIdOffset = offset).toCommonByteBuffer()
        ).packages.single()

        assertEquals(offset, pkg.typeIdOffset)
        assertEquals(ArscTestBytes.TYPE_STRING_ID + offset, pkg.typeSpecs.single().typeId)
        assertEquals("string", pkg.typeSpecs.single().typeName)
        assertEquals(
            listOf("string", "string", "dimen", "bool", "color", "bool", "dimen", "style"),
            pkg.types.map { it.typeName },
        )
    }

    @Test
    fun doesNotReadEntryValuesPastTheTypeChunk() {
        val table = ArscFileParser.parse(
            ArscTestBytes.tableWithEntryCutOffBeforeValue().toCommonByteBuffer()
        )
        val cutOff = table.packages.single().types.first { it.typeName == "bool" }.entries.single()

        assertEquals("enabled", cutOff.key)
        assertNull(cutOff.value, "the value bytes belong to the next chunk, not to this entry")
        // The chunk that follows must still parse normally.
        assertEquals(
            "#ff3ddc84",
            table.formatEntryValue(
                table.packages.single().types.first { it.typeName == "color" }.entries.single()
            ),
        )
    }

    @Test
    fun reportsTopLevelChunkRanges() {
        val bytes = ArscTestBytes.sampleTable()
        val ranges = ArscFileParser.chunkRanges(bytes.toCommonByteBuffer())

        assertEquals(
            listOf(ArscChunkKind.VALUE_STRINGS, ArscChunkKind.PACKAGE),
            ranges.map { it.kind },
        )
        assertEquals(
            listOf("Value strings", "package ${ArscTestBytes.PACKAGE_NAME}"),
            ranges.map { it.label },
        )
        // The children start right after the table header and tile the rest of the
        // file, so their ranges have to be adjacent and reach the end.
        val pool = ranges.first()
        val pkg = ranges.last()
        assertEquals(pool.offset + pool.size, pkg.offset)
        assertEquals(bytes.size, pkg.offset + pkg.size)
        // The pool range must cover the pool `parse` reads out of the same bytes.
        val valueStrings = ArscFileParser.parse(bytes.toCommonByteBuffer()).valueStrings
        assertEquals(ArscTestBytes.VALUE_STRINGS, valueStrings.strings)
    }

    @Test
    fun clampsChunkRangesOfTruncatedTable() {
        val full = ArscTestBytes.sampleTable()
        val truncated = full.copyOf(full.size - 120)
        val ranges = ArscFileParser.chunkRanges(truncated.toCommonByteBuffer())

        assertEquals(2, ranges.size)
        // The package chunk still declares its original size; the walk clamps it to
        // the bytes present rather than pointing past the end of the file.
        assertTrue(ranges.all { it.offset + it.size <= truncated.size })
        assertEquals(truncated.size, ranges.last().let { it.offset + it.size })
    }

    @Test
    fun reportsNoChunkRangesForNonTable() {
        val pool = ArscTestBytes.stringPool(listOf("a", "b"))
        assertEquals(emptyList(), ArscFileParser.chunkRanges(pool.toCommonByteBuffer()))
        assertEquals(emptyList(), ArscFileParser.chunkRanges(ByteArray(4).toCommonByteBuffer()))
    }

    @Test
    fun failsOnNonTableRootChunk() {
        val pool = ArscTestBytes.stringPool(listOf("a", "b"))
        assertFailsWith<IllegalArgumentException> {
            ArscFileParser.parse(pool.toCommonByteBuffer())
        }
    }

    private fun parseSample(utf8Strings: Boolean = false): ArscFile =
        ArscFileParser.parse(ArscTestBytes.sampleTable(utf8Strings).toCommonByteBuffer())

    private fun valueOf(table: ArscFile, typeName: String): String {
        val type = table.packages.single().types.first { it.typeName == typeName }
        return table.formatEntryValue(type.entries.single())
    }
}
