package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.query.resolveComponentDependencies
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                it.reason == "exports acme:pricing/quote@1.2.0, which does not match required base name acme:pricing/calculate"
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
    fun resolveComponentDependenciesRejectsOversizedScanCandidatesBeforeParsing() {
        val root = temporaryFolder.newFolder("oversized-candidates").toPath()
        val checkout = writeComponent(
            root.resolve("dist/components/checkout.component.wasm"),
            imports = listOf("acme:pricing/calculate@1.0.0"),
            exports = listOf("acme:checkout/run@1.0.0"),
        )
        Files.createDirectories(root.resolve("components"))
        Files.write(root.resolve("components/oversized.component.wasm"), ByteArray(32))

        val provider = WasmComponentBinaryProvider(Files.readAllBytes(checkout).toCommonByteBuffer())
        val result = provider.resolveComponentDependencies(
            path = checkout.toString(),
            projectRoot = root.toString(),
            scanRoots = emptyList(),
            limits = HexanaResourceLimits(maxFileWasmBytes = 8),
        )

        assertTrue(result.rejectedCandidates.any {
            it.candidatePath.endsWith("oversized.component.wasm") &&
                it.reason == "file size 32 bytes exceeds configured maximum file size of 8 bytes"
        })
    }

    @Test
    fun resolveComponentDependenciesExcludesRelativeMainComponentFromRejectedCandidates() {
        val root = temporaryFolder.newFolder("relative-checkout").toPath()
        val relativeCheckout = "dist/components/checkout.component.wasm"
        val checkout = writeComponent(
            root.resolve(relativeCheckout),
            imports = listOf(
                "acme:pricing/calculate@1.2.0",
                "wasi:cli/run@0.2.3",
            ),
            exports = listOf("acme:checkout/run@1.0.0"),
        )
        writeComponent(
            root.resolve("components/pricing.component.wasm"),
            imports = listOf("acme:tax/rates@1.0.0"),
            exports = listOf("acme:pricing/calculate@1.2.0"),
        )
        writeComponent(
            root.resolve("components/tax.component.wasm"),
            imports = emptyList(),
            exports = listOf("acme:tax/rates@1.0.0"),
        )
        writeComponent(
            root.resolve("components/pricing_preview.component.wasm"),
            imports = emptyList(),
            exports = listOf("acme:pricing/quote@1.2.0"),
        )

        val provider = WasmComponentBinaryProvider(Files.readAllBytes(checkout).toCommonByteBuffer())
        val result = provider.resolveComponentDependencies(
            path = relativeCheckout,
            projectRoot = root.toString(),
            scanRoots = emptyList(),
        )

        assertFalse(result.rejectedCandidates.any { it.candidatePath.endsWith(relativeCheckout) })
        assertTrue(result.resolvedDependencyPaths.any {
            it.importName == "acme:pricing/calculate@1.2.0" && it.classification == "direct"
        })
        assertTrue(result.resolvedDependencyPaths.any {
            it.importName == "acme:tax/rates@1.0.0" && it.classification == "transitive"
        })
        assertEquals(
            "Add components/pricing.component.wasm and components/tax.component.wasm to the checkout component dependency set before running dist/components/checkout.component.wasm.",
            result.minimalRunBuildFix,
        )
    }

    @Test
    fun resolveComponentDependenciesDeduplicatesRelativeScanRootsAgainstProjectRoot() {
        val root = temporaryFolder.newFolder("relative-scan-roots").toPath()
        val relativeCheckout = "dist/components/checkout.component.wasm"
        val checkout = writeComponent(
            root.resolve(relativeCheckout),
            imports = listOf(
                "acme:pricing/calculate@1.2.0",
                "wasi:cli/run@0.2.3",
            ),
            exports = listOf("acme:checkout/run@1.0.0"),
        )
        val pricing = writeComponent(
            root.resolve("components/pricing.component.wasm"),
            imports = listOf("acme:tax/rates@1.0.0"),
            exports = listOf("acme:pricing/calculate@1.2.0"),
        )
        val tax = writeComponent(
            root.resolve("components/tax.component.wasm"),
            imports = emptyList(),
            exports = listOf("acme:tax/rates@1.0.0"),
        )

        val provider = WasmComponentBinaryProvider(Files.readAllBytes(checkout).toCommonByteBuffer())
        val result = provider.resolveComponentDependencies(
            path = root.resolve(relativeCheckout).toString(),
            projectRoot = root.toString(),
            scanRoots = listOf("components", "third_party", "tools/wasm"),
        )

        assertTrue(result.candidateConflicts.isEmpty(), "Relative scan roots must not duplicate project-root files")
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
        assertEquals(
            "Add components/pricing.component.wasm and components/tax.component.wasm to the checkout component dependency set before running ${root.resolve(relativeCheckout)}.",
            result.minimalRunBuildFix,
        )
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
            previousPath = null,
            dependencyLockPath = null,
        )

        assertFalse(result.compatible)
        assertTrue(result.matches.any { it.importName.contains("component:adder/add") })
        assertTrue(result.missingImports.any { it.baseName.contains("component:subtractor/subtract") })
        assertEquals(result.compatible, result.summary.compatible)
    }

    @Test
    fun checkComponentCompatibilityRejectsOversizedExplicitDependency() {
        val root = temporaryFolder.newFolder("oversized-explicit-dependency").toPath()
        val current = writeComponentEntries(
            root.resolve("current.component.wasm"),
            imports = emptyList(),
            exports = emptyList(),
        )
        val oversizedDependency = root.resolve("deps/oversized.component.wasm")
        Files.createDirectories(oversizedDependency.parent)
        val maxFileBytes = Files.size(current).toInt()
        Files.write(oversizedDependency, ByteArray(maxFileBytes + 1))

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(
                limits = HexanaResourceLimits(maxFileWasmBytes = maxFileBytes)
            ).checkComponentCompatibility(
                input = WasmInput.FilePath(current.toString()),
                dependencyPaths = listOf(oversizedDependency.toString()),
                projectRoot = root.toString(),
                previousPath = null,
                dependencyLockPath = null,
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.contains("Component provider size"))
    }

    @Test
    fun checkComponentCompatibilityRejectsOversizedPreviousComponent() {
        val root = temporaryFolder.newFolder("oversized-previous-component").toPath()
        val current = writeComponentEntries(
            root.resolve("current.component.wasm"),
            imports = listOf(componentImport("acme:reporting/sink@2.0.0")),
            exports = emptyList(),
        )
        val oversizedPrevious = root.resolve("previous.component.wasm")
        val maxFileBytes = Files.size(current).toInt()
        Files.write(oversizedPrevious, ByteArray(maxFileBytes + 1))

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(
                limits = HexanaResourceLimits(maxFileWasmBytes = maxFileBytes)
            ).checkComponentCompatibility(
                input = WasmInput.FilePath(current.toString()),
                dependencyPaths = emptyList(),
                projectRoot = root.toString(),
                previousPath = oversizedPrevious.toString(),
                dependencyLockPath = null,
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.contains("Component provider size"))
    }

    @Test
    fun checkComponentCompatibilityRejectsOversizedDependencyLock() {
        val root = temporaryFolder.newFolder("oversized-dependency-lock").toPath()
        val current = writeComponentEntries(
            root.resolve("current.component.wasm"),
            imports = emptyList(),
            exports = emptyList(),
        )
        val lockPath = root.resolve("config/component-lock.json")
        Files.createDirectories(lockPath.parent)
        Files.writeString(lockPath, """{"providers":[]}""")

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(
                limits = HexanaResourceLimits(maxTextFileBytes = 4)
            ).checkComponentCompatibility(
                input = WasmInput.FilePath(current.toString()),
                dependencyPaths = emptyList(),
                projectRoot = root.toString(),
                previousPath = null,
                dependencyLockPath = "config/component-lock.json",
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.contains("Dependency lock size"))
    }

    @Test
    fun checkComponentCompatibilityExplainsDriftAndLockFixes() {
        val root = temporaryFolder.newFolder("reporting").toPath()
        val current = writeComponentEntries(
            root.resolve("dist/components/reporting.component.wasm"),
            imports = listOf(componentImport("acme:reporting/sink@2.0.0")),
            exports = listOf(componentExport("acme:reporting/run@2.0.0")),
        )
        val previous = writeComponentEntries(
            root.resolve("previous_build/dist/components/reporting.component.wasm"),
            imports = listOf(componentImport("acme:reporting/sink@1.4.0")),
            exports = listOf(componentExport("acme:reporting/run@1.4.0")),
        )
        writeComponentEntries(
            root.resolve("components/reporting_sink_legacy.component.wasm"),
            imports = emptyList(),
            exports = listOf(componentExport("acme:reporting/sink@1.4.0")),
        )
        val wrongShape = writeComponentEntries(
            root.resolve("components/reporting_sink_v2_wrong_shape.component.wasm"),
            imports = emptyList(),
            exports = listOf(
                componentExport(
                    "acme:reporting/sink@2.0.0",
                    sortKind = "instance",
                    externKind = "instance",
                )
            ),
        )
        writeComponentEntries(
            root.resolve("third_party/components/telemetry_sink.component.wasm"),
            imports = emptyList(),
            exports = listOf(componentExport("acme:telemetry/sink@2.0.0")),
        )
        val lockPath = root.resolve("config/component-lock.json")
        Files.createDirectories(lockPath.parent)
        Files.writeString(
            lockPath,
            """
            {
              "providers": [
                {
                  "interface": "acme:reporting/sink",
                  "version": "1.4.0",
                  "path": "components/reporting_sink_legacy.component.wasm"
                },
                {
                  "interface": "acme:telemetry/sink",
                  "version": "2.0.0",
                  "path": "third_party/components/telemetry_sink.component.wasm"
                }
              ]
            }
            """.trimIndent()
        )

        val result = service.checkComponentCompatibility(
            input = WasmInput.FilePath(current.toString()),
            dependencyPaths = emptyList(),
            projectRoot = root.toString(),
            previousPath = previous.toString(),
            dependencyLockPath = "config/component-lock.json",
        )

        assertFalse(result.compatible)
        assertEquals("sort_mismatch", result.selectedIncompatibilityReason)
        assertEquals("acme:reporting/sink@2.0.0", result.failingImport?.importName)
        assertEquals("acme:reporting/sink@1.4.0", result.failingImport?.previousImportName)
        assertEquals(true, result.failingImport?.changedSincePrevious)
        assertTrue(result.configuredProviders.any {
            it.interfaceName == "acme:reporting/sink" &&
                it.path == "components/reporting_sink_legacy.component.wasm"
        })
        assertTrue(result.candidateProviders.any {
            it.dependencyPath.endsWith("reporting_sink_legacy.component.wasm") &&
                it.verdict == "version_drift"
        })
        assertTrue(result.candidateProviders.any {
            it.dependencyPath == wrongShape.toString() &&
                it.verdict == "sort_mismatch"
        })
        assertTrue(result.candidateProviders.any {
            it.dependencyPath.endsWith("telemetry_sink.component.wasm") &&
                it.verdict == "wrong_interface"
        })
        assertTrue(result.providerPathsInvolved.any { it == "components/reporting_sink_legacy.component.wasm" })
        assertTrue(result.providerPathsInvolved.any { it.endsWith("reporting_sink_v2_wrong_shape.component.wasm") })
        assertEquals("dist/components/reporting.component.wasm", result.componentPath)
        assertEquals("previous_build/dist/components/reporting.component.wasm", result.previousComponentPath)
        assertEquals("config/component-lock.json", result.dependencyLockPathForReport)
        assertEquals("acme:reporting/sink@2.0.0", result.failingImportForReport?.importName)
        assertEquals("acme:reporting/sink", result.failingImportForReport?.baseName)
        assertEquals("2.0.0", result.failingImportForReport?.requiredVersion)
        assertEquals("acme:reporting/sink@1.4.0", result.failingImportForReport?.previousImportName)
        assertEquals("sort_mismatch", result.selectedIncompatibilityReasonForReport)
        assertEquals(
            listOf(
                "components/reporting_sink_legacy.component.wasm",
                "components/reporting_sink_v2_wrong_shape.component.wasm",
            ),
            result.providerPathsInvolvedForReport,
        )
        assertFalse(
            result.providerPathsInvolvedForReport.any { it.contains("telemetry_sink") },
            "Report-ready involved paths must not include unrelated telemetry providers",
        )
        assertTrue(result.candidateProvidersInspected.any {
            it.dependencyPath == "components/reporting_sink_v2_wrong_shape.component.wasm" &&
                it.exportName == "acme:reporting/sink@2.0.0" &&
                it.verdict == "sort_mismatch"
        })
        assertTrue(result.candidateProvidersInspected.any {
            it.dependencyPath == "third_party/components/telemetry_sink.component.wasm" &&
                it.verdict == "wrong_interface"
        })
        assertFalse(
            result.candidateProvidersInspected.any { it.dependencyPath == "previous_build/dist/components/reporting.component.wasm" },
            "Previous component is drift context, not a provider candidate",
        )
        assertEquals("config/component-lock.json", result.minimalDependencyLockFix?.dependencyLockPath)
        assertEquals("components/reporting_sink_legacy.component.wasm", result.minimalDependencyLockFix?.replaceProviderPath)
        assertEquals("acme:reporting/sink", result.minimalDependencyLockFix?.requiredInterface)
        assertEquals("2.0.0", result.minimalDependencyLockFix?.requiredVersion)
        assertEquals(wrongShape.toString(), result.minimalDependencyLockFix?.rejectProviderPath)
        assertEquals(
            "components/reporting_sink_legacy.component.wasm",
            result.minimalDependencyLockFixForReport?.replaceProviderPath,
        )
        assertEquals(
            "components/reporting_sink_v2_wrong_shape.component.wasm",
            result.minimalDependencyLockFixForReport?.rejectProviderPath,
        )
        assertTrue(result.minimalDependencyLockFix?.requiredShape.orEmpty().contains("Func export"))
        assertTrue(result.evidence?.summary.orEmpty().contains("acme:reporting/sink@2.0.0"))
        assertTrue(result.recommendation.orEmpty().contains("config/component-lock.json"))
        assertTrue(result.recommendation.orEmpty().contains("acme:reporting/sink@2.0.0"))
        assertTrue(result.summary.sortMismatchCount >= 1)
        assertTrue(result.summary.wrongInterfaceCount >= 1)
    }

    private fun componentFixture(name: String): Path =
        repoRoot().resolve("wasmParser/src/commonTest/resources/component/$name")

    private fun copyFixture(name: String, target: Path): Path {
        Files.createDirectories(target.parent)
        return Files.copy(componentFixture(name), target)
    }

    private data class TestComponentEntry(
        val name: String,
        val sortKind: String = "func",
        val externKind: String = "type",
    )

    private fun componentImport(name: String, externKind: String = "type"): TestComponentEntry =
        TestComponentEntry(name = name, externKind = externKind)

    private fun componentExport(
        name: String,
        sortKind: String = "func",
        externKind: String = "type",
    ): TestComponentEntry =
        TestComponentEntry(name = name, sortKind = sortKind, externKind = externKind)

    private fun writeComponent(target: Path, imports: List<String>, exports: List<String>): Path =
        writeComponentEntries(
            target = target,
            imports = imports.map(::componentImport),
            exports = exports.map(::componentExport),
        )

    private fun writeComponentEntries(target: Path, imports: List<TestComponentEntry>, exports: List<TestComponentEntry>): Path {
        Files.createDirectories(target.parent)
        return Files.write(target, buildComponent(imports, exports))
    }

    private fun buildComponent(imports: List<TestComponentEntry>, exports: List<TestComponentEntry>): ByteArray {
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

    private fun encodeComponentImport(entry: TestComponentEntry): ByteArray {
        val (baseName, version) = splitVersionedName(entry.name)
        val externType = encodeExternType(entry.externKind)
        return if (version.isBlank()) {
            byteArrayOf(0x00) + encodeName(baseName) + externType
        } else {
            byteArrayOf(0x01) + encodeName(baseName) + encodeName("@$version") + externType
        }
    }

    private fun encodeComponentExport(entry: TestComponentEntry): ByteArray {
        val (baseName, version) = splitVersionedName(entry.name)
        val sortIndex = encodeSortIndex(entry.sortKind)
        val externDescription = byteArrayOf(0x01) + encodeExternType(entry.externKind)
        return if (version.isBlank()) {
            byteArrayOf(0x00) + encodeName(baseName) + sortIndex + externDescription
        } else {
            byteArrayOf(0x01) + encodeName(baseName) + encodeName("@$version") + sortIndex + externDescription
        }
    }

    private fun encodeSortIndex(kind: String, index: Int = 0): ByteArray =
        byteArrayOf(componentSortKindTag(kind)) + encodeU32(index)

    private fun encodeExternType(kind: String = "type", index: Int = 0): ByteArray =
        byteArrayOf(componentExternKindTag(kind)) + encodeU32(index)

    private fun componentSortKindTag(kind: String): Byte = when (kind) {
        "func" -> 0x01
        "value" -> 0x02
        "type" -> 0x03
        "component" -> 0x04
        "instance" -> 0x05
        else -> error("Unsupported component sort kind: $kind")
    }.toByte()

    private fun componentExternKindTag(kind: String): Byte = when (kind) {
        "type" -> 0x01
        "component" -> 0x04
        "instance" -> 0x05
        else -> error("Unsupported component extern kind: $kind")
    }.toByte()

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
