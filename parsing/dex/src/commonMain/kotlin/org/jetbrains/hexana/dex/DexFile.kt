package org.jetbrains.hexana.dex

/**
 * Parsed Android DEX file. See
 * [DEX format](https://source.android.com/docs/core/runtime/dex-format).
 */
data class DexFile(
    val header: DexHeader,
    val stringIds: List<DexStringId>,
    val typeIds: List<DexTypeId>,
    val protoIds: List<DexProtoId>,
    val fieldIds: List<DexFieldId>,
    val methodIds: List<DexMethodId>,
    val classDefs: List<DexClassDef>,
) {
    /**
     * Resolves a string by its index in the string IDs table.
     * Returns `null` if the index is out of range.
     */
    fun string(index: Int): String? =
        stringIds.getOrNull(index)?.value

    /**
     * Resolves a type descriptor by its index in the type IDs table.
     * Returns `null` if the index is out of range.
     */
    fun typeName(index: Int): String? {
        val typeId = typeIds.getOrNull(index) ?: return null
        return string(typeId.descriptorIdx)
    }
}

data class DexHeader(
    val magic: ByteArray,
    val checksum: Long,
    val signature: ByteArray,
    val fileSize: Long,
    val headerSize: Long,
    val endianTag: Long,
    val linkSize: Long,
    val linkOff: Long,
    val mapOff: Long,
    val stringIdsSize: Int,
    val stringIdsOff: Long,
    val typeIdsSize: Int,
    val typeIdsOff: Long,
    val protoIdsSize: Int,
    val protoIdsOff: Long,
    val fieldIdsSize: Int,
    val fieldIdsOff: Long,
    val methodIdsSize: Int,
    val methodIdsOff: Long,
    val classDefsSize: Int,
    val classDefsOff: Long,
    val dataSize: Long,
    val dataOff: Long,
) {
    /** DEX version string extracted from the magic bytes, e.g. `"039"`. */
    val version: String
        get() = String(magic, 4, 3)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DexHeader) return false
        return magic.contentEquals(other.magic) &&
            checksum == other.checksum &&
            signature.contentEquals(other.signature) &&
            fileSize == other.fileSize &&
            headerSize == other.headerSize &&
            endianTag == other.endianTag &&
            linkSize == other.linkSize &&
            linkOff == other.linkOff &&
            mapOff == other.mapOff &&
            stringIdsSize == other.stringIdsSize &&
            stringIdsOff == other.stringIdsOff &&
            typeIdsSize == other.typeIdsSize &&
            typeIdsOff == other.typeIdsOff &&
            protoIdsSize == other.protoIdsSize &&
            protoIdsOff == other.protoIdsOff &&
            fieldIdsSize == other.fieldIdsSize &&
            fieldIdsOff == other.fieldIdsOff &&
            methodIdsSize == other.methodIdsSize &&
            methodIdsOff == other.methodIdsOff &&
            classDefsSize == other.classDefsSize &&
            classDefsOff == other.classDefsOff &&
            dataSize == other.dataSize &&
            dataOff == other.dataOff
    }

    override fun hashCode(): Int {
        var result = magic.contentHashCode()
        result = 31 * result + checksum.hashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + headerSize.hashCode()
        return result
    }
}

/**
 * `string_id_item`: offset to string data + lazily resolved value.
 */
data class DexStringId(
    val stringDataOff: Long,
    /** The decoded MUTF-8 string value. */
    val value: String,
)

/** `type_id_item`: index into the string IDs list. */
data class DexTypeId(
    val descriptorIdx: Int,
)

/** `proto_id_item`: method prototype. */
data class DexProtoId(
    val shortyIdx: Int,
    val returnTypeIdx: Int,
    val parametersOff: Long,
)

/** `field_id_item`. */
data class DexFieldId(
    val classIdx: Int,
    val typeIdx: Int,
    val nameIdx: Int,
)

/** `method_id_item`. */
data class DexMethodId(
    val classIdx: Int,
    val protoIdx: Int,
    val nameIdx: Int,
)

/** `class_def_item`. */
data class DexClassDef(
    val classIdx: Int,
    val accessFlags: Int,
    val superclassIdx: Int,
    val interfacesOff: Long,
    val sourceFileIdx: Int,
    val annotationsOff: Long,
    val classDataOff: Long,
    val staticValuesOff: Long,
)

/** Parsed `class_data_item`: fields and methods belonging to a single class. */
data class DexClassData(
    val staticFields: List<DexEncodedField>,
    val instanceFields: List<DexEncodedField>,
    val directMethods: List<DexEncodedMethod>,
    val virtualMethods: List<DexEncodedMethod>,
)

/** `encoded_field` inside a `class_data_item`. */
data class DexEncodedField(
    /** Index into [DexFile.fieldIds]. */
    val fieldIdx: Int,
    val accessFlags: Int,
)

/** `encoded_method` inside a `class_data_item`. */
data class DexEncodedMethod(
    /** Index into [DexFile.methodIds]. */
    val methodIdx: Int,
    val accessFlags: Int,
    val codeOff: Long,
)

/** DEX access flag constants (shared between classes, fields, and methods). */
object DexAccessFlags {
    const val ACC_PUBLIC = 0x0001
    const val ACC_PRIVATE = 0x0002
    const val ACC_PROTECTED = 0x0004
    const val ACC_STATIC = 0x0008
    const val ACC_FINAL = 0x0010
    const val ACC_SYNCHRONIZED = 0x0020
    const val ACC_VOLATILE = 0x0040
    const val ACC_BRIDGE = 0x0040
    const val ACC_TRANSIENT = 0x0080
    const val ACC_VARARGS = 0x0080
    const val ACC_NATIVE = 0x0100
    const val ACC_INTERFACE = 0x0200
    const val ACC_ABSTRACT = 0x0400
    const val ACC_STRICT = 0x0800
    const val ACC_SYNTHETIC = 0x1000
    const val ACC_ANNOTATION = 0x2000
    const val ACC_ENUM = 0x4000
    const val ACC_CONSTRUCTOR = 0x10000
    const val ACC_DECLARED_SYNCHRONIZED = 0x20000
}

fun renderDexClassAccessFlags(flags: Int): String = buildString {
    if (flags and DexAccessFlags.ACC_PUBLIC != 0) append("public ")
    if (flags and DexAccessFlags.ACC_PRIVATE != 0) append("private ")
    if (flags and DexAccessFlags.ACC_PROTECTED != 0) append("protected ")
    if (flags and DexAccessFlags.ACC_STATIC != 0) append("static ")
    if (flags and DexAccessFlags.ACC_FINAL != 0) append("final ")
    if (flags and DexAccessFlags.ACC_INTERFACE != 0) append("interface ")
    if (flags and DexAccessFlags.ACC_ABSTRACT != 0) append("abstract ")
    if (flags and DexAccessFlags.ACC_SYNTHETIC != 0) append("synthetic ")
    if (flags and DexAccessFlags.ACC_ANNOTATION != 0) append("annotation ")
    if (flags and DexAccessFlags.ACC_ENUM != 0) append("enum ")
}.trimEnd()

fun renderDexMethodAccessFlags(flags: Int): String = buildString {
    if (flags and DexAccessFlags.ACC_PUBLIC != 0) append("public ")
    if (flags and DexAccessFlags.ACC_PRIVATE != 0) append("private ")
    if (flags and DexAccessFlags.ACC_PROTECTED != 0) append("protected ")
    if (flags and DexAccessFlags.ACC_STATIC != 0) append("static ")
    if (flags and DexAccessFlags.ACC_FINAL != 0) append("final ")
    if (flags and DexAccessFlags.ACC_SYNCHRONIZED != 0) append("synchronized ")
    if (flags and DexAccessFlags.ACC_BRIDGE != 0) append("bridge ")
    if (flags and DexAccessFlags.ACC_VARARGS != 0) append("varargs ")
    if (flags and DexAccessFlags.ACC_NATIVE != 0) append("native ")
    if (flags and DexAccessFlags.ACC_ABSTRACT != 0) append("abstract ")
    if (flags and DexAccessFlags.ACC_STRICT != 0) append("strictfp ")
    if (flags and DexAccessFlags.ACC_SYNTHETIC != 0) append("synthetic ")
    if (flags and DexAccessFlags.ACC_CONSTRUCTOR != 0) append("constructor ")
    if (flags and DexAccessFlags.ACC_DECLARED_SYNCHRONIZED != 0) append("declared-synchronized ")
}.trimEnd()

fun renderDexFieldAccessFlags(flags: Int): String = buildString {
    if (flags and DexAccessFlags.ACC_PUBLIC != 0) append("public ")
    if (flags and DexAccessFlags.ACC_PRIVATE != 0) append("private ")
    if (flags and DexAccessFlags.ACC_PROTECTED != 0) append("protected ")
    if (flags and DexAccessFlags.ACC_STATIC != 0) append("static ")
    if (flags and DexAccessFlags.ACC_FINAL != 0) append("final ")
    if (flags and DexAccessFlags.ACC_VOLATILE != 0) append("volatile ")
    if (flags and DexAccessFlags.ACC_TRANSIENT != 0) append("transient ")
    if (flags and DexAccessFlags.ACC_SYNTHETIC != 0) append("synthetic ")
    if (flags and DexAccessFlags.ACC_ENUM != 0) append("enum ")
}.trimEnd()
