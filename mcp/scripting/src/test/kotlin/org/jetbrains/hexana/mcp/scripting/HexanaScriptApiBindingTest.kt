package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryStringRecord
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.WasmBinaryWriter
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that the shared scripting surface is actually reachable through the
 * GraalJS binding — the shape itself is covered by `plugins-shared`, this checks
 * the JVM adapter and the sandbox boundary around it.
 */
class HexanaScriptApiBindingTest {

    private fun run(
        script: String,
        provider: org.jetbrains.hexana.providers.BinaryProvider = testProvider(),
        fileName: String? = null,
        onLine: ((String) -> Unit)? = null,
    ): HexanaScriptResult = HexanaScriptRunner().run(
        source = HexanaScriptSource.Inline(script),
        binaryProvider = provider,
        fileName = fileName,
        onLine = onLine,
    )

    private fun lines(script: String, provider: org.jetbrains.hexana.providers.BinaryProvider = testProvider()): List<String> {
        val result = run(script, provider)
        assertEquals(
            HexanaScriptStatus.SUCCESS,
            result.status,
            "script failed: ${result.diagnostics.map { it.message }}",
        )
        return result.output.lines
    }

    // -- identity ------------------------------------------------------------

    @Test
    fun curatedIdentityMembersAreBound() {
        val lines = lines(
            """
            print(binary.format);
            print(binary.formatName);
            print(binary.size);
            print(binary.name);
            """.trimIndent()
        )

        assertEquals(listOf("binary", "Binary", "5", "null"), lines)
    }

    @Test
    fun fileNameIsSurfacedAsBinaryName() {
        val result = HexanaScriptRunner().run(
            source = HexanaScriptSource.Inline("print(binary.name);"),
            binaryProvider = testProvider(),
            fileName = "sample.bin",
        )

        assertEquals(listOf("sample.bin"), result.output.lines)
    }

    // -- bytes ---------------------------------------------------------------

    @Test
    fun byteHelpersAreBound() {
        val lines = lines(
            """
            print(binary.bytes.at(1));
            print(binary.bytes.hex(0, 3));
            print(binary.bytes.ascii(0, 5));
            print(binary.bytes.u16(1));
            print(binary.bytes.indexOf("bc"));
            print(binary.bytes.slice(1, 2).join("-"));
            """.trimIndent()
        )

        assertEquals(
            listOf("97", "00 61 62", ".abcd", 0x6261.toString(), "2", "97-98"),
            lines,
        )
    }

    @Test
    fun readsOutsideTheBinaryBecomeScriptErrorsThatNameTheRange() {
        val result = run("print(binary.bytes.at(999));")

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        val message = result.diagnostics.single().message
        assertTrue(message.contains("outside the binary"), message)
        assertTrue(message.contains("0..4"), message)
    }

    // -- output globals ------------------------------------------------------

    @Test
    fun tableAndRowGlobalsEmitTabSeparatedOutput() {
        val lines = lines(
            """
            table("Name", "Size");
            row("code", 1024);
            row("data", 64);
            """.trimIndent()
        )

        assertEquals(listOf("Name\tSize", "code\t1024", "data\t64"), lines)
    }

    @Test
    fun rowBeforeTableFailsWithAnActionableMessage() {
        val result = run("row(1, 2);")

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertTrue(result.diagnostics.single().message.contains("table("), result.diagnostics.single().message)
    }

    @Test
    fun printAcceptsMultipleArgumentsAndKeepsIntegersIntegral() {
        assertEquals(listOf("a 1 true null", "5"), lines("""print("a", 1, true, null); print(binary.bytes.size);"""))
    }

    // -- structure, strings, size -------------------------------------------

    @Test
    fun structureStringsAndSizeAnalysisAreBound() {
        val lines = lines(
            """
            print(binary.strings.length + " " + binary.strings[0].value + "@" + binary.strings[0].offset);
            print(binary.sizeAnalysis.name + ":" + binary.sizeAnalysis.children[0].size);
            print(binary.structure.length);
            """.trimIndent()
        )

        assertEquals(listOf("1 hello@1", "root:2", "0"), lines)
    }

    // -- tabs ----------------------------------------------------------------

    @Test
    fun tabsExposeTheTablesTheProductRenders() {
        val lines = lines(
            """
            print(binary.tabs.indexOf("Exports") >= 0);
            const t = binary.tab("Exports");
            print(t.rowCount);
            print(t.columns.join(","));
            print(t.cell(0, t.columnIndex("Name")));
            print(t.row(0).length === t.columns.length);
            """.trimIndent(),
            provider = wasmProvider(),
        )

        assertEquals("true", lines[0])
        assertEquals("1", lines[1])
        assertTrue(lines[2].contains("Name"), lines[2])
        assertEquals("hello", lines[3])
        assertEquals("true", lines[4])
    }

    @Test
    fun unknownTabNameListsWhatIsAvailable() {
        val result = run("""binary.tab("Nope");""", provider = wasmProvider())

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        val message = result.diagnostics.single().message
        assertTrue(message.contains("no such tab"), message)
        assertTrue(message.contains("Exports"), message)
    }

    // -- escape hatch and back-compat ---------------------------------------

    @Test
    fun rawExposesProviderPropertiesReflectively() {
        val lines = lines("""print(binary.raw.strings[0].value); print(binary.raw.strings[0].size);""")

        assertEquals(listOf("hello", "5"), lines)
    }

    @Test
    fun uncuratedProviderMembersStillResolveOnBinary() {
        // `structureRanges` is a provider property with no curated equivalent
        // under that name, so it must fall through. This is what keeps scripts
        // written against the pre-v1 surface working.
        assertEquals(listOf("0"), lines("print(binary.structureRanges.length);"))
    }

    @Test
    fun rawExposesDataButNotBehavior() {
        val lines = lines(
            """
            print(typeof binary.raw.getClass);
            print(typeof binary.raw.wait);
            print(typeof binary.raw.strings);
            """.trimIndent()
        )

        assertEquals(listOf("undefined", "undefined", "object"), lines)
    }

    @Test
    fun writesToTheBinarySurfaceAreIgnoredRatherThanFailing() {
        val lines = lines(
            """
            binary.format = "hacked";
            print(binary.format);
            """.trimIndent()
        )

        assertEquals(listOf("binary"), lines)
    }

    // -- streaming -----------------------------------------------------------

    @Test
    fun outputIsStreamedToTheCallbackAsItIsProduced() {
        val streamed = Collections.synchronizedList(mutableListOf<String>())
        val result = run("""print("a"); print("b"); print("c");""", onLine = streamed::add)

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("a", "b", "c"), streamed.toList())
        assertEquals(result.output.lines, streamed.toList())
    }

    @Test
    fun streamedOutputRespectsTheConfiguredLimits() {
        val streamed = Collections.synchronizedList(mutableListOf<String>())
        val result = HexanaScriptRunner(limits = HexanaScriptLimits(maxOutputLines = 2)).run(
            source = HexanaScriptSource.Inline("""print("a"); print("b"); print("c");"""),
            binaryProvider = testProvider(),
            onLine = streamed::add,
        )

        assertTrue(result.output.truncated)
        assertEquals(listOf("a", "b"), streamed.toList())
    }

    @Test
    fun interactiveLimitsAllowLongerRunsThanTheDefault() {
        val interactive = HexanaScriptLimits.interactive()

        assertTrue(interactive.timeoutMillis > HexanaScriptLimits().timeoutMillis)
        assertTrue(interactive.maxOutputLines > HexanaScriptLimits().maxOutputLines)

        val result = HexanaScriptRunner(limits = interactive).run(
            source = HexanaScriptSource.Inline("for (let i = 0; i < 5000; i++) print(i);"),
            binaryProvider = testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertFalse(result.output.truncated)
        assertEquals(5000, result.output.lines.size)
    }

    // -- fixtures ------------------------------------------------------------

    private fun testProvider(): GenericBinaryProvider = TestBinaryProvider()

    private class TestBinaryProvider : GenericBinaryProvider(byteArrayOf(0, 97, 98, 99, 100).toCommonByteBuffer()) {
        override val strings: List<BinaryStringRecord> =
            listOf(BinaryStringRecord(value = "hello", offset = 1, size = 5, index = 0))

        override val sizeAnalysis: BinarySizeNode =
            BinarySizeNode(
                name = "root",
                size = bytes.size,
                offset = 0,
                children = listOf(BinarySizeNode(name = "child", size = 2, offset = 1)),
            )
    }

    /** Minimal core Wasm module exporting a single function named `hello`. */
    private fun wasmProvider(): WasmBinaryProvider {
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

        return WasmBinaryProvider(writer.toByteArray().toCommonByteBuffer())
    }
}
