package org.jetbrains.hexana.shared

/**
 * Hand-built DEX file for the smali renderer tests. There is no checked-in
 * `.dex` fixture in this repository — every DEX test constructs exactly the
 * bytes it needs — and a renderer test needs real `class_data_item` and
 * `code_item` structures, which a directly constructed model cannot provide.
 *
 * The file describes:
 *
 * ```
 * public final class com.example.Foo extends java.lang.Object {  // Foo.java
 *     private int x;
 *     public void bar()   { x = 1; }                  // direct method
 *     public void print(String s) { print("hello"); } // virtual method, wrapped in try/catch
 * }
 * ```
 */
internal fun buildSampleDex(): ByteArray {
    val strings = listOf(
        "Lcom/example/Foo;", // 0
        "Ljava/lang/Object;", // 1
        "I", // 2
        "V", // 3
        "x", // 4
        "bar", // 5
        "Foo.java", // 6
        "hello", // 7
        "Ljava/lang/String;", // 8
        "print", // 9
    )
    // type_id -> string_id
    val types = listOf(0, 1, 2, 3, 8)
    val typeFoo = 0
    val typeObject = 1
    val typeInt = 2
    val typeVoid = 3
    val typeString = 4

    val headerSize = 0x70
    val stringIdsOff = headerSize
    val typeIdsOff = stringIdsOff + strings.size * 4
    val protoIdsOff = typeIdsOff + types.size * 4
    val fieldIdsOff = protoIdsOff + PROTO_COUNT * 12
    val methodIdsOff = fieldIdsOff + FIELD_COUNT * 8
    val classDefsOff = methodIdsOff + METHOD_COUNT * 8
    val dataOff = classDefsOff + CLASS_DEF_COUNT * 32
    require(dataOff % 4 == 0) { "data section must start 4-byte aligned, got $dataOff" }

    // --- data section: string data, then the type_list, class_data and code items.
    val data = DexBytes()
    fun dataPosition(): Int = dataOff + data.size

    val stringDataOffsets = strings.map { value ->
        val at = dataPosition()
        data.uleb128(value.length)
        data.bytes(value.encodeToByteArray())
        data.u1(0)
        at
    }

    // type_list for print(Ljava/lang/String;)V
    data.align(4)
    val printParamsOff = dataPosition()
    data.u4(1).u2(typeString).u2(0)

    // code_item for bar(): const/4 v0, #1 ; iput v0, p0, Foo->x:I ; return-void
    data.align(4)
    val barCodeOff = dataPosition()
    data.u2(2) // registers_size — v0 local, v1 = p0 (this)
        .u2(1) // ins_size
        .u2(0) // outs_size
        .u2(0) // tries_size
        .u4(0) // debug_info_off
        .u4(4) // insns_size, in code units
        .bytes(codeUnits(0x1012, 0x1059, 0x0000, 0x000E))

    // code_item for print(String): const-string v0, "hello" ; invoke-virtual {p0, v0} ; return-void
    data.align(4)
    val printCodeOff = dataPosition()
    data.u2(3) // registers_size — v0 local, v1 = p0 (this), v2 = p1 (arg)
        .u2(2) // ins_size
        .u2(2) // outs_size
        .u2(1) // tries_size
        .u4(0) // debug_info_off
        .u4(6) // insns_size
        .bytes(codeUnits(0x001A, 0x0007, 0x206E, 0x0001, 0x0001, 0x000E))
        // try_item: [0, 3) handled at handler-list offset 1
        .u4(0).u2(3).u2(1)
        // encoded_catch_handler_list: one handler, one typed catch plus a catch-all
        .uleb128(1)
        .sleb128(-1).uleb128(typeObject).uleb128(5).uleb128(5)

    // class_data_item: 1 instance field, 1 direct method, 1 virtual method
    val classDataOff = dataPosition()
    data.uleb128(0) // static_fields_size
        .uleb128(1) // instance_fields_size
        .uleb128(1) // direct_methods_size
        .uleb128(1) // virtual_methods_size
        .uleb128(0).uleb128(ACC_PRIVATE) // field x, index delta 0
        .uleb128(0).uleb128(ACC_PUBLIC).uleb128(barCodeOff) // method bar, index delta 0
        .uleb128(1).uleb128(ACC_PUBLIC).uleb128(printCodeOff) // method print, index delta 1

    val dataBytes = data.build()

    // --- id tables and class_defs.
    val tables = DexBytes()
    for (offset in stringDataOffsets) tables.u4(offset)
    for (stringIdx in types) tables.u4(stringIdx)
    // proto_id_item: shorty, return_type, parameters_off
    tables.u4(3).u4(typeVoid).u4(0) // ()V
    tables.u4(3).u4(typeVoid).u4(printParamsOff) // (Ljava/lang/String;)V
    // field_id_item: class, type, name
    tables.u2(typeFoo).u2(typeInt).u4(4)
    // method_id_item: class, proto, name
    tables.u2(typeFoo).u2(0).u4(5) // bar()V
    tables.u2(typeFoo).u2(1).u4(9) // print(Ljava/lang/String;)V
    // class_def_item
    tables.u4(typeFoo)
        .u4(ACC_PUBLIC or ACC_FINAL)
        .u4(typeObject)
        .u4(0) // interfaces_off
        .u4(6) // source_file_idx -> "Foo.java"
        .u4(0) // annotations_off
        .u4(classDataOff)
        .u4(0) // static_values_off
    val tableBytes = tables.build()

    val fileSize = dataOff + dataBytes.size

    val header = DexBytes()
        .bytes(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)) // "dex\n035\0"
        .u4(0) // checksum — not verified by the parser
        .bytes(ByteArray(20)) // signature
        .u4(fileSize)
        .u4(headerSize)
        .u4(0x12345678L) // endian_tag
        .u4(0).u4(0) // link_size, link_off
        .u4(0) // map_off
        .u4(strings.size).u4(stringIdsOff)
        .u4(types.size).u4(typeIdsOff)
        .u4(PROTO_COUNT).u4(protoIdsOff)
        .u4(FIELD_COUNT).u4(fieldIdsOff)
        .u4(METHOD_COUNT).u4(methodIdsOff)
        .u4(CLASS_DEF_COUNT).u4(classDefsOff)
        .u4(dataBytes.size).u4(dataOff)
        .build()
    require(header.size == headerSize) { "header is ${header.size} bytes, expected $headerSize" }

    return header + tableBytes + dataBytes
}

private const val PROTO_COUNT = 2
private const val FIELD_COUNT = 1
private const val METHOD_COUNT = 2
private const val CLASS_DEF_COUNT = 1

private const val ACC_PUBLIC = 0x0001
private const val ACC_PRIVATE = 0x0002
private const val ACC_FINAL = 0x0010

/** Little-endian `insns[]` bytes from 16-bit code units. */
private fun codeUnits(vararg units: Int): ByteArray {
    val out = ByteArray(units.size * 2)
    units.forEachIndexed { i, unit ->
        out[i * 2] = (unit and 0xFF).toByte()
        out[i * 2 + 1] = ((unit shr 8) and 0xFF).toByte()
    }
    return out
}

/** Little-endian byte builder, the DEX counterpart of the `javaBytecode` tests' `ClassFileBytes`. */
internal class DexBytes {
    private val out = ArrayList<Byte>()

    val size: Int get() = out.size

    fun u1(value: Int): DexBytes {
        out.add((value and 0xFF).toByte())
        return this
    }

    fun u2(value: Int): DexBytes {
        u1(value)
        u1(value shr 8)
        return this
    }

    fun u4(value: Long): DexBytes {
        u2((value and 0xFFFF).toInt())
        u2(((value shr 16) and 0xFFFF).toInt())
        return this
    }

    fun u4(value: Int): DexBytes = u4(value.toLong() and 0xFFFFFFFFL)

    fun uleb128(value: Int): DexBytes {
        var remaining = value
        do {
            val byte = remaining and 0x7F
            remaining = remaining ushr 7
            u1(if (remaining != 0) byte or 0x80 else byte)
        } while (remaining != 0)
        return this
    }

    fun sleb128(value: Int): DexBytes {
        var remaining = value
        while (true) {
            val byte = remaining and 0x7F
            val signBitSet = (byte and 0x40) != 0
            remaining = remaining shr 7
            val done = (remaining == 0 && !signBitSet) || (remaining == -1 && signBitSet)
            u1(if (done) byte else byte or 0x80)
            if (done) break
        }
        return this
    }

    fun bytes(values: ByteArray): DexBytes {
        out.addAll(values.toList())
        return this
    }

    /** Appends zero bytes until the length is a multiple of [alignment]. */
    fun align(alignment: Int): DexBytes {
        while (out.size % alignment != 0) u1(0)
        return this
    }

    fun build(): ByteArray = out.toByteArray()
}
