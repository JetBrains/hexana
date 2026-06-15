package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentAnalysisServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val service = DefaultHexanaAnalysisService()

    @Test
    fun listComponentSurfaceReturnsImportsExportsAndVersionParts() {
        val calculator = componentFixture("calculator.wasm")

        val result = service.listComponentSurface(WasmInput.FilePath(calculator.toString()))

        assertTrue(result.isComponent)
        assertTrue(result.imports.any { it.baseName.contains("component:adder/add") })
        assertTrue(result.imports.any { it.baseName.contains("component:subtractor/subtract") })
        assertTrue(result.exports.any { it.baseName.contains("component:calculator/calculate") })
        assertTrue(result.imports.all { !it.runtimeProvided })
        assertTrue(result.summary.nonRuntimeImportCount >= 2)
    }

    @Test
    fun listComponentSurfaceReportsCoreWasmAsNotComponent() {
        val core = temporaryFolder.newFile("core.wasm").toPath()
        Files.write(
            core,
            wat2wasm(
                """
                (module
                  (func (export "main")))
                """.trimIndent()
            )
        )

        val result = service.listComponentSurface(WasmInput.FilePath(core.toString()))

        assertFalse(result.isComponent)
        assertEquals(0, result.summary.importCount)
        assertTrue(result.imports.isEmpty())
        assertTrue(result.exports.isEmpty())
    }

    @Test
    fun resolveComponentDependenciesFindsProvidersAndUnresolvedImports() {
        val root = temporaryFolder.newFolder("project").toPath()
        val calculator = copyFixture("calculator.wasm", root.resolve("calculator.wasm"))
        copyFixture("add.component.wasm", root.resolve("deps/add.component.wasm"))

        val result = service.resolveComponentDependencies(
            input = WasmInput.FilePath(calculator.toString()),
            projectRoot = root.toString(),
            scanRoots = emptyList(),
        )

        assertTrue(result.directDependencies.any { it.importBaseName.contains("component:adder/add") })
        assertTrue(result.unresolvedImports.any { it.baseName.contains("component:subtractor/subtract") })
        assertTrue(result.summary.scannedFileCount >= 1)
        assertFalse(result.summary.truncated)
    }

    @Test
    fun resolveComponentDependenciesReportsAmbiguousProviders() {
        val root = temporaryFolder.newFolder("ambiguous").toPath()
        val calculator = copyFixture("calculator.wasm", root.resolve("calculator.wasm"))
        copyFixture("add.component.wasm", root.resolve("deps/a/add.component.wasm"))
        copyFixture("add.component.wasm", root.resolve("deps/b/add.component.wasm"))

        val result = service.resolveComponentDependencies(
            input = WasmInput.FilePath(calculator.toString()),
            projectRoot = root.toString(),
            scanRoots = emptyList(),
        )

        assertTrue(result.candidateConflicts.any { it.baseName.contains("component:adder/add") })
        assertTrue(result.summary.ambiguousCount >= 1)
    }

    @Test
    fun resolveComponentDependenciesReturnsCompleteDependencyAudit() {
        val root = temporaryFolder.newFolder("checkout").toPath()
        val checkout = writeComponent(
            root.resolve("dist/components/checkout.component.wasm"),
            imports = listOf(
                "acme:pricing/calculate@1.2.0",
                "acme:inventory/check@1.0.0",
                "wasi:cli/run@0.2.3",
            ),
            exports = listOf("acme:checkout/run@1.0.0"),
        )
        val pricing = writeComponent(
            root.resolve("components/pricing.component.wasm"),
            imports = listOf(
                "acme:tax/rates@1.0.0",
                "wasi:filesystem/types@0.2.0",
            ),
            exports = listOf("acme:pricing/calculate@1.2.0"),
        )
        val tax = writeComponent(
            root.resolve("components/tax.component.wasm"),
            imports = emptyList(),
            exports = listOf("acme:tax/rates@1.0.0"),
        )
        writeComponent(
            root.resolve("third_party/legacy_pricing.component.wasm"),
            imports = emptyList(),
            exports = listOf("acme:pricing/calculate@1.1.0"),
        )
        writeComponent(
            root.resolve("components/pricing_preview.component.wasm"),
            imports = emptyList(),
            exports = listOf("acme:pricing/quote@1.2.0"),
        )
        writeComponent(
            root.resolve("components/tax_v2.component.wasm"),
            imports = emptyList(),
            exports = listOf("acme:tax/rates@2.0.0"),
        )
        Files.createDirectories(root.resolve("tools/wasm"))
        Files.write(root.resolve("tools/wasm/metrics.wasm"), byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00))

        val result = service.resolveComponentDependencies(
            input = WasmInput.FilePath(checkout.toString()),
            projectRoot = root.toString(),
            scanRoots = emptyList(),
        )

        assertTrue(result.requiredNonRuntimeImports.any {
            it.importName == "acme:pricing/calculate@1.2.0" && it.classification == "direct"
        })
        assertTrue(result.requiredNonRuntimeImports.any {
            it.importName == "acme:tax/rates@1.0.0" && it.classification == "transitive"
        })
        assertTrue(result.ignoredRuntimeImports.any {
            it.importName == "wasi:cli/run@0.2.3" && it.classification == "direct"
        })
        assertTrue(result.ignoredRuntimeImports.any {
            it.importName == "wasi:filesystem/types@0.2.0" && it.classification == "transitive"
        })
        assertTrue(result.resolvedDependencyPaths.any {
            it.importName == "acme:pricing/calculate@1.2.0" &&
                it.dependencyPath == pricing.toString() &&
                it.classification == "direct"
        })
        assertTrue(result.resolvedDependencyPaths.any {
            it.importName == "acme:tax/rates@1.0.0" &&
                it.dependencyPath == tax.toString() &&
                it.classification == "transitive"
        })
        assertTrue(result.unresolvedImports.any { it.importName == "acme:inventory/check@1.0.0" })
        assertTrue(result.rejectedCandidates.any {
            it.candidatePath.endsWith("legacy_pricing.component.wasm") &&
                it.reason.contains("required version is 1.2.0")
        })
        assertTrue(result.rejectedCandidates.any {
            it.candidatePath.endsWith("pricing_preview.component.wasm") &&
                it.reason.contains("does not match any required import")
        })
        assertTrue(result.rejectedCandidates.any {
            it.candidatePath.endsWith("metrics.wasm") &&
                it.reason.contains("not a WebAssembly component")
        })
        assertEquals(2, result.summary.directRequiredImportCount)
        assertEquals(1, result.summary.transitiveRequiredImportCount)
        assertEquals(2, result.summary.ignoredRuntimeImportCount)
        assertEquals(1, result.summary.directResolvedCount)
        assertEquals(1, result.summary.transitiveResolvedCount)
        assertEquals(1, result.summary.unresolvedCount)
        assertTrue(result.summary.rejectedCandidateCount >= 4)
    }

    @Test
    fun checkComponentCompatibilityReportsMatchesAndMissingImports() {
        val root = temporaryFolder.newFolder("compat").toPath()
        val calculator = copyFixture("calculator.wasm", root.resolve("calculator.wasm"))
        val adder = copyFixture("add.component.wasm", root.resolve("deps/add.component.wasm"))

        val result = service.checkComponentCompatibility(
            input = WasmInput.FilePath(calculator.toString()),
            dependencyPaths = listOf(adder.toString()),
            projectRoot = null,
        )

        assertFalse(result.compatible)
        assertTrue(result.matches.any { it.importName.contains("component:adder/add") })
        assertTrue(result.missingImports.any { it.baseName.contains("component:subtractor/subtract") })
        assertEquals(result.compatible, result.summary.compatible)
    }

    private fun componentFixture(name: String): Path =
        repoRoot().resolve("wasmParser/src/commonTest/resources/component/$name")

    private fun copyFixture(name: String, target: Path): Path {
        Files.createDirectories(target.parent)
        return Files.copy(componentFixture(name), target)
    }

    private fun writeComponent(target: Path, imports: List<String>, exports: List<String>): Path {
        Files.createDirectories(target.parent)
        return Files.write(target, buildComponent(imports, exports))
    }

    private fun buildComponent(imports: List<String>, exports: List<String>): ByteArray {
        val sections = buildList {
            add(encodeComponentTypeSection())
            if (imports.isNotEmpty()) {
                add(encodeSection(10, encodeVector(imports.map(::encodeComponentImport))))
            }
            if (exports.isNotEmpty()) {
                add(encodeSection(11, encodeVector(exports.map(::encodeComponentExport))))
            }
        }
        return byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00) + sections.concat()
    }

    private fun encodeComponentTypeSection(): ByteArray {
        val functionType = byteArrayOf(0x40) + encodeVector(emptyList()) + byteArrayOf(0x01, 0x00)
        return encodeSection(7, encodeVector(listOf(functionType)))
    }

    private fun encodeComponentImport(name: String): ByteArray {
        val (baseName, version) = splitVersionedName(name)
        val externType = encodeExternType()
        return if (version.isBlank()) {
            byteArrayOf(0x00) + encodeName(baseName) + externType
        } else {
            byteArrayOf(0x01) + encodeName(baseName) + encodeName("@$version") + externType
        }
    }

    private fun encodeComponentExport(name: String): ByteArray {
        val (baseName, version) = splitVersionedName(name)
        val sortIndex = byteArrayOf(0x01) + encodeU32(0)
        val externDescription = byteArrayOf(0x01) + encodeExternType()
        return if (version.isBlank()) {
            byteArrayOf(0x00) + encodeName(baseName) + sortIndex + externDescription
        } else {
            byteArrayOf(0x01) + encodeName(baseName) + encodeName("@$version") + sortIndex + externDescription
        }
    }

    private fun encodeExternType(index: Int = 0): ByteArray =
        byteArrayOf(0x01) + encodeU32(index)

    private fun encodeSection(sectionId: Int, payload: ByteArray): ByteArray =
        byteArrayOf(sectionId.toByte()) + encodeU32(payload.size) + payload

    private fun encodeVector(items: List<ByteArray>): ByteArray =
        encodeU32(items.size) + items.concat()

    private fun encodeName(text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        return encodeU32(data.size) + data
    }

    private fun encodeU32(value: Int): ByteArray {
        require(value >= 0)
        val bytes = mutableListOf<Byte>()
        var remaining = value
        do {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) {
                byte = byte or 0x80
            }
            bytes += byte.toByte()
        } while (remaining != 0)
        return bytes.toByteArray()
    }

    private fun splitVersionedName(name: String): Pair<String, String> {
        if ("@" !in name) return name to ""
        val base = name.substringBeforeLast("@")
        val version = name.substringAfterLast("@")
        return base to version
    }

    private fun List<ByteArray>.concat(): ByteArray =
        fold(ByteArray(0)) { acc, bytes -> acc + bytes }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (dir.parent != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts")) && Files.exists(dir.resolve("wasmParser"))) {
                return dir
            }
            dir = dir.parent
        }
        error("Repository root not found")
    }
}
