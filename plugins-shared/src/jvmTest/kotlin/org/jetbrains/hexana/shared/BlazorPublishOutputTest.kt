package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Blazor WebAssembly output, which is not committed (it is large and the runtime is not ours).
 * Skipped unless the environment points at it:
 *
 * - `HEXANA_BLAZOR_FRAMEWORK_DIR`: a published app's `wwwroot/_framework` (`dotnet publish` of `dotnet new blazorwasm`).
 *   Every `*.wasm` except the runtime must decode as a .NET assembly without diagnostics.
 * - `HEXANA_BLAZOR_RUNTIME_NATIVE_DIR`: the runtime pack's native folder, holding `dotnet.native.wasm` and
 *   `dotnet.native.js.symbols` (`~/.nuget/packages/microsoft.netcore.app.runtime.mono.browser-wasm/<version>/runtimes/browser-wasm/native`).
 */
class BlazorPublishOutputTest {
    private fun dir(variable: String): File? = System.getenv(variable)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    private fun wasm(file: File): WasmBinaryProvider = assertIs<WasmBinaryProvider>(createBinaryProvider(file.readBytes().toCommonByteBuffer(), file.path, file.name))

    @Test
    fun `every published assembly decodes as Webcil`() {
        val framework = dir("HEXANA_BLAZOR_FRAMEWORK_DIR") ?: return
        val modules = framework.listFiles { f -> f.name.endsWith(".wasm") }.orEmpty().sortedBy { it.name }
        assertTrue(modules.isNotEmpty(), "no .wasm in $framework")
        var methods = 0
        for (file in modules) {
            val provider = wasm(file)
            if (file.name.startsWith("dotnet.native.")) {
                assertNull(provider.webcil, file.name)
                continue
            }
            val assembly = assertNotNull(provider.webcil, file.name)
            val metadata = assertNotNull(assembly.clrMetadata, file.name)
            assertEquals(emptyList(), metadata.diagnostics, file.name)
            assertEquals(file.name.substringBefore('.'), metadata.assembly?.name?.substringBefore('.'), file.name)
            methods += metadata.methods.size
            for (name in CLR_TAB_NAMES) WasmTableTabsProvider().singleTab(name, provider, TableTabsContext())
        }
        assertTrue(methods > 1000, "only $methods methods")
    }

    @Test
    fun `the runtime's symbol map names its functions`() {
        val native = dir("HEXANA_BLAZOR_RUNTIME_NATIVE_DIR") ?: return
        val file = native.resolve("dotnet.native.wasm")
        val provider = wasm(file)
        assertNull(provider.nameSection?.functionSubsection)
        val fs = object : FileSystem {
            override fun listEntries(dir: String) = File(dir).listFiles().orEmpty().map { FileEntry(it.name, it.isDirectory, it.isFile) }
            override fun readFile(path: String) = File(path).takeIf { it.isFile }?.readBytes()
            override fun fileExists(path: String) = File(path).isFile
            override fun parentDir(path: String): String = File(path).parent
            override fun joinPath(base: String, child: String): String = File(base, child).path
            override fun pathRoot(path: String) = "/"
            override fun regularFileSize(path: String) = File(path).takeIf { it.isFile }?.length()
        }
        assertTrue(WasmSymbolMapCompanionLoader.load(provider, file.path, fs))
        val names = assertNotNull(provider.nameSection?.functionSubsection?.namemap)
        assertEquals(provider.functionIndexSpaceSize, names.size)
        assertTrue(names.values.any { it.startsWith("mono_") })
    }
}
