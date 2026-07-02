package org.jetbrains.hexana.mcp

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AllowedFilePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun allowsFilesUnderConfiguredRoot() {
        val root = temporaryFolder.newFolder("allowed").toPath()
        val wasm = writeMinimalWasm(root.resolve("module.wasm"))
        val service = restrictedService(root)

        val result = service.summarizeModule(WasmInput.FilePath(wasm.toString()))

        assertEquals(0, result.functions)
    }

    @Test
    fun deniesFilesOutsideConfiguredRoot() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val outside = writeMinimalWasm(temporaryFolder.newFolder("outside").toPath().resolve("module.wasm"))
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(outside.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun deniesDotDotTraversalOutsideConfiguredRoot() {
        val parent = temporaryFolder.newFolder("workspace").toPath()
        val allowedRoot = parent.resolve("allowed").createDirectories()
        val outside = writeMinimalWasm(parent.resolve("outside/secret.wasm"))
        val traversalPath = allowedRoot.resolve("../outside/${outside.fileName}").toString()
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(traversalPath))
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun deniesSymlinkEscapeOutsideConfiguredRoot() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val outside = writeMinimalWasm(temporaryFolder.newFolder("outside").toPath().resolve("secret.wasm"))
        val link = allowedRoot.resolve("linked-secret.wasm")
        Files.createSymbolicLink(link, outside)
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(link.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun allowsRelativePathsWithNormalizationInsideConfiguredRoot() {
        val uniqueRoot = Path.of("build/tmp/allowed-file-policy-${UUID.randomUUID()}")
        try {
            val allowedRoot = uniqueRoot.resolve("relative root").createDirectories()
            val wasm = writeMinimalWasm(allowedRoot.resolve("nested/module.wasm"))
            val service = DefaultHexanaAnalysisService(
                allowedFilePolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(allowedRoot.toString()))
            )

            val result = service.summarizeModule(
                WasmInput.FilePath(allowedRoot.resolve("nested/../nested/${wasm.fileName}").toString())
            )

            assertEquals(0, result.functions)
        } finally {
            uniqueRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun allowsPathsWithSpacesAndUnicode() {
        val root = temporaryFolder.newFolder("allowed root \u03a9").toPath()
        val wasm = writeMinimalWasm(root.resolve("module space \u03a9.wasm"))
        val service = restrictedService(root)

        val result = service.summarizeModule(WasmInput.FilePath(wasm.toString()))

        assertEquals(0, result.functions)
    }

    @Test
    fun binaryFilePathsUseAllowedRootPolicy() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val outside = temporaryFolder.newFolder("outside").toPath().resolve("native.bin")
        Files.write(outside, byteArrayOf(1, 2, 3))
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeBinary(BinaryInput.FilePath(outside.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun textPolicyPathsUseAllowedRootPolicy() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val wasm = writeMinimalWasm(allowedRoot.resolve("module.wasm"))
        val outsidePolicy = temporaryFolder.newFolder("outside").toPath().resolve("policy.json")
        Files.writeString(outsidePolicy, "{}")
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.auditLocalStackBudget(
                input = WasmInput.FilePath(wasm.toString()),
                policyInput = TextInput.FilePath(outsidePolicy.toString()),
                logInput = null,
                checkedExports = emptyList(),
                maxLocals = null,
                countParams = false,
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun textLogPathsUseAllowedRootPolicy() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val wasm = writeMinimalWasm(allowedRoot.resolve("module.wasm"))
        val outsideLog = temporaryFolder.newFolder("outside").toPath().resolve("run.log")
        Files.writeString(outsideLog, "local stack budget log")
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.auditLocalStackBudget(
                input = WasmInput.FilePath(wasm.toString()),
                policyInput = null,
                logInput = TextInput.FilePath(outsideLog.toString()),
                checkedExports = emptyList(),
                maxLocals = null,
                countParams = false,
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun allowedOversizedFilesStillUseFileSizeLimit() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val wasm = allowedRoot.resolve("oversized.wasm")
        Files.write(wasm, byteArrayOf(0, 1, 2, 3))
        val service = DefaultHexanaAnalysisService(
            maxFileInputBytes = 3,
            allowedFilePolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(allowedRoot.toString()))
        )

        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(wasm.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertEquals(
            "File exceeds maximum size of 3 bytes. Use a smaller file or increase the configured MCP file-size limit.",
            error.message
        )
    }

    @Test
    fun deniesImplicitSourceMapSymlinkEscape() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val wasm = writeMinimalWasm(allowedRoot.resolve("module.wasm"))
        val outsideMap = temporaryFolder.newFolder("outside").toPath().resolve("module.wasm.map")
        Files.writeString(outsideMap, "{}")
        Files.createSymbolicLink(allowedRoot.resolve("module.wasm.map"), outsideMap)
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.resolveWasmFrames(
                input = WasmInput.FilePath(wasm.toString()),
                frames = listOf(org.jetbrains.hexana.mcp.model.functions.WasmFrameInput(funcidx = 0)),
                sourceMapInput = null,
                crashReportInput = null,
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun componentScanRootsOutsideAllowedRootsAreRejected() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val component = copyComponentFixture("calculator.wasm", allowedRoot.resolve("calculator.wasm"))
        val outsideScanRoot = temporaryFolder.newFolder("outside-scan-root").toPath()
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.resolveComponentDependencies(
                input = WasmInput.FilePath(component.toString()),
                projectRoot = allowedRoot.toString(),
                scanRoots = listOf(outsideScanRoot.toString()),
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun componentProviderPathsOutsideAllowedRootsAreRejected() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val component = copyComponentFixture("calculator.wasm", allowedRoot.resolve("calculator.wasm"))
        val outsideProvider = copyComponentFixture(
            "add.component.wasm",
            temporaryFolder.newFolder("outside-provider").toPath().resolve("add.component.wasm")
        )
        val service = restrictedService(allowedRoot)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.checkComponentCompatibility(
                input = WasmInput.FilePath(component.toString()),
                dependencyPaths = listOf(outsideProvider.toString()),
                projectRoot = allowedRoot.toString(),
                previousPath = null,
                dependencyLockPath = null,
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    private fun restrictedService(allowedRoot: Path) = DefaultHexanaAnalysisService(
        allowedFilePolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(allowedRoot.toString()))
    )

    private fun writeMinimalWasm(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.write(path, minimalWasm)
    }

    private fun copyComponentFixture(name: String, target: Path): Path {
        Files.createDirectories(target.parent)
        return Files.copy(repoRoot().resolve("wasmParser/src/commonTest/resources/component/$name"), target)
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

    private companion object {
        val minimalWasm = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
        )
    }
}
