package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

sealed class TypeId {
    companion object {
        fun resolve(typeId: UInt): TypeId {
            return when (typeId) {
                0x7Fu -> TYPE_I32
                0x7Eu -> TYPE_I64
                0x7Du -> TYPE_F32
                0x7Cu -> TYPE_F64
                else -> error("unknown type id: $typeId")
            }
        }
    }

    data object TYPE_I32 : TypeId()
    data object TYPE_I64 : TypeId()
    data object TYPE_F32 : TypeId()
    data object TYPE_F64 : TypeId()
}

class WasmSectionTypes(size: Int, offset: Int) : WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 1

        private const val FUNCTION_TYPE_SIGNATURE: UByte = 0x60u;
    }

    fun CommonByteBuffer.parseTypes(): List<WasmSectionTypesRecord> {
        position(offset)

        val typeRecords = mutableListOf<WasmSectionTypesRecord>()

        consumeArray {
            val type = get().toUByte()

            if (type != FUNCTION_TYPE_SIGNATURE) {
                error("unknown type id: ${type}");
            }

            val params = mutableListOf<TypeId>()
            consumeArray {
                val param = get().toUInt()
                params.add(TypeId.resolve(param))
            }

            val retTypes = mutableListOf<TypeId>()
            consumeArray {
                val retType = get().toUInt()
                retTypes.add(TypeId.resolve(retType))
            }

            typeRecords.add(WasmSectionTypesRecord(params, retTypes))
        }

        return typeRecords
    }
}

data class WasmSectionTypesRecord(
    val params: List<TypeId>,
    val retTypes: List<TypeId>
)