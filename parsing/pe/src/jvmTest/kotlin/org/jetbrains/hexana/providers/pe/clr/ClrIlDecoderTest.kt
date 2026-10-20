package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import org.jetbrains.hexana.providers.pe.PeFile
import org.jetbrains.hexana.providers.pe.PeImageFile
import org.jetbrains.hexana.providers.pe.PeParser
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** IL of `sample-clr.exe`, cross-checked against `ikdasm` output for the same file. */
class ClrIlDecoderTest {
    private val bytes = Path.of(System.getProperty("user.dir"))
        .resolve("../native-fixtures/test-data/pe/sample-clr.exe")
        .normalize()
        .readBytes()
    private val buffer = bytes.toCommonByteBuffer()
    private val clr = assertNotNull(assertIs<PeImageFile>(assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(buffer)).value).clr)

    private fun method(name: String) = clr.methods.single { it.name == name }

    private fun listing(name: String): List<ClrIlInstruction> {
        val result = ClrIlDecoder.decode(buffer, method(name).body!!.codeRange)
        assertNull(result.truncatedAt)
        return result.instructions
    }

    private fun ClrIlInstruction.describe(method: ClrMethodDef): String? =
        (operand as? ClrIlOperand.Token)?.let { clr.tokens.describe(it.token, method) }

    @Test
    fun `decodes Main with resolved call targets`() {
        val main = method("Main")
        val instructions = listing("Main")
        assertEquals(
            listOf(0x00, 0x05, 0x06, 0x0B, 0x10, 0x11, 0x16, 0x1B, 0x1C, 0x21, 0x26, 0x27, 0x28, 0x2D, 0x32, 0x37, 0x38, 0x3D),
            instructions.map { it.offset },
        )
        assertEquals("newobj dup ldstr callvirt dup ldstr callvirt stloc.0 ldstr newobj ldloc.0 ldc.i4.0 callvirt callvirt call ldloc.0 callvirt ret",
            instructions.joinToString(" ") { it.opcode!!.mnemonic })
        assertEquals("void List<string>::.ctor()", instructions[0].describe(main))
        assertEquals("a", instructions[2].describe(main))
        assertEquals("void List<string>::Add(!0)", instructions[3].describe(main))
        assertEquals("void Hexana.Sample.Greeter::.ctor(string)", instructions[9].describe(main))
        assertEquals("string Hexana.Sample.Greeter::Greet(string)", instructions[13].describe(main))
        assertEquals("void System.Console::WriteLine(string)", instructions[14].describe(main))
        assertEquals(listOf("List<string>"), clr.tokens.localVariableTypes(main))

        val last = instructions.last()
        assertEquals(NativeByteRange(main.body!!.codeRange.offset + 0x3D, 1), last.range)
    }

    @Test
    fun `decodes switch, branches, generic instantiations and exception clauses`() {
        val describe = method("Describe")
        val instructions = listing("Describe").associateBy { it.offset }
        assertEquals(ClrIlOperand.Switch(listOf(0x14, 0x1C, 0x24)), instructions.getValue(0x01).operand)
        assertEquals(ClrIlOperand.Target(0x2C), instructions.getValue(0x12).operand)
        assertEquals(ClrIlOperand.Target(0x75), instructions.getValue(0x1A).operand)
        assertEquals("leave.s", instructions.getValue(0x1A).opcode?.mnemonic)
        assertEquals("!0 Box<int>::Value", instructions.getValue(0x33).describe(describe))
        assertEquals("string Hexana.Sample.Program+<>c::<Describe>b__1_0(int)", instructions.getValue(0x46).describe(describe))
        assertEquals("ldftn", instructions.getValue(0x46).opcode?.mnemonic)
        assertEquals("string Box<int>::Map<string>(Func<!0, string>)", instructions.getValue(0x57).describe(describe))
        assertEquals("int Hexana.Sample.Greeter::Counter", instructions.getValue(0x68).describe(describe))
        assertEquals(listOf("string"), clr.tokens.localVariableTypes(describe))

        assertEquals(
            listOf(
                ClrExceptionClause(ClrExceptionClauseKind.Catch, 0x00, 0x5F, 0x5F, 0x09, "System.FormatException", null),
                ClrExceptionClause(ClrExceptionClauseKind.Finally, 0x00, 0x68, 0x68, 0x0D, null, null),
            ),
            describe.body!!.exceptionClauses,
        )
    }

    @Test
    fun `decodes every method body in the fixture completely`() {
        for (method in clr.methods) {
            val body = method.body ?: continue
            val result = ClrIlDecoder.decode(buffer, body.codeRange)
            assertNull(result.truncatedAt, method.name)
            assertEquals(body.codeSize, result.instructions.sumOf { it.range.size }, method.name)
            result.instructions.forEach { assertNotNull(it.opcode, "${method.name} @ ${it.offset}") }
        }
    }

    @Test
    fun `stops at a truncated operand instead of reading past the code`() {
        val body = method("Main").body!!
        // Cut the code inside the first instruction's 4-byte method token.
        val result = ClrIlDecoder.decode(buffer, NativeByteRange(body.codeRange.offset, 3))
        assertEquals(emptyList(), result.instructions)
        assertEquals(0, result.truncatedAt)
    }
}
