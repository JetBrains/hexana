package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import org.jetbrains.hexana.providers.pe.PeFile
import org.jetbrains.hexana.providers.pe.PeImageFile
import org.jetbrains.hexana.providers.pe.PeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The same fixture as the JVM fixture tests, on every target: metadata decoding, IL decoding and
 * token resolution lean on unsigned arithmetic and bit reinterpretation, which differ on JS.
 */
class ClrMultiplatformTest {
    private val buffer = SAMPLE_CLR_EXE.toCommonByteBuffer()
    private val clr = assertNotNull(assertIs<PeImageFile>(assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(buffer)).value).clr)

    @Test
    fun decodesMetadata() {
        assertEquals("sample-clr", clr.assembly?.name)
        assertEquals("Hexana.Sample.Program::Main", clr.entryPoint)
        assertEquals(".NETFramework,Version=v4.8", clr.targetFramework)
        assertEquals(8, clr.typeDefs.size)
        assertEquals("U Map<U>(Func<T, U> map)", clr.methods.single { it.name == "Map" }.signature)
        assertEquals("B77A5C561934E089", clr.assemblyRefs.single().publicKeyOrToken.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') })
    }

    @Test
    fun decodesIlAndResolvesTokens() {
        val describe = clr.methods.single { it.name == "Describe" }
        val listing = ClrIlDecoder.decode(buffer, describe.body!!.codeRange)
        assertNull(listing.truncatedAt)
        val byOffset = listing.instructions.associateBy { it.offset }
        assertEquals(ClrIlOperand.Switch(listOf(0x14, 0x1C, 0x24)), byOffset.getValue(0x01).operand)
        assertEquals(ClrIlOperand.Target(0x75), byOffset.getValue(0x1A).operand)
        val ldstr = assertIs<ClrIlOperand.Token>(byOffset.getValue(0x14).operand)
        assertEquals("zero", clr.tokens.describe(ldstr.token, describe))
        val call = assertIs<ClrIlOperand.Token>(byOffset.getValue(0x57).operand)
        assertEquals("string Box<int>::Map<string>(Func<!0, string>)", clr.tokens.describe(call.token, describe))
        assertEquals(listOf("string"), clr.tokens.localVariableTypes(describe))
        assertEquals(listOf(ClrExceptionClauseKind.Catch, ClrExceptionClauseKind.Finally), describe.body!!.exceptionClauses.map { it.kind })
    }
}
