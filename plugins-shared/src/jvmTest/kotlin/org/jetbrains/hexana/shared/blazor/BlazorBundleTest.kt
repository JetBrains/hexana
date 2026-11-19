package org.jetbrains.hexana.shared.blazor

import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.shared.FileEntry
import org.jetbrains.hexana.shared.FileSystem
import org.jetbrains.hexana.shared.TableTabsContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `_framework` directory read through its manifest: sizes, precompressed copies, integrity, the tab. */
class BlazorBundleTest {
    private class DirFileSystem : FileSystem {
        val reads = mutableListOf<String>()
        override fun listEntries(dir: String): List<FileEntry> =
            File(dir).listFiles().orEmpty().map { FileEntry(it.name, it.isDirectory, it.isFile) }
        override fun readFile(path: String): ByteArray? { reads += File(path).name; return File(path).takeIf { it.isFile }?.readBytes() }
        override fun fileExists(path: String): Boolean = File(path).isFile
        override fun parentDir(path: String): String = File(path).parent
        override fun joinPath(base: String, child: String): String = File(base, child).path
        override fun pathRoot(path: String): String = "/"
        override fun regularFileSize(path: String): Long? = File(path).takeIf { it.isFile }?.length()
    }

    private fun sri(bytes: ByteArray): String = "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val app = ByteArray(1000) { it.toByte() }
    private val runtime = ByteArray(5000) { (it * 7).toByte() }

    /** `app` intact with a Brotli copy, the runtime changed after publish, `lib` never copied. */
    private fun framework(): File = Files.createTempDirectory("hexana-framework").toFile().apply {
        deleteOnExit()
        resolve("app.abc123.wasm").writeBytes(app)
        resolve("app.abc123.wasm.br").writeBytes(ByteArray(400))
        resolve("app.abc123.wasm.gz").writeBytes(ByteArray(450))
        resolve("dotnet.native.def456.wasm").writeBytes(runtime.copyOf().also { it[0] = 1 })
        resolve("dotnet.native.js").writeText("not the manifest")
        resolve("dotnet.ghi789.js").writeText(
            """
            var x = 1; const config = /*json-start*/{
              "mainAssemblyName": "app", "globalizationMode": "invariant", "debugLevel": 0, "linkerEnabled": true,
              "resources": {
                "hash": "sha256-x",
                "wasmNative": [{ "virtualPath": "dotnet.native.wasm", "name": "dotnet.native.def456.wasm", "hash": "${sri(runtime)}" }],
                "assembly": [
                  { "virtualPath": "app.wasm", "name": "app.abc123.wasm", "hash": "${sri(app)}" },
                  { "virtualPath": "lib.wasm", "name": "lib.zzz999.wasm", "hash": "sha256-AAAA" }
                ]
              }
            }/*json-end*/;
            """.trimIndent(),
        )
    }

    @Test
    fun `finds the manifest among the dotnet scripts`() {
        assertEquals(
            listOf("blazor.boot.json", "dotnet.js", "dotnet.ghi789.js"),
            BlazorBundle.configCandidates(listOf("dotnet.native.ab.js", "dotnet.ghi789.js", "dotnet.runtime.cd.js", "dotnet.js", "blazor.boot.json")),
        )
    }

    @Test
    fun `reads sizes and integrity of every listed file`() {
        val dir = framework()
        val fs = DirFileSystem()
        val bundle = assertNotNull(BlazorBundle.load(dir.resolve("app.abc123.wasm").path, fs, verifyIntegrity = true))
        assertEquals("dotnet.ghi789.js", bundle.configFileName)
        assertEquals("app", bundle.config.mainAssemblyName)

        val byName = bundle.files.associateBy { it.resource.virtualPath }
        val appFile = assertNotNull(byName["app.wasm"])
        assertEquals(1000L, appFile.size)
        assertEquals(400L, appFile.brotliSize)
        assertEquals(450L, appFile.gzipSize)
        assertEquals(400L, appFile.downloadSize)
        assertEquals(BlazorIntegrity.Matches, appFile.integrity)
        assertEquals(BlazorIntegrity.Mismatch, byName["dotnet.native.wasm"]?.integrity)
        assertEquals(5000L, byName["dotnet.native.wasm"]?.downloadSize)
        assertEquals(BlazorIntegrity.Missing, byName["lib.wasm"]?.integrity)
        assertEquals(appFile, bundle.current)
    }

    @Test
    fun `without verification nothing but the manifest is read`() {
        val dir = framework()
        val fs = DirFileSystem()
        val bundle = assertNotNull(BlazorBundle.load(dir.resolve("app.abc123.wasm").path, fs, verifyIntegrity = false))
        assertEquals(listOf("dotnet.ghi789.js"), fs.reads)
        assertEquals(BlazorIntegrity.NotChecked, bundle.files.single { it.resource.virtualPath == "app.wasm" }.integrity)
    }

    @Test
    fun `a module the manifest does not list, or a directory without one, has no bundle`() {
        val dir = framework()
        dir.resolve("stale.old.wasm").writeBytes(app)
        assertNull(BlazorBundle.load(dir.resolve("stale.old.wasm").path, DirFileSystem(), verifyIntegrity = false))
        val empty = Files.createTempDirectory("hexana-empty").toFile().apply { deleteOnExit() }
        empty.resolve("a.wasm").writeBytes(app)
        val fs = DirFileSystem()
        assertNull(BlazorBundle.load(empty.resolve("a.wasm").path, fs, verifyIntegrity = true))
        assertEquals(emptyList(), fs.reads)
    }

    @Test
    fun `the tab lists the files and summarizes the download`() {
        val dir = framework()
        val bundle = assertNotNull(BlazorBundle.load(dir.resolve("app.abc123.wasm").path, DirFileSystem(), verifyIntegrity = true))
        val provider = WasmBinaryProvider(File("../wasmParser/src/commonTest/resources/c/utf8-c.wasm").readBytes().toCommonByteBuffer())
        val tabs = BlazorBundleTableTabsProvider()
        val context = TableTabsContext(blazorBundle = bundle)
        assertEquals(listOf(BLAZOR_APP_TAB_NAME), tabs.availableTabNames(provider, context).first)
        assertEquals(emptyList(), tabs.availableTabNames(provider, TableTabsContext()).first)

        val tab = assertNotNull(tabs.singleTab(BLAZOR_APP_TAB_NAME, provider, context))
        assertEquals(3, tab.rowCount)
        fun cell(row: Int, column: String) = tab.columns.single { it.name == column }.cellProvider(row).representation
        // Runtime first, then assemblies by download size.
        assertEquals(listOf("Runtime (Wasm)", "Assembly", "Assembly"), (0 until 3).map { cell(it, "Kind") })
        // The missing `lib` has no size, so it sorts last.
        assertEquals(listOf("dotnet.native.wasm", "app.wasm  (this file)", "lib.wasm"), (0 until 3).map { cell(it, "Name") })
        assertEquals(listOf("differs from the manifest", "matches", "file missing"), (0 until 3).map { cell(it, "Integrity") })
        val prologue = assertNotNull(tab.prologueText)
        assertTrue("Blazor WebAssembly app 'app'" in prologue, prologue)
        assertTrue("1 file differs" in prologue && "dotnet.native.def456.wasm" in prologue, prologue)
        assertTrue("Missing from the directory: lib.zzz999.wasm" in prologue, prologue)
        assertTrue("This file: app.wasm (assembly), integrity matches" in prologue, prologue)
    }
}
