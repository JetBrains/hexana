package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.providers.pe.clr.ClrTableId as T

/** Column kinds of ECMA-335 II.22 metadata tables. */
internal sealed interface ClrColumn {
    data class Fixed(val size: Int) : ClrColumn
    data object StringIndex : ClrColumn
    data object GuidIndex : ClrColumn
    data object BlobIndex : ClrColumn
    data class TableIndex(val table: Int) : ClrColumn
    data class Coded(val kind: ClrCodedIndex) : ClrColumn
}

/**
 * ECMA-335 II.24.2.6 coded index kinds. [tables] lists the target table per tag
 * value; `-1` marks tag values that are reserved.
 */
internal enum class ClrCodedIndex(val tagBits: Int, val tables: List<Int>) {
    TypeDefOrRef(2, listOf(T.TYPE_DEF, T.TYPE_REF, T.TYPE_SPEC)),
    HasConstant(2, listOf(T.FIELD, T.PARAM, T.PROPERTY)),
    HasCustomAttribute(
        5,
        listOf(
            T.METHOD_DEF, T.FIELD, T.TYPE_REF, T.TYPE_DEF, T.PARAM, T.INTERFACE_IMPL, T.MEMBER_REF, T.MODULE,
            T.DECL_SECURITY, T.PROPERTY, T.EVENT, T.STAND_ALONE_SIG, T.MODULE_REF, T.TYPE_SPEC, T.ASSEMBLY,
            T.ASSEMBLY_REF, T.FILE, T.EXPORTED_TYPE, T.MANIFEST_RESOURCE, T.GENERIC_PARAM,
            T.GENERIC_PARAM_CONSTRAINT, T.METHOD_SPEC,
        ),
    ),
    HasFieldMarshal(1, listOf(T.FIELD, T.PARAM)),
    HasDeclSecurity(2, listOf(T.TYPE_DEF, T.METHOD_DEF, T.ASSEMBLY)),
    MemberRefParent(3, listOf(T.TYPE_DEF, T.TYPE_REF, T.MODULE_REF, T.METHOD_DEF, T.TYPE_SPEC)),
    HasSemantics(1, listOf(T.EVENT, T.PROPERTY)),
    MethodDefOrRef(1, listOf(T.METHOD_DEF, T.MEMBER_REF)),
    MemberForwarded(1, listOf(T.FIELD, T.METHOD_DEF)),
    Implementation(2, listOf(T.FILE, T.ASSEMBLY_REF, T.EXPORTED_TYPE)),
    CustomAttributeType(3, listOf(-1, -1, T.METHOD_DEF, T.MEMBER_REF, -1)),
    ResolutionScope(2, listOf(T.MODULE, T.MODULE_REF, T.ASSEMBLY_REF, T.TYPE_REF)),
    TypeOrMethodDef(1, listOf(T.TYPE_DEF, T.METHOD_DEF)),
}

internal class ClrTableSchema(val id: Int, val name: String, val columns: List<ClrColumn>)

private fun u1() = ClrColumn.Fixed(1)
private fun u2() = ClrColumn.Fixed(2)
private fun u4() = ClrColumn.Fixed(4)
private val str = ClrColumn.StringIndex
private val guid = ClrColumn.GuidIndex
private val blob = ClrColumn.BlobIndex
private fun idx(table: Int) = ClrColumn.TableIndex(table)
private fun coded(kind: ClrCodedIndex) = ClrColumn.Coded(kind)

/** Schemas indexed by table id, covering every table defined by ECMA-335 (0x00..0x2C). */
internal val CLR_TABLE_SCHEMAS: List<ClrTableSchema?> = run {
    val schemas = arrayOfNulls<ClrTableSchema>(64)
    fun def(id: Int, name: String, vararg columns: ClrColumn) {
        schemas[id] = ClrTableSchema(id, name, columns.toList())
    }
    def(T.MODULE, "Module", u2(), str, guid, guid, guid)
    def(T.TYPE_REF, "TypeRef", coded(ClrCodedIndex.ResolutionScope), str, str)
    def(T.TYPE_DEF, "TypeDef", u4(), str, str, coded(ClrCodedIndex.TypeDefOrRef), idx(T.FIELD), idx(T.METHOD_DEF))
    def(T.FIELD_PTR, "FieldPtr", idx(T.FIELD))
    def(T.FIELD, "Field", u2(), str, blob)
    def(T.METHOD_PTR, "MethodPtr", idx(T.METHOD_DEF))
    def(T.METHOD_DEF, "MethodDef", u4(), u2(), u2(), str, blob, idx(T.PARAM))
    def(T.PARAM_PTR, "ParamPtr", idx(T.PARAM))
    def(T.PARAM, "Param", u2(), u2(), str)
    def(T.INTERFACE_IMPL, "InterfaceImpl", idx(T.TYPE_DEF), coded(ClrCodedIndex.TypeDefOrRef))
    def(T.MEMBER_REF, "MemberRef", coded(ClrCodedIndex.MemberRefParent), str, blob)
    // Type is a single byte followed by a padding byte.
    def(T.CONSTANT, "Constant", u1(), u1(), coded(ClrCodedIndex.HasConstant), blob)
    def(T.CUSTOM_ATTRIBUTE, "CustomAttribute", coded(ClrCodedIndex.HasCustomAttribute), coded(ClrCodedIndex.CustomAttributeType), blob)
    def(T.FIELD_MARSHAL, "FieldMarshal", coded(ClrCodedIndex.HasFieldMarshal), blob)
    def(T.DECL_SECURITY, "DeclSecurity", u2(), coded(ClrCodedIndex.HasDeclSecurity), blob)
    def(T.CLASS_LAYOUT, "ClassLayout", u2(), u4(), idx(T.TYPE_DEF))
    def(T.FIELD_LAYOUT, "FieldLayout", u4(), idx(T.FIELD))
    def(T.STAND_ALONE_SIG, "StandAloneSig", blob)
    def(T.EVENT_MAP, "EventMap", idx(T.TYPE_DEF), idx(T.EVENT))
    def(T.EVENT_PTR, "EventPtr", idx(T.EVENT))
    def(T.EVENT, "Event", u2(), str, coded(ClrCodedIndex.TypeDefOrRef))
    def(T.PROPERTY_MAP, "PropertyMap", idx(T.TYPE_DEF), idx(T.PROPERTY))
    def(T.PROPERTY_PTR, "PropertyPtr", idx(T.PROPERTY))
    def(T.PROPERTY, "Property", u2(), str, blob)
    def(T.METHOD_SEMANTICS, "MethodSemantics", u2(), idx(T.METHOD_DEF), coded(ClrCodedIndex.HasSemantics))
    def(T.METHOD_IMPL, "MethodImpl", idx(T.TYPE_DEF), coded(ClrCodedIndex.MethodDefOrRef), coded(ClrCodedIndex.MethodDefOrRef))
    def(T.MODULE_REF, "ModuleRef", str)
    def(T.TYPE_SPEC, "TypeSpec", blob)
    def(T.IMPL_MAP, "ImplMap", u2(), coded(ClrCodedIndex.MemberForwarded), str, idx(T.MODULE_REF))
    def(T.FIELD_RVA, "FieldRVA", u4(), idx(T.FIELD))
    def(T.ENC_LOG, "EncLog", u4(), u4())
    def(T.ENC_MAP, "EncMap", u4())
    def(T.ASSEMBLY, "Assembly", u4(), u2(), u2(), u2(), u2(), u4(), blob, str, str)
    def(T.ASSEMBLY_PROCESSOR, "AssemblyProcessor", u4())
    def(T.ASSEMBLY_OS, "AssemblyOS", u4(), u4(), u4())
    def(T.ASSEMBLY_REF, "AssemblyRef", u2(), u2(), u2(), u2(), u4(), blob, str, str, blob)
    def(T.ASSEMBLY_REF_PROCESSOR, "AssemblyRefProcessor", u4(), idx(T.ASSEMBLY_REF))
    def(T.ASSEMBLY_REF_OS, "AssemblyRefOS", u4(), u4(), u4(), idx(T.ASSEMBLY_REF))
    def(T.FILE, "File", u4(), str, blob)
    def(T.EXPORTED_TYPE, "ExportedType", u4(), u4(), str, str, coded(ClrCodedIndex.Implementation))
    def(T.MANIFEST_RESOURCE, "ManifestResource", u4(), u4(), str, coded(ClrCodedIndex.Implementation))
    def(T.NESTED_CLASS, "NestedClass", idx(T.TYPE_DEF), idx(T.TYPE_DEF))
    def(T.GENERIC_PARAM, "GenericParam", u2(), u2(), coded(ClrCodedIndex.TypeOrMethodDef), str)
    def(T.METHOD_SPEC, "MethodSpec", coded(ClrCodedIndex.MethodDefOrRef), blob)
    def(T.GENERIC_PARAM_CONSTRAINT, "GenericParamConstraint", idx(T.GENERIC_PARAM), coded(ClrCodedIndex.TypeDefOrRef))
    schemas.toList()
}

/** Portable PDB tables (0x30..0x37) only appear in standalone or embedded PDB metadata. */
internal fun clrTableName(id: Int): String = CLR_TABLE_SCHEMAS.getOrNull(id)?.name ?: when (id) {
    0x30 -> "Document"
    0x31 -> "MethodDebugInformation"
    0x32 -> "LocalScope"
    0x33 -> "LocalVariable"
    0x34 -> "LocalConstant"
    0x35 -> "ImportScope"
    0x36 -> "StateMachineMethod"
    0x37 -> "CustomDebugInformation"
    else -> "Table 0x${id.toString(16).uppercase().padStart(2, '0')}"
}
