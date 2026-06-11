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
