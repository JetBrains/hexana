package org.jetbrains.hexana.arsc

/**
 * Parsed Android binary resource table (`resources.arsc`). See
 * [ResourceTypes.h](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h).
 *
 * The file is a tree of type-length chunks: one `RES_TABLE_TYPE` root holding
 * the global value string pool plus one `RES_TABLE_PACKAGE_TYPE` chunk per
 * package. Only the chunks needed to list resources are modelled here; unknown
 * chunk types are skipped by the parser rather than represented.
 */
data class ArscFile(
    /** `packageCount` as declared in the table header. */
    val packageCount: Int,
    /** Global value string pool — the target of every `TYPE_STRING` value. */
    val valueStrings: ArscStringPool,
    val packages: List<ArscPackage>,
) {
    /** Number of defined resource entries across all packages and configurations. */
    val resourceCount: Int
        get() = packages.sumOf { pkg -> pkg.types.sumOf { it.entries.size } }

    /**
     * Flattens the table into one row per defined entry, in file order.
     *
     * The same resource ID appears once per configuration that defines it, which
     * is how the table itself is laid out (one `RES_TABLE_TYPE` chunk per
     * configuration).
     */
    fun resources(): List<ArscResource> = packages.flatMap { pkg ->
        pkg.types.flatMap { type ->
            type.entries.map { entry ->
                ArscResource(
                    id = resourceId(pkg.id, type.typeId, entry.entryId),
                    packageName = pkg.name,
                    typeName = type.typeName,
                    configuration = type.configuration,
                    entry = entry,
                )
            }
        }
    }

    /** Renders [entry]'s value for display: a scalar value, or a bag summary for complex entries. */
    fun formatEntryValue(entry: ArscEntry): String = when {
        entry.isComplex -> buildString {
            append("<bag of ").append(entry.mapValues.size).append(" item(s)")
            if (entry.parentRef != 0) append(", parent ").append(formatResourceReference(entry.parentRef))
            append('>')
        }
        entry.value != null -> formatValue(entry.value)
        else -> ""
    }

    /**
     * Renders a `Res_value` the way `aapt2 dump` does for the common data types.
     * Unknown data types fall back to the raw hex payload.
     */
    fun formatValue(value: ArscValue): String = when (value.dataType) {
        ArscValue.TYPE_NULL -> if (value.data == ArscValue.DATA_NULL_EMPTY) "@empty" else "@null"
        ArscValue.TYPE_REFERENCE, ArscValue.TYPE_DYNAMIC_REFERENCE -> formatResourceReference(value.data)
        ArscValue.TYPE_ATTRIBUTE, ArscValue.TYPE_DYNAMIC_ATTRIBUTE -> "?" + hexResourceId(value.data)
        ArscValue.TYPE_STRING -> valueStrings[value.data] ?: "<string@${value.data}>"
        ArscValue.TYPE_FLOAT -> Float.fromBits(value.data).toString()
        ArscValue.TYPE_DIMENSION -> formatComplexUnit(value.data, DIMENSION_UNITS)
        // Fractions are stored as 0..1, but `%` reads as a percentage.
        ArscValue.TYPE_FRACTION -> formatComplexUnit(value.data, FRACTION_UNITS, scale = 100f)
        ArscValue.TYPE_INT_DEC -> value.data.toString()
        ArscValue.TYPE_INT_HEX -> "0x" + value.data.toUInt().toString(16)
        ArscValue.TYPE_INT_BOOLEAN -> if (value.data != 0) "true" else "false"
        ArscValue.TYPE_INT_COLOR_ARGB8 -> formatColor(value.data, digits = 8)
        ArscValue.TYPE_INT_COLOR_RGB8 -> formatColor(value.data and 0xFFFFFF, digits = 6)
        ArscValue.TYPE_INT_COLOR_ARGB4 -> formatColor(value.data, digits = 4)
        ArscValue.TYPE_INT_COLOR_RGB4 -> formatColor(value.data and 0xFFF, digits = 3)
        else -> "0x" + value.data.toUInt().toString(16) + " (type 0x" + value.dataType.toString(16) + ")"
    }

    /** Renders a resource reference as `@package:type/name` when resolvable, else `@0x7f010000`. */
    fun formatResourceReference(id: Int): String {
        val name = resourceName(id) ?: return "@" + hexResourceId(id)
        return "@$name"
    }

    /**
     * Resolves `package:type/name` for a resource ID defined in this table.
     * Returns `null` for IDs from other packages (e.g. framework `0x01…` refs).
     */
    fun resourceName(id: Int): String? {
        val pkg = packages.firstOrNull { it.id == (id ushr 24) and 0xFF } ?: return null
        val typeId = (id ushr 16) and 0xFF
        val entryId = id and 0xFFFF
        val chunks = pkg.types.filter { it.typeId == typeId }
        val entry = chunks.firstNotNullOfOrNull { chunk -> chunk.entries.firstOrNull { it.entryId == entryId } }
            ?: return null
        return "${pkg.name}:${chunks.first().typeName}/${entry.key}"
    }
}

/** Composes a resource ID from its package, type and entry parts: `0xPPTTEEEE`. */
fun resourceId(packageId: Int, typeId: Int, entryId: Int): Int =
    ((packageId and 0xFF) shl 24) or ((typeId and 0xFF) shl 16) or (entryId and 0xFFFF)

/** Renders a resource ID in the conventional `0x7f010000` form. */
fun hexResourceId(id: Int): String = "0x" + id.toUInt().toString(16).padStart(8, '0')

/**
 * One defined resource entry, flattened out of the chunk tree by
 * [ArscFile.resources]. The [entry] is kept so callers can reach the bag items
 * of a complex resource; [ArscFile.formatEntryValue] renders it for display.
 */
data class ArscResource(
    /** Full resource ID, `0xPPTTEEEE`. */
    val id: Int,
    val packageName: String,
    val typeName: String,
    val configuration: ArscConfiguration,
    val entry: ArscEntry,
) {
    val name: String get() = entry.key
}

/**
 * `ResStringPool`: the decoded strings of one pool. Styles are parsed over but
 * not modelled — they carry span markup that basic resource listing never uses.
 */
data class ArscStringPool(
    val flags: Int,
    val strings: List<String>,
) {
    val isUtf8: Boolean get() = flags and FLAG_UTF8 != 0
    val isSorted: Boolean get() = flags and FLAG_SORTED != 0

    /** Pool lookup that tolerates out-of-range indices from damaged files. */
    operator fun get(index: Int): String? = strings.getOrNull(index)

    companion object {
        const val FLAG_SORTED: Int = 1 shl 0
        const val FLAG_UTF8: Int = 1 shl 8

        val EMPTY: ArscStringPool = ArscStringPool(flags = 0, strings = emptyList())
    }
}

/** `ResTable_package`: one package's type/key pools and its resource type chunks. */
data class ArscPackage(
    /** Package ID — the high byte of every resource ID in this package (`0x7f` for an app). */
    val id: Int,
    val name: String,
    /** Type names, indexed by `typeId - 1`. */
    val typeStrings: ArscStringPool,
    /** Entry names, indexed by `ArscEntry.keyIndex`. */
    val keyStrings: ArscStringPool,
    /** One element per `RES_TABLE_TYPE` chunk, i.e. per (type, configuration) pair. */
    val types: List<ArscTypeChunk>,
    /** One element per `RES_TABLE_TYPE_SPEC` chunk, i.e. per resource type. */
    val typeSpecs: List<ArscTypeSpec>,
)

/** `ResTable_typeSpec`: declares how many entries a resource type has. */
data class ArscTypeSpec(
    val typeId: Int,
    val typeName: String,
    val entryCount: Int,
)

/** `ResTable_type`: the entries of one resource type for one configuration. */
data class ArscTypeChunk(
    val typeId: Int,
    val typeName: String,
    val configuration: ArscConfiguration,
    /** Declared entry count, including the holes that [entries] omits. */
    val entryCount: Int,
    /** Only the entries that this configuration actually defines. */
    val entries: List<ArscEntry>,
)

/** `ResTable_entry`, either a scalar [value] or a complex bag of [mapValues]. */
data class ArscEntry(
    /** Index within the type, i.e. the low 16 bits of the resource ID. */
    val entryId: Int,
    val keyIndex: Int,
    /** Entry name resolved through [ArscPackage.keyStrings]. */
    val key: String,
    val flags: Int,
    /** Scalar value, or `null` for complex entries. */
    val value: ArscValue?,
    /** Complex entries only: resource ID of the inherited parent, or 0. */
    val parentRef: Int = 0,
    /** Complex entries only: the bag items. */
    val mapValues: List<ArscMapValue> = emptyList(),
) {
    val isComplex: Boolean get() = flags and FLAG_COMPLEX != 0
    val isPublic: Boolean get() = flags and FLAG_PUBLIC != 0
    val isWeak: Boolean get() = flags and FLAG_WEAK != 0
    val isCompact: Boolean get() = flags and FLAG_COMPACT != 0

    companion object {
        const val FLAG_COMPLEX: Int = 0x0001
        const val FLAG_PUBLIC: Int = 0x0002
        const val FLAG_WEAK: Int = 0x0004

        /** Android 14+ 8-byte entry form: the key is inlined and the value type is packed into the flags. */
        const val FLAG_COMPACT: Int = 0x0008
    }
}

/** `ResTable_map`: one item of a complex entry (a style attribute, an array element, …). */
data class ArscMapValue(
    /** Attribute resource ID, or an `ATTR_*`/array pseudo-attribute. */
    val nameRef: Int,
    val value: ArscValue,
)

/** `Res_value`: a typed 32-bit payload. */
data class ArscValue(
    val dataType: Int,
    val data: Int,
) {
    companion object {
        const val TYPE_NULL: Int = 0x00
        const val TYPE_REFERENCE: Int = 0x01
        const val TYPE_ATTRIBUTE: Int = 0x02
        const val TYPE_STRING: Int = 0x03
        const val TYPE_FLOAT: Int = 0x04
        const val TYPE_DIMENSION: Int = 0x05
        const val TYPE_FRACTION: Int = 0x06
        const val TYPE_DYNAMIC_REFERENCE: Int = 0x07
        const val TYPE_DYNAMIC_ATTRIBUTE: Int = 0x08
        const val TYPE_INT_DEC: Int = 0x10
        const val TYPE_INT_HEX: Int = 0x11
        const val TYPE_INT_BOOLEAN: Int = 0x12
        const val TYPE_INT_COLOR_ARGB8: Int = 0x1c
        const val TYPE_INT_COLOR_RGB8: Int = 0x1d
        const val TYPE_INT_COLOR_ARGB4: Int = 0x1e
        const val TYPE_INT_COLOR_RGB4: Int = 0x1f

        /** `TYPE_NULL` data value marking `@empty` rather than an undefined value. */
        const val DATA_NULL_EMPTY: Int = 1
    }
}

/**
 * The subset of `ResTable_config` that basic resource listing needs.
 *
 * The full struct has grown a new trailing block in almost every Android
 * release; [size] records how long the on-disk struct actually was, and
 * [qualifier] renders only the axes parsed here.
 */
data class ArscConfiguration(
    val size: Int,
    val mcc: Int,
    val mnc: Int,
    /** ISO 639 language code, or `""` for any. */
    val language: String,
    /** ISO 3166 region code, or `""` for any. */
    val country: String,
    val orientation: Int,
    /** Density in dpi, or one of [DENSITY_ANY]/[DENSITY_NONE]. */
    val density: Int,
    val sdkVersion: Int,
) {
    /**
     * Directory-style qualifier string, e.g. `en-rUS-land-hdpi-v21`, or
     * `default` when none of the parsed axes is set.
     */
    val qualifier: String
        get() {
            val parts = buildList {
                if (mcc != 0) add("mcc$mcc")
                if (mnc != 0) add("mnc$mnc")
                if (language.isNotEmpty()) add(language)
                if (country.isNotEmpty()) add("r$country")
                when (orientation) {
                    ORIENTATION_PORT -> add("port")
                    ORIENTATION_LAND -> add("land")
                    ORIENTATION_SQUARE -> add("square")
                }
                densityQualifier(density)?.let(::add)
                if (sdkVersion != 0) add("v$sdkVersion")
            }
            return if (parts.isEmpty()) "default" else parts.joinToString("-")
        }

    companion object {
        const val ORIENTATION_PORT: Int = 0x01
        const val ORIENTATION_LAND: Int = 0x02
        const val ORIENTATION_SQUARE: Int = 0x03

        const val DENSITY_ANY: Int = 0xFFFE
        const val DENSITY_NONE: Int = 0xFFFF

        val DEFAULT: ArscConfiguration = ArscConfiguration(
            size = 0,
            mcc = 0,
            mnc = 0,
            language = "",
            country = "",
            orientation = 0,
            density = 0,
            sdkVersion = 0,
        )
    }
}

private fun densityQualifier(density: Int): String? = when (density) {
    0 -> null
    120 -> "ldpi"
    160 -> "mdpi"
    213 -> "tvdpi"
    240 -> "hdpi"
    320 -> "xhdpi"
    480 -> "xxhdpi"
    640 -> "xxxhdpi"
    ArscConfiguration.DENSITY_ANY -> "anydpi"
    ArscConfiguration.DENSITY_NONE -> "nodpi"
    else -> "${density}dpi"
}

/** `COMPLEX_UNIT_*` suffixes for `TYPE_DIMENSION`. */
private val DIMENSION_UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")

/** `COMPLEX_UNIT_*` suffixes for `TYPE_FRACTION`. */
private val FRACTION_UNITS = arrayOf("%", "%p")

/**
 * Renders a `TYPE_DIMENSION`/`TYPE_FRACTION` payload: a 24-bit mantissa scaled
 * by a 2-bit radix, plus a unit suffix.
 */
private fun formatComplexUnit(data: Int, units: Array<String>, scale: Float = 1f): String {
    val mantissa = data shr 8
    val radix = (data shr 4) and 0x3
    val unit = units.getOrNull(data and 0xF) ?: "?"
    val shift = when (radix) {
        0 -> 0
        1 -> 7
        2 -> 15
        else -> 23
    }
    val scaled = scale * mantissa.toFloat() / (1 shl shift)
    val rendered = if (scaled == scaled.toInt().toFloat()) scaled.toInt().toString() else scaled.toString()
    return rendered + unit
}

private fun formatColor(data: Int, digits: Int): String =
    "#" + data.toUInt().toString(16).padStart(digits, '0')
