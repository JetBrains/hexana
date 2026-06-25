package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BinaryMcpToolsFixtureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val service = DefaultHexanaAnalysisService()

    @Test
    fun summarizeBinaryCoversWasmComponentNativeFixturesAndGenericBytes() {
        val wasm = createBinaryFile(
            "core.wasm",
            wat2wasm(
                """
                (module
                  (import "env" "log" (func))
                  (func (export "main")
                    call 0))
                """.trimIndent()
            )
        )
        val component = createBinaryFile("component.wasm", componentWithCoreModule(wat2wasm("(module (func))")))

        val wasmSummary = service.summarizeBinary(BinaryInput.FilePath(wasm.toString()))
        val componentSummary = service.summarizeBinary(BinaryInput.FilePath(component.toString()))
        val elfSummary = service.summarizeBinary(BinaryInput.FilePath(nativeFixture("elf/sample.o").toString()))
        val machOSummary = service.summarizeBinary(BinaryInput.FilePath(nativeFixture("macho/sample-x86_64.o").toString()))
        val peSummary = service.summarizeBinary(BinaryInput.FilePath(nativeFixture("pe/sample.exe").toString()))
        val coffSummary = service.summarizeBinary(BinaryInput.FilePath(nativeFixture("pe/sample.coff").toString()))
        val archiveSummary = service.summarizeBinary(BinaryInput.FilePath(nativeFixture("pe/sample.lib").toString()))
        val genericSummary = service.summarizeBinary(BinaryInput.RawBytes(byteArrayOf(1, 2, 3, 4)))

        assertEquals("wasm", wasmSummary.module.format)
        assertEquals(1, wasmSummary.counts.functions)
        assertEquals(1, wasmSummary.counts.imports)
        assertEquals(1, wasmSummary.counts.exports)

        assertEquals("wasm_component", componentSummary.module.format)
        assertEquals(1, componentSummary.counts.coreModules)
        assertEquals(1, componentSummary.counts.sections)

        assertEquals("elf", elfSummary.module.format)
        assertEquals(24, elfSummary.counts.sections)
        assertTrue(elfSummary.counts.symbols!! > 0)
        assertTrue(elfSummary.counts.functions!! > 0)

        assertEquals("mach_o", machOSummary.module.format)
        assertTrue(machOSummary.counts.sections!! > 0)
        assertTrue(machOSummary.counts.symbols!! > 0)
        assertTrue(machOSummary.counts.functions!! > 0)

        assertEquals("pe", peSummary.module.format)
        assertEquals(5, peSummary.counts.sections)
        assertNotNull(peSummary.counts.exports)

        assertEquals("coff", coffSummary.module.format)
        assertEquals(10, coffSummary.counts.sections)
        assertTrue(coffSummary.counts.symbols!! > 0)
        assertTrue(coffSummary.counts.functions!! > 0)

        assertEquals("archive", archiveSummary.module.format)
        assertEquals(6, archiveSummary.counts.archiveMembers)

        assertEquals("generic", genericSummary.module.format)
        assertEquals(4, genericSummary.module.byteLength)
    }

    @Test
    fun queryBinaryExtractsNativeFixtureEntitiesWithoutDisassembly() {
        val elfSections = service.queryBinary(
            input = BinaryInput.FilePath(nativeFixture("elf/sample.o").toString()),
            operation = "sections",
            query = listOf(".debug_info"),
            offset = 0,
            limit = 10,
            detail = "evidence",
            maxBytes = 2_000,
        )
        val machOSymbols = service.queryBinary(
            input = BinaryInput.FilePath(nativeFixture("macho/sample-x86_64.o").toString()),
            operation = "symbols",
            query = listOf("_hexana_add"),
            offset = 0,
            limit = 10,
            detail = "evidence",
            maxBytes = 2_000,
        )
        val peDebug = service.queryBinary(
            input = BinaryInput.FilePath(nativeFixture("pe/sample.exe").toString()),
            operation = "debug",
            query = listOf("sample.pdb"),
            offset = 0,
            limit = 10,
            detail = "evidence",
            maxBytes = 2_000,
        )
        val coffSymbols = service.queryBinary(
            input = BinaryInput.FilePath(nativeFixture("pe/sample.coff").toString()),
            operation = "symbols",
            query = listOf("hexana_dispatch"),
            offset = 0,
            limit = 10,
            detail = "evidence",
            maxBytes = 2_000,
        )
        val archiveMembers = service.queryBinary(
            input = BinaryInput.FilePath(nativeFixture("pe/sample.lib").toString()),
            operation = "archive_members",
            query = listOf("sample.exe"),
            offset = 0,
            limit = 10,
            detail = "evidence",
            maxBytes = 2_000,
        )

        assertTrue(elfSections.records.all { it.kind == "section" })
        assertTrue(elfSections.records.any { it.name == ".debug_info" })
        assertTrue(elfSections.records.all { it.evidence.isNotEmpty() })

        assertTrue(machOSymbols.records.all { it.kind == "symbol" })
        assertTrue(machOSymbols.records.any { it.name == "_hexana_add" })

        assertTrue(peDebug.records.all { it.kind == "debug" })
        assertTrue(peDebug.records.any { it.name?.endsWith("sample.pdb") == true })

        assertEquals("symbol", coffSymbols.records.single().kind)
        assertEquals("hexana_dispatch", coffSymbols.records.single().name)

        assertEquals(5, archiveMembers.total)
        assertTrue(archiveMembers.records.all { it.kind == "archive_member" })
        assertTrue(archiveMembers.records.all { it.name == "sample.exe" })
    }

    @Test
    fun queryBinaryAndFocusedFunctionToolsReportPaginationAndOmissions() {
        val symbolsPage = service.queryBinary(
            input = BinaryInput.FilePath(nativeFixture("pe/sample.coff").toString()),
            operation = "symbols",
            query = emptyList(),
            offset = 1,
            limit = 2,
            detail = "summary",
            maxBytes = 2_000,
        )
        val wasm = createBinaryFile(
            "functions.wasm",
            wat2wasm(
                """
                (module
                  (import "env" "log" (func))
                  (func (export "first"))
                  (func (export "second")))
                """.trimIndent()
            )
        )
        val wasmFunctions = service.listBinaryFunctions(
            input = BinaryInput.FilePath(wasm.toString()),
            offset = 1,
            limit = 1,
        )
        val nativeSearch = service.searchBinaryFunctions(
            input = BinaryInput.FilePath(nativeFixture("pe/sample.coff").toString()),
            query = listOf("hexana_add"),
            offset = 0,
            limit = 10,
        )

        assertTrue(symbolsPage.total > symbolsPage.returned)
        assertEquals(2, symbolsPage.returned)
        assertEquals(3, symbolsPage.nextOffset)
        assertEquals(1, symbolsPage.omitted.single { it.reason.contains("before requested") }.count)
        assertTrue(symbolsPage.omitted.any { it.reason == "outside `limit` bounds" })
        assertTrue(symbolsPage.records.all { it.location == null })
        assertTrue(symbolsPage.records.all { it.evidence.isEmpty() })

        assertEquals(3, wasmFunctions.total)
        assertEquals(1, wasmFunctions.returned)
        assertEquals(2, wasmFunctions.nextOffset)
        assertEquals("code", wasmFunctions.functions.single().source)
        assertEquals(1, wasmFunctions.omitted.single { it.reason.contains("before requested") }.count)

        assertTrue(nativeSearch.total >= 1)
        assertTrue(nativeSearch.functions.any { it.name == "hexana_add" })
        assertTrue(nativeSearch.functions.all { it.source.isNotBlank() })
    }

    private fun createBinaryFile(name: String, bytes: ByteArray): Path =
        temporaryFolder.newFile(name).toPath().also { Files.write(it, bytes) }

    private fun componentWithCoreModule(coreModule: ByteArray): ByteArray =
        componentHeader() + componentSection(1, coreModule)

    private fun componentHeader(): ByteArray =
        byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x0D, 0x00, 0x01, 0x00)

    private fun componentSection(id: Int, payload: ByteArray): ByteArray =
        byteArrayOf(id.toByte()) + encodeU32(payload.size) + payload

    private fun encodeU32(value: Int): ByteArray {
        require(value >= 0)
        val result = mutableListOf<Byte>()
        var remaining = value
        do {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) byte = byte or 0x80
            result += byte.toByte()
        } while (remaining != 0)
        return result.toByteArray()
    }

    private fun nativeFixture(relativePath: String): Path =
        nativeFixtureRoot().resolve(relativePath).also {
            require(Files.isRegularFile(it)) { "Missing native fixture $it" }
            require(it.readBytes().isNotEmpty()) { "Empty native fixture $it" }
        }

    private fun nativeFixtureRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            val candidate = current.resolve("parsing/native-fixtures/test-data")
            if (Files.isDirectory(candidate)) return candidate
            current = current.parent
        }
        error("Cannot find parsing/native-fixtures/test-data from ${System.getProperty("user.dir")}")
    }
}
