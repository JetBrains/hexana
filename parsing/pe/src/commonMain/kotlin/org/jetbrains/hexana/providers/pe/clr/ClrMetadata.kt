package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic

/**
 * Managed (.NET / ECMA-335) metadata of a PE image, decoded from the CLR
 * runtime header data directory (index 14).
 *
 * Everything below the [header] is best effort: a malformed metadata root or
 * tables stream leaves the corresponding parts empty and records a
 * diagnostic instead of failing the whole PE parse.
 */
data class ClrMetadata(
    val header: ClrCliHeader,
    val root: ClrMetadataRoot?,
    val streams: List<ClrStream>,
    val tablesHeader: ClrTablesHeader?,
    val tables: List<ClrTableInfo>,
    val module: ClrModule?,
    val assembly: ClrAssembly?,
    /** `TargetFrameworkAttribute` value on the assembly, e.g. `.NETCoreApp,Version=v8.0`. */
    val targetFramework: String?,
    /** Human-readable entry point: `Type::Method` for a managed entry point, an RVA for a native one. */
    val entryPoint: String?,
    val readyToRun: Boolean,
    val typeRefs: List<ClrTypeRef>,
    val typeDefs: List<ClrTypeDef>,
    val fields: List<ClrField>,
    val methods: List<ClrMethodDef>,
    val memberRefs: List<ClrMemberRef>,
    val moduleRefs: List<ClrModuleRef>,
    val assemblyRefs: List<ClrAssemblyRef>,
    val manifestResources: List<ClrManifestResource>,
    val userStrings: List<ClrUserString>,
    val diagnostics: List<NativeParserDiagnostic>,
) {
    /** Fields grouped by the 1-based TypeDef row of their declaring type. */
    val fieldsByType: Map<Int, List<ClrField>> by lazy { fields.groupBy { it.declaringTypeRow } }

    /** Methods grouped by the 1-based TypeDef row of their declaring type. */
    val methodsByType: Map<Int, List<ClrMethodDef>> by lazy { methods.groupBy { it.declaringTypeRow } }
}

/** ECMA-335 II.25.3.3 CLI header. */
data class ClrCliHeader(
    val size: UInt,
    val majorRuntimeVersion: Int,
    val minorRuntimeVersion: Int,
    val metadataRva: UInt,
    val metadataSize: UInt,
    val flags: UInt,
    val entryPointToken: UInt,
    val resourcesRva: UInt,
    val resourcesSize: UInt,
    val strongNameSignatureRva: UInt,
    val strongNameSignatureSize: UInt,
    val vTableFixupsRva: UInt,
    val vTableFixupsSize: UInt,
    val managedNativeHeaderRva: UInt,
    val managedNativeHeaderSize: UInt,
    val headerRange: NativeByteRange,
) {
    val isIlOnly: Boolean get() = flags and ClrHeaderFlags.IL_ONLY != 0u
    val requires32Bit: Boolean get() = flags and ClrHeaderFlags.REQUIRED_32BIT != 0u
    val prefers32Bit: Boolean get() = flags and ClrHeaderFlags.PREFERRED_32BIT != 0u
    val isStrongNameSigned: Boolean get() = flags and ClrHeaderFlags.STRONG_NAME_SIGNED != 0u
    val hasNativeEntryPoint: Boolean get() = flags and ClrHeaderFlags.NATIVE_ENTRYPOINT != 0u
}

object ClrHeaderFlags {
    const val IL_ONLY: UInt = 0x00000001u
    const val REQUIRED_32BIT: UInt = 0x00000002u
    const val IL_LIBRARY: UInt = 0x00000004u
    const val STRONG_NAME_SIGNED: UInt = 0x00000008u
    const val NATIVE_ENTRYPOINT: UInt = 0x00000010u
    const val TRACK_DEBUG_DATA: UInt = 0x00010000u
    const val PREFERRED_32BIT: UInt = 0x00020000u
}

/** ECMA-335 II.24.2.1 metadata root (`BSJB`). */
data class ClrMetadataRoot(
    val majorVersion: Int,
    val minorVersion: Int,
    /** Runtime version string, e.g. `v4.0.30319`. */
    val version: String,
    val flags: Int,
    val streamCount: Int,
    val range: NativeByteRange,
)

/** ECMA-335 II.24.2.2 stream header plus the stream's location in the file. */
data class ClrStream(
    val index: Int,
    val name: String,
    val offset: UInt,
    val size: UInt,
    val headerRange: NativeByteRange,
    /** File range of the stream data, or null when it points outside the metadata. */
    val contentRange: NativeByteRange?,
)

/** ECMA-335 II.24.2.6 `#~` / `#-` stream header. */
data class ClrTablesHeader(
    val streamName: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val heapSizes: Int,
    val validMask: ULong,
    val sortedMask: ULong,
    val range: NativeByteRange,
)

data class ClrTableInfo(
    val id: Int,
    val name: String,
    val rowCount: Int,
    /** Row size in bytes; 0 when the table schema is unknown to Hexana. */
    val rowSize: Int,
    val sorted: Boolean,
    /** File range of the table rows; null when the layout could not be computed. */
    val range: NativeByteRange?,
)

/** A metadata token: table id in the high byte, 1-based row in the low 24 bits. */
fun clrToken(table: Int, row: Int): UInt = (table.toUInt() shl 24) or row.toUInt()

data class ClrModule(
    val generation: Int,
    val name: String,
    val mvid: String?,
    val recordRange: NativeByteRange,
)

data class ClrAssembly(
    val name: String,
    val version: ClrVersion,
    val culture: String,
    val flags: UInt,
    val hashAlgorithm: UInt,
    val publicKey: ByteArray,
    val recordRange: NativeByteRange,
) {
    override fun equals(other: Any?): Boolean =
        other is ClrAssembly && name == other.name && version == other.version && culture == other.culture &&
            flags == other.flags && hashAlgorithm == other.hashAlgorithm && publicKey.contentEquals(other.publicKey) &&
            recordRange == other.recordRange

    override fun hashCode(): Int = 31 * name.hashCode() + version.hashCode()
}

data class ClrVersion(val major: Int, val minor: Int, val build: Int, val revision: Int) {
    override fun toString(): String = "$major.$minor.$build.$revision"
}

data class ClrAssemblyRef(
    val row: Int,
    val name: String,
    val version: ClrVersion,
    val culture: String,
    val flags: UInt,
    /** Public key token (8 bytes) or the full public key when `flags` has the PublicKey bit. */
    val publicKeyOrToken: ByteArray,
    val recordRange: NativeByteRange,
) {
    val token: UInt get() = clrToken(ClrTableId.ASSEMBLY_REF, row)

    override fun equals(other: Any?): Boolean =
        other is ClrAssemblyRef && row == other.row && name == other.name && version == other.version &&
            culture == other.culture && flags == other.flags && publicKeyOrToken.contentEquals(other.publicKeyOrToken) &&
            recordRange == other.recordRange

    override fun hashCode(): Int = 31 * row + name.hashCode()
}

data class ClrTypeRef(
    val row: Int,
    val namespace: String,
    val name: String,
    /** Reflection-style full name; nested references use `Outer+Nested`. */
    val fullName: String,
    /** Assembly or module that defines the type; nested references report their outermost type's scope. */
    val scope: String,
    val recordRange: NativeByteRange,
) {
    val token: UInt get() = clrToken(ClrTableId.TYPE_REF, row)
}

enum class ClrTypeKind(val label: String) {
    Class("class"),
    Interface("interface"),
    Struct("struct"),
    Enum("enum"),
    Delegate("delegate"),
    Module("<module>"),
}

data class ClrTypeDef(
    val row: Int,
    val namespace: String,
    val name: String,
    /** Reflection-style full name: `Namespace.Outer+Nested`. */
    val fullName: String,
    val flags: UInt,
    val kind: ClrTypeKind,
    val extends: String?,
    /** 1-based row of the enclosing type, or null for a top-level type. */
    val enclosingTypeRow: Int?,
    val genericParameters: List<String>,
    val recordRange: NativeByteRange,
) {
    val token: UInt get() = clrToken(ClrTableId.TYPE_DEF, row)
}

data class ClrField(
    val row: Int,
    val name: String,
    val flags: Int,
    val type: String,
    /** 1-based TypeDef row of the declaring type, or 0 when unknown. */
    val declaringTypeRow: Int,
    val recordRange: NativeByteRange,
) {
    val token: UInt get() = clrToken(ClrTableId.FIELD, row)
}

data class ClrMethodDef(
    val row: Int,
    val name: String,
    val rva: UInt,
    val flags: Int,
    val implFlags: Int,
    /** Decoded signature, e.g. `string Greet(string name)`. */
    val signature: String,
    /** 1-based TypeDef row of the declaring type, or 0 when unknown. */
    val declaringTypeRow: Int,
    val body: ClrMethodBody?,
    val recordRange: NativeByteRange,
) {
    val token: UInt get() = clrToken(ClrTableId.METHOD_DEF, row)
}

/** ECMA-335 II.25.4 method body header and IL location. */
data class ClrMethodBody(
    val isFat: Boolean,
    val maxStack: Int,
    val codeSize: Int,
    val localVarSigToken: UInt,
    val hasExceptionSections: Boolean,
    /** Header plus IL code. */
    val range: NativeByteRange,
    val codeRange: NativeByteRange,
)

data class ClrMemberRef(
    val row: Int,
    val parent: String,
    val name: String,
    val signature: String,
    val isField: Boolean,
    val recordRange: NativeByteRange,
) {
    val token: UInt get() = clrToken(ClrTableId.MEMBER_REF, row)
}

data class ClrModuleRef(
    val row: Int,
    val name: String,
    val recordRange: NativeByteRange,
)

data class ClrManifestResource(
    val row: Int,
    val name: String,
    val offset: UInt,
    val flags: UInt,
    /** `embedded`, or the file / assembly reference that holds the resource. */
    val implementation: String,
    val size: UInt?,
    /** File range of the embedded resource data (without its length prefix). */
    val dataRange: NativeByteRange?,
    val recordRange: NativeByteRange,
) {
    val isPublic: Boolean get() = flags and 0x7u == 1u
}

data class ClrUserString(
    /** Offset inside the `#US` heap; together with table 0x70 this is the `ldstr` token. */
    val heapOffset: Int,
    val value: String,
    val range: NativeByteRange,
) {
    val token: UInt get() = 0x70000000u or heapOffset.toUInt()
}

object ClrTableId {
    const val MODULE: Int = 0x00
    const val TYPE_REF: Int = 0x01
    const val TYPE_DEF: Int = 0x02
    const val FIELD_PTR: Int = 0x03
    const val FIELD: Int = 0x04
    const val METHOD_PTR: Int = 0x05
    const val METHOD_DEF: Int = 0x06
    const val PARAM_PTR: Int = 0x07
    const val PARAM: Int = 0x08
    const val INTERFACE_IMPL: Int = 0x09
    const val MEMBER_REF: Int = 0x0A
    const val CONSTANT: Int = 0x0B
    const val CUSTOM_ATTRIBUTE: Int = 0x0C
    const val FIELD_MARSHAL: Int = 0x0D
    const val DECL_SECURITY: Int = 0x0E
    const val CLASS_LAYOUT: Int = 0x0F
    const val FIELD_LAYOUT: Int = 0x10
    const val STAND_ALONE_SIG: Int = 0x11
    const val EVENT_MAP: Int = 0x12
    const val EVENT_PTR: Int = 0x13
    const val EVENT: Int = 0x14
    const val PROPERTY_MAP: Int = 0x15
    const val PROPERTY_PTR: Int = 0x16
    const val PROPERTY: Int = 0x17
    const val METHOD_SEMANTICS: Int = 0x18
    const val METHOD_IMPL: Int = 0x19
    const val MODULE_REF: Int = 0x1A
    const val TYPE_SPEC: Int = 0x1B
    const val IMPL_MAP: Int = 0x1C
    const val FIELD_RVA: Int = 0x1D
    const val ENC_LOG: Int = 0x1E
    const val ENC_MAP: Int = 0x1F
    const val ASSEMBLY: Int = 0x20
    const val ASSEMBLY_PROCESSOR: Int = 0x21
    const val ASSEMBLY_OS: Int = 0x22
    const val ASSEMBLY_REF: Int = 0x23
    const val ASSEMBLY_REF_PROCESSOR: Int = 0x24
    const val ASSEMBLY_REF_OS: Int = 0x25
    const val FILE: Int = 0x26
    const val EXPORTED_TYPE: Int = 0x27
    const val MANIFEST_RESOURCE: Int = 0x28
    const val NESTED_CLASS: Int = 0x29
    const val GENERIC_PARAM: Int = 0x2A
    const val METHOD_SPEC: Int = 0x2B
    const val GENERIC_PARAM_CONSTRAINT: Int = 0x2C
}
