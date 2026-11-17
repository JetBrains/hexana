package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.providers.BinaryStringRecord
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.WasmBinaryWriter
import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import org.jetbrains.hexana.shared.scripting.ScriptOutputBlock
import org.jetbrains.hexana.shared.scripting.parseScriptOutputBlocks
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
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

    // -- location ------------------------------------------------------------

    @Test
    fun pathAndDirAreBoundFromTheHostSuppliedPath() {
        val result = HexanaScriptRunner().run(
            source = HexanaScriptSource.Inline("print(binary.name, binary.path, binary.dir);"),
            binaryProvider = testProvider(),
            fileName = "sample.bin",
            filePath = "/home/me/build/sample.bin",
        )

        assertEquals(listOf("sample.bin /home/me/build/sample.bin /home/me/build"), result.output.lines)
    }

    @Test
    fun pathAndDirReadAsNullForABinaryWithNoFile() {
        // The in-memory case: a ZIP entry or a module opened from a table. A
        // script has to be able to tell there is nowhere to look.
        assertEquals(
            listOf("null null"),
            lines("print(binary.path, binary.dir);"),
        )
    }

    @Test
    fun loadingFromAPathReportsTheResolvedLocation() {
        // What the CLI and the MCP tool do. The resolved path is what a script
        // can build on: `--binary ./app.bin` must still yield a usable `dir`.
        val binary = tempFileWith("hexana-located-", ".bin", byteArrayOf(1, 2, 3))
        val expected = binary.toRealPath()

        val result = HexanaScriptRunner().run(
            BinaryInput.FilePath(binary.toString()),
            HexanaScriptSource.Inline("print(binary.name); print(binary.path); print(binary.dir);"),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(
            listOf(expected.fileName.toString(), expected.toString(), expected.parent.toString()),
            result.output.lines,
        )
    }

    @Test
    fun aRelativeBinaryPathStillYieldsAnAbsoluteDir() {
        val binary = tempFileWith("hexana-relative-", ".bin", byteArrayOf(4, 5))
        val viaDots = binary.parent.resolve("..").resolve(binary.parent.fileName).resolve(binary.fileName)

        val result = HexanaScriptRunner().run(
            BinaryInput.FilePath(viaDots.toString()),
            HexanaScriptSource.Inline("print(binary.dir);"),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf(binary.toRealPath().parent.toString()), result.output.lines)
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

    @Test
    fun narratedOutputSegmentsIntoTextAndTableBlocks() {
        // The reported bug: a script that prints a heading before its table had
        // the heading and the tab-separated rows jumbled into one column, because
        // segmentation looked only at the first two lines.
        val result = run(
            """
            print("Regions");
            print("");
            table("Name", "Size");
            row("code", 1024);
            row("data", 64);
            print("");
            print("done");
            """.trimIndent()
        )
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)

        val blocks = parseScriptOutputBlocks(result.output.lines)
        assertEquals(3, blocks.size, "expected text, table, text — got ${blocks.map { it::class.simpleName }}")

        assertEquals(listOf("Regions", ""), (blocks[0] as ScriptOutputBlock.Text).lines)

        val table = blocks[1] as ScriptOutputBlock.Table
        assertEquals(listOf("Name", "Size"), table.columns)
        assertEquals(listOf(listOf("code", "1024"), listOf("data", "64")), table.rows)

        assertEquals(listOf("", "done"), (blocks[2] as ScriptOutputBlock.Text).lines)
    }

    @Test
    fun theStarterTemplateProducesTextFollowedByATable() {
        // The template ships with the plugins and is the first thing most users
        // run, so its output has to land in the layout the tab expects.
        val result = HexanaScriptRunner().run(
            source = HexanaScriptSource.Inline(ScriptApiReference.starterTemplate()),
            binaryProvider = wasmProvider(),
            fileName = "fixture.wasm",
        )
        assertEquals(
            HexanaScriptStatus.SUCCESS,
            result.status,
            "starter template failed: ${result.diagnostics.map { it.message }}",
        )

        val blocks = parseScriptOutputBlocks(result.output.lines)
        assertTrue(blocks.size >= 2, "expected several blocks, got ${blocks.size}")
        assertTrue(blocks.first() is ScriptOutputBlock.Text, "the template starts by narrating")
        val table = blocks.filterIsInstance<ScriptOutputBlock.Table>().singleOrNull()
        assertTrue(table != null, "the template's region table must be recognised as a table")
        assertEquals(listOf("Region", "Offset", "Size", "% of file"), table.columns)
        assertTrue(table.rows.isNotEmpty(), "the fixture has sections, so the table has rows")
    }

    @Test
    fun pureTableOutputStaysASingleTableBlock() {
        // The common case must not regress into a document layout.
        val result = run(
            """
            table("Name", "Size");
            row("code", 1024);
            """.trimIndent()
        )

        val blocks = parseScriptOutputBlocks(result.output.lines)
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is ScriptOutputBlock.Table)
    }

    @Test
    fun plainTextOutputIsNotTurnedIntoATable() {
        // Previously this rendered as a one-column table with an ID column.
        val result = run("""print("just"); print("some"); print("text");""")

        val blocks = parseScriptOutputBlocks(result.output.lines)
        assertEquals(1, blocks.size)
        assertEquals(listOf("just", "some", "text"), (blocks.single() as ScriptOutputBlock.Text).lines)
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

    // -- open ----------------------------------------------------------------

    @Test
    fun openReadsASecondBinaryThroughTheSameSurface() {
        val other = tempFileWith("hexana-open-", ".wasm", wasmBytes())

        val lines = lines(
            """
            const other = open("${escaped(other)}");
            print(other.format, other.name, other.size);
            const exports = other.tab("Exports");
            print(exports.cell(0, exports.columnIndex("Name")));
            print(binary.size, other.size !== binary.size);
            """.trimIndent()
        )

        assertEquals("wasm ${other.fileName} ${Files.size(other)}", lines[0])
        assertEquals("hello", lines[1])
        assertEquals("5 true", lines[2])
    }

    @Test
    fun openedBinariesAreReusedRatherThanReparsed() {
        // A script comparing in a loop must not pay for a parse per iteration,
        // and repeating a path must not count against the open limit.
        val other = tempFileWith("hexana-open-reuse-", ".bin", byteArrayOf(1, 2, 3, 4))

        val lines = lines(
            """
            let total = 0;
            for (let i = 0; i < 40; i++) total += open("${escaped(other)}").size;
            print(total);
            """.trimIndent()
        )

        assertEquals(listOf("160"), lines)
    }

    @Test
    fun openingMoreBinariesThanTheLimitAllowsFailsWithTheLimitInTheMessage() {
        val opens = (0 until 3).joinToString("\n") { index ->
            val file = tempFileWith("hexana-open-limit-$index-", ".bin", byteArrayOf(index.toByte()))
            """open("${escaped(file)}");"""
        }

        val result = HexanaScriptRunner(limits = HexanaScriptLimits(maxOpenedBinaries = 2)).run(
            source = HexanaScriptSource.Inline(opens),
            binaryProvider = testProvider(),
        )

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        val message = result.diagnostics.single().message
        assertTrue(message.contains("at most 2 additional"), message)
    }

    @Test
    fun anOpenedBinaryReportsItsOwnLocation() {
        // `open(...)` returns `binary`'s shape, so it has to answer the location
        // questions too — that is what makes walking from one file to its sibling
        // and then to that one's sibling work.
        val other = tempFileWith("hexana-open-located-", ".bin", byteArrayOf(1, 2))

        val lines = lines(
            """
            const other = open("${escaped(other)}");
            print(other.name);
            print(other.path);
            print(other.dir);
            """.trimIndent()
        )

        val expected = other.toRealPath()
        assertEquals(
            listOf(expected.fileName.toString(), expected.toString(), expected.parent.toString()),
            lines,
        )
    }

    @Test
    fun aSiblingCanBeOpenedByJoiningDir() {
        // The pattern this exists for: reach the file next to the open one
        // without the script knowing where either of them lives.
        val sibling = tempFileWith("hexana-sibling-", ".bin", byteArrayOf(7, 7, 7, 7))
        val main = tempFileWith("hexana-main-", ".bin", byteArrayOf(1))

        val result = HexanaScriptRunner().run(
            BinaryInput.FilePath(main.toString()),
            HexanaScriptSource.Inline(
                """print(open(binary.dir + "/" + "${sibling.fileName}").size);"""
            ),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, result.diagnostics.toString())
        assertEquals(listOf("4"), result.output.lines)
    }

    @Test
    fun openingAMissingFileIsAScriptErrorNamingThePath() {
        val result = run("""open("/no/such/hexana-open.bin");""")

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        val message = result.diagnostics.single().message
        assertTrue(message.contains("/no/such/hexana-open.bin"), message)
        assertTrue(message.contains("File not found"), message)
    }

    @Test
    fun openObeysTheAllowedRootPolicyThatGovernsEveryOtherPath() {
        // `open` must not reach further than the binary and script paths the host
        // already accepts — that is what keeps it safe on a restricted server.
        val allowedRoot = Files.createTempDirectory("hexana-open-allowed-")
        val outside = tempFileWith("hexana-open-outside-", ".bin", byteArrayOf(7, 7, 7))

        val result = HexanaScriptRunner(
            allowedFilePolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(allowedRoot.toString())),
        ).run(
            source = HexanaScriptSource.Inline("""print(open("${escaped(outside)}").size);"""),
            binaryProvider = testProvider(),
        )

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertTrue(result.output.lines.isEmpty(), "bytes outside the allowed roots reached the script")
        assertTrue(
            result.diagnostics.single().message.contains("outside allowed roots"),
            result.diagnostics.single().message,
        )
    }

    @Test
    fun openReadsAFileInsideAnAllowedRoot() {
        // The other half of the policy. On its own the denial above is also what
        // a check wired up backwards, or one that refused everything, would
        // produce — every other `open` test here runs with the policy that
        // allows all files, so nothing else exercises the allowing branch.
        val allowedRoot = Files.createTempDirectory("hexana-open-allowed-")
        val inside = Files.write(allowedRoot.resolve("inside.bin"), byteArrayOf(9, 9, 9, 9, 9))

        val result = HexanaScriptRunner(
            allowedFilePolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(allowedRoot.toString())),
        ).run(
            source = HexanaScriptSource.Inline("""print(open("${escaped(inside)}").size);"""),
            binaryProvider = testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, result.diagnostics.toString())
        assertEquals(listOf("5"), result.output.lines)
    }

    private fun tempFileWith(prefix: String, suffix: String, bytes: ByteArray): Path =
        Files.createTempFile(prefix, suffix).also { Files.write(it, bytes) }

    /** A path as a JavaScript string literal body, so Windows separators survive. */
    private fun escaped(path: Path): String =
        path.toString().replace("\\", "\\\\").replace("\"", "\\\"")

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

    private fun wasmProvider(): WasmBinaryProvider = WasmBinaryProvider(wasmBytes().toCommonByteBuffer())

    /** Minimal core Wasm module exporting a single function named `hello`. */
    private fun wasmBytes(): ByteArray {
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
}
