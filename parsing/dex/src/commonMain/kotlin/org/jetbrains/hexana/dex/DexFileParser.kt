package org.jetbrains.hexana.dex

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parses Android `.dex` files on top of [CommonByteBuffer].
 *
 * DEX files are little-endian (the standard case). The buffer is read using
 * the little-endian helpers already present on [CommonByteBuffer].
 *
 * Reference: https://source.android.com/docs/core/runtime/dex-format
 */
object DexFileParser {

    /** `dex\n` prefix — the first 4 bytes of every DEX file. */
    private val MAGIC_PREFIX = byteArrayOf(0x64, 0x65, 0x78, 0x0A)

    /** Standard little-endian tag: `0x12345678`. */
    private const val ENDIAN_CONSTANT = 0x12345678L

    fun isDex(buffer: CommonByteBuffer): Boolean {
        if (buffer.size < 8) return false
        val saved = buffer.position()
        try {
            buffer.position(0)
            for (b in MAGIC_PREFIX) {
                if (buffer.get() != b) return false
            }
            return true
        } finally {
            buffer.position(saved)
        }
    }

    fun parse(buffer: CommonByteBuffer): DexFile {
        buffer.position(0)
        val header = parseHeader(buffer)
        val stringIds = parseStringIds(buffer, header)
        val typeIds = parseTypeIds(buffer, header)
        val protoIds = parseProtoIds(buffer, header)
        val fieldIds = parseFieldIds(buffer, header)
        val methodIds = parseMethodIds(buffer, header)
        val classDefs = parseClassDefs(buffer, header)
        return DexFile(header, stringIds, typeIds, protoIds, fieldIds, methodIds, classDefs)
    }

    private fun parseHeader(buffer: CommonByteBuffer): DexHeader {
        val magic = ByteArray(8).also { buffer.get(it, 0, 8) }
        // Validate magic prefix
        for (i in MAGIC_PREFIX.indices) {
            require(magic[i] == MAGIC_PREFIX[i]) {
                "not a DEX file: bad magic"
            }
        }
        val checksum = buffer.readU4()
        val signature = ByteArray(20).also { buffer.get(it, 0, 20) }
        val fileSize = buffer.readU4()
        val headerSize = buffer.readU4()
        val endianTag = buffer.readU4()
        require(endianTag == ENDIAN_CONSTANT) {
            "unsupported endianness: 0x${endianTag.toString(16)}"
        }
        val linkSize = buffer.readU4()
        val linkOff = buffer.readU4()
        val mapOff = buffer.readU4()
        val stringIdsSize = buffer.readU4().toInt()
        val stringIdsOff = buffer.readU4()
        val typeIdsSize = buffer.readU4().toInt()
        val typeIdsOff = buffer.readU4()
        val protoIdsSize = buffer.readU4().toInt()
        val protoIdsOff = buffer.readU4()
        val fieldIdsSize = buffer.readU4().toInt()
        val fieldIdsOff = buffer.readU4()
        val methodIdsSize = buffer.readU4().toInt()
        val methodIdsOff = buffer.readU4()
        val classDefsSize = buffer.readU4().toInt()
        val classDefsOff = buffer.readU4()
        val dataSize = buffer.readU4()
        val dataOff = buffer.readU4()

        return DexHeader(
            magic = magic,
            checksum = checksum,
            signature = signature,
            fileSize = fileSize,
            headerSize = headerSize,
            endianTag = endianTag,
            linkSize = linkSize,
            linkOff = linkOff,
            mapOff = mapOff,
            stringIdsSize = stringIdsSize,
            stringIdsOff = stringIdsOff,
            typeIdsSize = typeIdsSize,
            typeIdsOff = typeIdsOff,
            protoIdsSize = protoIdsSize,
            protoIdsOff = protoIdsOff,
            fieldIdsSize = fieldIdsSize,
            fieldIdsOff = fieldIdsOff,
            methodIdsSize = methodIdsSize,
            methodIdsOff = methodIdsOff,
            classDefsSize = classDefsSize,
            classDefsOff = classDefsOff,
            dataSize = dataSize,
            dataOff = dataOff,
        )
    }

    private fun parseStringIds(buffer: CommonByteBuffer, header: DexHeader): List<DexStringId> {
        if (header.stringIdsSize == 0) return emptyList()
        buffer.position(header.stringIdsOff.toInt())
        val offsets = LongArray(header.stringIdsSize) { buffer.readU4() }
        return offsets.map { off ->
            buffer.position(off.toInt())
            val value = readMutf8String(buffer)
            DexStringId(stringDataOff = off, value = value)
        }
    }

    private fun parseTypeIds(buffer: CommonByteBuffer, header: DexHeader): List<DexTypeId> {
        if (header.typeIdsSize == 0) return emptyList()
        buffer.position(header.typeIdsOff.toInt())
        return List(header.typeIdsSize) {
            DexTypeId(descriptorIdx = buffer.readU4().toInt())
        }
    }

    private fun parseProtoIds(buffer: CommonByteBuffer, header: DexHeader): List<DexProtoId> {
        if (header.protoIdsSize == 0) return emptyList()
        buffer.position(header.protoIdsOff.toInt())
        val protos = List(header.protoIdsSize) {
            DexProtoId(
                shortyIdx = buffer.readU4().toInt(),
                returnTypeIdx = buffer.readU4().toInt(),
                parametersOff = buffer.readU4(),
            )
        }
        // Parameter type lists live in the data section, so they are resolved in
        // a second pass rather than seeking away mid-table.
        return protos.map { proto ->
            proto.copy(parameterTypeIdx = parseTypeList(buffer, proto.parametersOff))
        }
    }

    private fun parseFieldIds(buffer: CommonByteBuffer, header: DexHeader): List<DexFieldId> {
        if (header.fieldIdsSize == 0) return emptyList()
        buffer.position(header.fieldIdsOff.toInt())
        return List(header.fieldIdsSize) {
            DexFieldId(
                classIdx = buffer.readU2(),
                typeIdx = buffer.readU2(),
                nameIdx = buffer.readU4().toInt(),
            )
        }
    }

    private fun parseMethodIds(buffer: CommonByteBuffer, header: DexHeader): List<DexMethodId> {
        if (header.methodIdsSize == 0) return emptyList()
        buffer.position(header.methodIdsOff.toInt())
        return List(header.methodIdsSize) {
            DexMethodId(
                classIdx = buffer.readU2(),
                protoIdx = buffer.readU2(),
                nameIdx = buffer.readU4().toInt(),
            )
        }
    }

    private fun parseClassDefs(buffer: CommonByteBuffer, header: DexHeader): List<DexClassDef> {
        if (header.classDefsSize == 0) return emptyList()
        buffer.position(header.classDefsOff.toInt())
        return List(header.classDefsSize) {
            DexClassDef(
                classIdx = buffer.readU4().toInt(),
                accessFlags = buffer.readU4().toInt(),
                superclassIdx = buffer.readU4().toInt(),
                interfacesOff = buffer.readU4(),
                sourceFileIdx = buffer.readU4().toInt(),
                annotationsOff = buffer.readU4(),
                classDataOff = buffer.readU4(),
                staticValuesOff = buffer.readU4(),
            )
        }
    }

    /**
     * Parses the `class_data_item` at [classDef]'s `classDataOff`.
     * Returns `null` when the offset is zero (no class data).
     */
    fun parseClassData(buffer: CommonByteBuffer, classDef: DexClassDef): DexClassData? {
        val off = classDef.classDataOff
        if (off == 0L) return null
        buffer.position(off.toInt())

        val staticFieldsSize = buffer.readUleb128()
        val instanceFieldsSize = buffer.readUleb128()
        val directMethodsSize = buffer.readUleb128()
        val virtualMethodsSize = buffer.readUleb128()

        fun readFields(count: Int): List<DexEncodedField> {
            var fieldIdx = 0
            return List(count) {
                fieldIdx += buffer.readUleb128()
                val accessFlags = buffer.readUleb128()
                DexEncodedField(fieldIdx, accessFlags)
            }
        }

        fun readMethods(count: Int): List<DexEncodedMethod> {
            var methodIdx = 0
            return List(count) {
                methodIdx += buffer.readUleb128()
                val accessFlags = buffer.readUleb128()
                val codeOff = buffer.readUleb128().toLong()
                DexEncodedMethod(methodIdx, accessFlags, codeOff)
            }
        }

        return DexClassData(
            staticFields = readFields(staticFieldsSize),
            instanceFields = readFields(instanceFieldsSize),
            directMethods = readMethods(directMethodsSize),
            virtualMethods = readMethods(virtualMethodsSize),
        )
    }

    /**
     * Parses the `code_item` at [codeOff] — one method's register frame,
     * instruction stream and exception table. Returns `null` when [codeOff] is
     * zero, which is how abstract and native methods are encoded.
     */
    fun parseCodeItem(buffer: CommonByteBuffer, codeOff: Long): DexCodeItem? {
        if (codeOff == 0L) return null
        buffer.position(codeOff.toInt())

        val registersSize = buffer.readU2()
        val insSize = buffer.readU2()
        val outsSize = buffer.readU2()
        val triesSize = buffer.readU2()
        val debugInfoOff = buffer.readU4()
        val insnsSize = buffer.readU4().toInt()
        require(insnsSize >= 0) { "code_item at $codeOff has insns_size $insnsSize" }

        val insnsFileOffset = buffer.position()
        val insns = ByteArray(insnsSize * 2).also { buffer.get(it, 0, it.size) }

        val tries = if (triesSize == 0) {
            emptyList()
        } else {
            // A padding code unit keeps try_item[] 4-byte aligned.
            if (insnsSize % 2 != 0) buffer.readU2()
            List(triesSize) {
                DexTry(
                    startAddr = buffer.readU4().toInt(),
                    insnCount = buffer.readU2(),
                    handlerOff = buffer.readU2(),
                )
            }
        }

        val handlers = if (triesSize == 0) emptyList() else parseCatchHandlers(buffer)

        return DexCodeItem(
            registersSize = registersSize,
            insSize = insSize,
            outsSize = outsSize,
            debugInfoOff = debugInfoOff,
            insnsSize = insnsSize,
            insnsFileOffset = insnsFileOffset,
            instructions = DexBytecodeDecoder.decode(insns),
            tries = tries,
            handlers = handlers,
        )
    }

    /**
     * Parses the `encoded_catch_handler_list` at the buffer's current position.
     * Handler offsets in `try_item` are byte offsets from the start of this
     * list, so each handler records its own relative offset.
     */
    private fun parseCatchHandlers(buffer: CommonByteBuffer): List<DexCatchHandler> {
        val listStart = buffer.position()
        val size = buffer.readUleb128()
        return List(size) {
            val offset = buffer.position() - listStart
            // A negative size means |size| typed handlers followed by a catch-all.
            val encodedSize = buffer.readSleb128()
            val typeHandlers = List(if (encodedSize < 0) -encodedSize else encodedSize) {
                DexTypeAddrPair(typeIdx = buffer.readUleb128(), addr = buffer.readUleb128())
            }
            val catchAllAddr = if (encodedSize <= 0) buffer.readUleb128() else null
            DexCatchHandler(offset, typeHandlers, catchAllAddr)
        }
    }

    /**
     * Parses a `type_list` at the given offset (e.g. `interfaces_off` or
     * `parameters_off`). Returns an empty list when [offset] is zero.
     */
    fun parseTypeList(buffer: CommonByteBuffer, offset: Long): List<Int> {
        if (offset == 0L) return emptyList()
        buffer.position(offset.toInt())
        val size = buffer.readU4().toInt()
        return List(size) { buffer.readU2() }
    }
}

// -----------------------------------------------------------------------------
// Little-endian readers over CommonByteBuffer
// -----------------------------------------------------------------------------

private fun CommonByteBuffer.readU1(): Int = get().toInt() and 0xFF

private fun CommonByteBuffer.readU2(): Int = readU1() or (readU1() shl 8)

private fun CommonByteBuffer.readU4(): Long {
    val b0 = readU1().toLong()
    val b1 = readU1().toLong()
    val b2 = readU1().toLong()
    val b3 = readU1().toLong()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

/**
 * Reads a ULEB128-encoded unsigned integer (used for string lengths in
 * `string_data_item`).
 */
private fun CommonByteBuffer.readUleb128(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val b = readU1()
        result = result or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) break
        shift += 7
    }
    return result
}

/**
 * Reads a SLEB128-encoded signed integer (used for `encoded_catch_handler`
 * sizes, where a negative value marks a trailing catch-all handler).
 */
private fun CommonByteBuffer.readSleb128(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val b = readU1()
        result = result or ((b and 0x7F) shl shift)
        shift += 7
        if (b and 0x80 == 0) {
            // Sign-extend from the last significant bit read.
            if (shift < 32 && (b and 0x40) != 0) {
                result = result or (-1 shl shift)
            }
            break
        }
    }
    return result
}

/**
 * Reads a Modified UTF-8 (MUTF-8) string as used in DEX `string_data_item`.
 * The format is: ULEB128 utf16_size, then MUTF-8 bytes terminated by 0x00.
 */
private fun readMutf8String(buffer: CommonByteBuffer): String {
    val utf16Size = buffer.readUleb128()
    val chars = CharArray(utf16Size)
    var charIdx = 0
    while (charIdx < utf16Size) {
        val a = buffer.readU1()
        if (a == 0) break
        when {
            a and 0x80 == 0 -> {
                chars[charIdx++] = a.toChar()
            }
            a and 0xE0 == 0xC0 -> {
                val b = buffer.readU1()
                chars[charIdx++] = (((a and 0x1F) shl 6) or (b and 0x3F)).toChar()
            }
            a and 0xF0 == 0xE0 -> {
                val b = buffer.readU1()
                val c = buffer.readU1()
                chars[charIdx++] = (((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar()
            }
            else -> {
                chars[charIdx++] = '?'
            }
        }
    }
    // Skip remaining bytes until null terminator
    while (true) {
        val b = buffer.readU1()
        if (b == 0) break
    }
    return chars.concatToString(0, 0 + charIdx)
}
