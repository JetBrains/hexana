package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Finding `dotnet.native.js.symbols` (or any Emscripten symbol map) next to a stripped module. */
class WasmSymbolMapCompanionLoaderTest {
    /** `(import "env" "foo" (func))` plus two defined functions, no name section. */
    private val stripped = byteArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x02, 0x0b, 0x01, 0x03, 'e'.code.toByte(), 'n'.code.toByte(), 'v'.code.toByte(),
        0x03, 'f'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 0x00, 0x00,
        0x03, 0x03, 0x02, 0x00, 0x00,
        0x0a, 0x07, 0x02, 0x02, 0x00, 0x0b, 0x02, 0x00, 0x0b,
    )

    private class DirFileSystem : FileSystem {
        val reads = mutableListOf<String>()
        override fun listEntries(dir: String): List<FileEntry> =
            File(dir).listFiles().orEmpty().map { FileEntry(it.name, it.isDirectory, it.isFile) }
        override fun readFile(path: String): ByteArray? { reads += path; return File(path).takeIf { it.isFile }?.readBytes() }
        override fun fileExists(path: String): Boolean = File(path).isFile
        override fun parentDir(path: String): String = File(path).parent
        override fun joinPath(base: String, child: String): String = File(base, child).path
        override fun pathRoot(path: String): String = "/"
        override fun regularFileSize(path: String): Long? = File(path).takeIf { it.isFile }?.length()
    }

    private fun dir(vararg files: Pair<String, String>): File = Files.createTempDirectory("hexana-symbols").toFile().apply {
        deleteOnExit()
        for ((name, text) in files) resolve(name).writeText(text)
    }

    private fun provider() = WasmBinaryProvider(stripped.toCommonByteBuffer())

    @Test
    fun `loads the map named after the module`() {
        val dir = dir("dotnet.native.js.symbols" to "0:foo\n1:mono_wasm_load_runtime\n2:mini_init\n")
        val provider = provider()
        val path = dir.resolve("dotnet.native.wasm").path
        assertEquals(dir.resolve("dotnet.native.js.symbols").path, WasmSymbolMapCompanionLoader.candidatePaths(provider, path).first())
        assertTrue(WasmSymbolMapCompanionLoader.load(provider, path, DirFileSystem()))
        assertEquals("mini_init", provider.nameSection?.functionSubsection?.namemap?.get(2))
        assertEquals(emptyList(), WasmSymbolMapCompanionLoader.candidatePaths(provider, path))
    }

    @Test
    fun `a fingerprinted module finds the plain or the fingerprinted map`() {
        val path = "/app/_framework/dotnet.native.a6k1euv12n.wasm"
        val candidates = WasmSymbolMapCompanionLoader.candidatePaths(
            provider(),
            path,
            listOf("dotnet.native.zz9yy8xx7w.js.symbols", "dotnet.native.extra.zz9yy8xx7w.js.symbols", "dotnet.js"),
        )
        assertEquals(
            listOf(
                "/app/_framework/dotnet.native.a6k1euv12n.js.symbols",
                "/app/_framework/dotnet.native.a6k1euv12n.wasm.symbols",
                "/app/_framework/dotnet.native.js.symbols",
                "/app/_framework/dotnet.native.zz9yy8xx7w.js.symbols",
            ),
            candidates,
        )
    }

    @Test
    fun `a map of another build is skipped for the next candidate`() {
        val dir = dir(
            "dotnet.native.ab12cd34ef.js.symbols" to "0:other_import\n1:x\n2:y\n",
            "dotnet.native.js.symbols" to "0:foo\n1:a\n2:b\n",
        )
        val provider = provider()
        assertTrue(WasmSymbolMapCompanionLoader.load(provider, dir.resolve("dotnet.native.ab12cd34ef.wasm").path, DirFileSystem()))
        assertEquals(dir.resolve("dotnet.native.js.symbols").path, provider.symbolMapPath)
    }

    @Test
    fun `the runtime module of a published Blazor app finds its map in the project's obj directory`() {
        val project = Files.createTempDirectory("hexana-blazor-project").toFile().apply { deleteOnExit() }
        val framework = project.resolve("bin/Release/net10.0/publish/wwwroot/_framework").apply { mkdirs() }
        val module = framework.resolve("dotnet.native.ovw7trd9up.wasm").apply { writeBytes(stripped) }
        project.resolve("obj/Release/net10.0/wasm/for-build").apply { mkdirs() }.resolve("dotnet.native.js.symbols").writeText("0:foo\n1:stale\n")
        project.resolve("obj/Release/net10.0/wasm/for-publish").apply { mkdirs() }.resolve("dotnet.native.js.symbols").writeText("0:foo\n1:a\n2:b\n")

        assertEquals(
            listOf("for-publish", "for-build"),
            WasmSymbolMapCompanionLoader.dotnetBuildCandidates(module.path, DirFileSystem()).map { File(it).parentFile.name },
        )
        val provider = provider()
        assertTrue(WasmSymbolMapCompanionLoader.load(provider, module.path, DirFileSystem()))
        assertTrue(provider.symbolMapPath!!.endsWith("for-publish/dotnet.native.js.symbols"), provider.symbolMapPath)
        // Any other module stays out of the project's obj directory.
        assertEquals(emptyList(), WasmSymbolMapCompanionLoader.dotnetBuildCandidates(framework.resolve("app.abc.wasm").path, DirFileSystem()))
    }

    @Test
    fun `nothing is read without a map or for a module with names`() {
        val fs = DirFileSystem()
        assertFalse(WasmSymbolMapCompanionLoader.load(provider(), dir().resolve("dotnet.native.wasm").path, fs))
        val named = File("../wasmParser/src/commonTest/resources/c/utf8-c.wasm").readBytes()
        val namedProvider = WasmBinaryProvider(named.toCommonByteBuffer())
        if (namedProvider.nameSection?.functionSubsection != null) {
            assertEquals(emptyList(), WasmSymbolMapCompanionLoader.candidatePaths(namedProvider, "/x/utf8-c.wasm"))
        }
        assertEquals(emptyList(), fs.reads)
    }
}
