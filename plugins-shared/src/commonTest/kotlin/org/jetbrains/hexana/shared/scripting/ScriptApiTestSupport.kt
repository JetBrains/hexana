package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.WasmBinaryWriter
import kotlin.test.assertTrue

/** Fixtures and [ScriptValue] navigation helpers shared by the scripting tests. */
internal object ScriptApiTestSupport {

    /** A minimal core Wasm module with type, function, export and code sections. */
    fun wasmProvider(): WasmBinaryProvider = WasmBinaryProvider(wasmBytes().toCommonByteBuffer())

    fun wasmBytes(): ByteArray {
        val writer = WasmBinaryWriter()
        writer.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        writer.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))

        val types = WasmBinaryWriter()
        types.writeULEB128(1)
        types.writeByte(0x60)
        types.writeULEB128(0)
        types.writeULEB128(0)
        writer.writeSection(1, types.toByteArray())

        val functions = WasmBinaryWriter()
        functions.writeULEB128(1)
        functions.writeULEB128(0)
        writer.writeSection(3, functions.toByteArray())

        val exports = WasmBinaryWriter()
        exports.writeULEB128(1)
        exports.writeName("hello")
        exports.writeByte(0x00)
        exports.writeULEB128(0)
        writer.writeSection(7, exports.toByteArray())

        val code = WasmBinaryWriter()
        code.writeULEB128(1)
        val body = WasmBinaryWriter()
        body.writeULEB128(0)
        body.writeByte(0x0B)
        code.writeULEB128(body.size())
        code.writeBytes(body.toByteArray())
        writer.writeSection(10, code.toByteArray())

        return writer.toByteArray()
    }

    /**
     * An ELF provider over plain ASCII bytes. The header does not parse, which is
     * exactly what we want: `strings` still scans, so the tests can cover the
     * string shape without carrying a binary fixture into this module.
     */
    fun elfProviderWithStrings(): ElfBinaryProvider = ElfBinaryProvider(
        "hexana scripting fixture".encodeToByteArray().toCommonByteBuffer(),
        NativeBinaryFormat.Elf(
            wordSize = NativeBinaryWordSize.Bits64,
            endian = NativeBinaryEndian.Little,
            fileType = null,
            machine = null,
        ),
    )

    fun genericProvider(bytes: ByteArray): GenericBinaryProvider =
        GenericBinaryProvider(bytes.toCommonByteBuffer())

    fun api(provider: BinaryProvider, fileName: String? = null): ScriptValue.Obj =
        scriptBinaryApi(provider, fileName)

    // -- ScriptValue navigation ---------------------------------------------

    fun obj(value: ScriptValue): ScriptValue.Obj =
        value as? ScriptValue.Obj ?: error("expected an object, got ${value::class.simpleName}")

    fun arr(value: ScriptValue): ScriptValue.Arr =
        value as? ScriptValue.Arr ?: error("expected an array, got ${value::class.simpleName}")

    fun member(value: ScriptValue, name: String): ScriptValue {
        val target = obj(value)
        val factory = target.members[name]
            ?: error("no member '$name'; available: ${target.members.keys.joinToString(", ")}")
        return factory()
    }

    fun call(value: ScriptValue, vararg arguments: ScriptValue): ScriptValue {
        val function = value as? ScriptValue.Fn ?: error("expected a function, got ${value::class.simpleName}")
        return function.invoke(arguments.toList())
    }

    fun callMember(value: ScriptValue, name: String, vararg arguments: ScriptValue): ScriptValue =
        call(member(value, name), *arguments)

    fun string(value: ScriptValue): String =
        (value as? ScriptValue.Str)?.value ?: error("expected a string, got ${value::class.simpleName}")

    fun number(value: ScriptValue): Number =
        (value as? ScriptValue.Num)?.value ?: error("expected a number, got ${value::class.simpleName}")

    fun int(value: ScriptValue): Int = number(value).toInt()

    fun stringList(value: ScriptValue): List<String> {
        val array = arr(value)
        return (0 until array.size).map { string(array.at(it)) }
    }

    fun num(value: Int): ScriptValue = ScriptValue.Num(value)

    fun str(value: String): ScriptValue = ScriptValue.Str(value)

    fun assertNonEmpty(value: ScriptValue, what: String): ScriptValue.Arr {
        val array = arr(value)
        assertTrue(array.size > 0, "expected $what to be non-empty")
        return array
    }
}
