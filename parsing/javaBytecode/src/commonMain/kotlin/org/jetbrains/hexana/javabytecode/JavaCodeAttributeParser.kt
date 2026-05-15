package org.jetbrains.hexana.javabytecode

import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * Decodes a `Code` attribute's opaque body (JVMS §4.7.3) into a
 * [JavaCodeAttribute]. The constant pool is needed only to look up nested
 * attribute names (e.g. `LineNumberTable`); those nested attributes are
 * still kept opaque at this stage.
 */
object JavaCodeAttributeParser {
    /**
     * Returns the [JavaCodeAttribute] for [methodInfo]'s first `Code`
     * attribute, or `null` if the method has none (abstract / native).
     */
    fun parse(methodInfo: MethodInfo, constantPool: JavaConstantPool): JavaCodeAttribute? {
        val codeAttr = methodInfo.attributes.firstOrNull { isCodeAttribute(it, constantPool) }
            ?: return null
        // Code attribute body starts at attr.byteOffset + 6 (skip
        // attribute_name_index u2 + attribute_length u4). Within the body,
        // code[] starts at +8 (max_stack u2 + max_locals u2 + code_length u4).
        val codeBaseFileOffset = if (codeAttr.byteOffset == 0) 0 else codeAttr.byteOffset + 6 + 8
        return parse(codeAttr.info, codeBaseFileOffset)
    }

    /**
     * Decodes a raw `Code` attribute body (the bytes BETWEEN the attribute
     * header and the next attribute). [codeBaseFileOffset] threads the
     * absolute file offset where `code[]` begins so callers that want to
     * render file-level positions can do so without re-walking the parent
     * class file.
     */
    fun parse(info: ByteArray, codeBaseFileOffset: Int = 0): JavaCodeAttribute {
        val buf = info.toCommonByteBuffer()
        val maxStack = buf.readU2()
        val maxLocals = buf.readU2()
        val codeLength = buf.readU4()
        require(codeLength >= 0) { "Code attribute code_length is negative: $codeLength" }
        val code = ByteArray(codeLength)
        if (codeLength > 0) buf.get(code, 0, codeLength)
        val instructions = JavaBytecodeDecoder.decode(code)

        val exceptionCount = buf.readU2()
        val exceptionTable = List(exceptionCount) {
            JavaExceptionHandler(
                startPc = buf.readU2(),
                endPc = buf.readU2(),
                handlerPc = buf.readU2(),
                catchTypeCpIndex = buf.readU2(),
            )
        }

        val attributesCount = buf.readU2()
        val attributes = List(attributesCount) {
            val nameIndex = buf.readU2()
            val length = buf.readU4()
            val nestedInfo = ByteArray(length)
            if (length > 0) buf.get(nestedInfo, 0, length)
            AttributeRecord(nameIndex, nestedInfo)
        }

        return JavaCodeAttribute(
            maxStack = maxStack,
            maxLocals = maxLocals,
            code = code,
            instructions = instructions,
            exceptionTable = exceptionTable,
            attributes = attributes,
            codeBaseFileOffset = codeBaseFileOffset,
        )
    }

    private fun isCodeAttribute(attr: AttributeRecord, cp: JavaConstantPool): Boolean {
        val entry = cp[attr.nameIndex]
        return entry is JavaConstantPoolEntry.Utf8 && entry.value == "Code"
    }
}

// Big-endian readers — same as in JavaClassFileParser; duplicated here so
// the file stays self-contained without exposing private helpers.
private fun org.jetbrains.hexana.io.CommonByteBuffer.readU2(): Int =
    ((get().toInt() and 0xFF) shl 8) or (get().toInt() and 0xFF)

private fun org.jetbrains.hexana.io.CommonByteBuffer.readU4(): Int =
    ((get().toInt() and 0xFF) shl 24) or
        ((get().toInt() and 0xFF) shl 16) or
        ((get().toInt() and 0xFF) shl 8) or
        (get().toInt() and 0xFF)
