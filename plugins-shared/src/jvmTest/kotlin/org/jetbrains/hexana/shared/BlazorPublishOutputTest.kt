package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import org.jetbrains.hexana.shared.blazor.BlazorBundle
import org.jetbrains.hexana.shared.blazor.BlazorIntegrity
import org.jetbrains.hexana.shared.blazor.MonoAotGroups
import org.jetbrains.hexana.shared.blazor.MonoAotReport
import org.jetbrains.hexana.shared.blazor.monoAotPrologue
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
 * - `HEXANA_BLAZOR_AOT_FRAMEWORK_DIR`: `bin/Release/<tfm>/publish/wwwroot/_framework` of the same app published with
 *   `-p:RunAOTCompilation=true -p:WasmEmitSymbolMap=true` (needs the `wasm-tools` workload) into its default
 *   location, so the symbol map is found in the project's `obj` directory.
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
    fun `the published bundle matches its manifest`() {
        val framework = dir("HEXANA_BLAZOR_FRAMEWORK_DIR") ?: return
        val core = framework.listFiles { f -> f.name.startsWith("System.Private.CoreLib.") && f.name.endsWith(".wasm") }.orEmpty().single()
        val started = System.nanoTime()
        val bundle = assertNotNull(BlazorBundle.load(core.path, fileSystem, verifyIntegrity = true))
        val millis = (System.nanoTime() - started) / 1_000_000
        println("Blazor bundle: ${bundle.files.size} files, ${bundle.files.sumOf { it.size ?: 0 }} bytes hashed in $millis ms")
        assertEquals(emptyList(), bundle.files.filter { it.integrity != BlazorIntegrity.Matches && it.integrity != BlazorIntegrity.NoHash }.map { "${it.resource.fileName}: ${it.integrity}" })
        assertTrue(bundle.files.count { it.integrity == BlazorIntegrity.Matches } > 30)
        assertEquals("System.Private.CoreLib.wasm", bundle.current?.resource?.virtualPath)
    }

    private val fileSystem = object : FileSystem {
        override fun listEntries(dir: String) = File(dir).listFiles().orEmpty().map { FileEntry(it.name, it.isDirectory, it.isFile) }
        override fun readFile(path: String) = File(path).takeIf { it.isFile }?.readBytes()
        override fun fileExists(path: String) = File(path).isFile
        override fun parentDir(path: String): String = File(path).parent
        override fun joinPath(base: String, child: String): String = File(base, child).path
        override fun pathRoot(path: String) = "/"
        override fun regularFileSize(path: String) = File(path).takeIf { it.isFile }?.length()
    }

    @Test
    fun `an AOT runtime module splits into the app's assemblies, types and methods`() {
        val framework = dir("HEXANA_BLAZOR_AOT_FRAMEWORK_DIR") ?: return
        val file = framework.listFiles { f -> f.name.startsWith("dotnet.native.") && f.name.endsWith(".wasm") }.orEmpty().single()
        val provider = wasm(file)
        // Not next to the module: found under the project's obj/<config>/<tfm>/wasm/for-publish.
        assertTrue(WasmSymbolMapCompanionLoader.load(provider, file.path, fileSystem))
        assertTrue("/wasm/for-publish/" in provider.symbolMapPath.orEmpty(), provider.symbolMapPath)
        val bundle = assertNotNull(BlazorBundle.load(file.path, fileSystem, verifyIntegrity = false))
        val started = System.nanoTime()
        val report = assertNotNull(MonoAotReport.load(provider, bundle) { name -> fileSystem.readFile(File(framework, name).path) })
        println("AOT report in ${(System.nanoTime() - started) / 1_000_000} ms: " + monoAotPrologue(report).replace("\n", " | "))
        assertEquals(emptyList(), report.withoutMetadata)

        val managed = report.root.children.single { it.name == MonoAotGroups.MANAGED }
        val instances = report.root.children.single { it.name == MonoAotGroups.GENERIC_INSTANCES }
        // Most of the code is .NET, and nearly all of it resolves to a type.
        assertTrue(managed.size + instances.size > report.root.size / 2, monoAotPrologue(report))
        val unresolved = managed.children.sumOf { a -> a.children.filter { it.name == MonoAotGroups.UNRESOLVED }.sumOf { it.size } } +
            instances.children.filter { it.name == MonoAotGroups.UNRESOLVED }.sumOf { it.size }
        assertTrue(unresolved * 100 < managed.size + instances.size, "unresolved $unresolved")
        assertEquals("System.Private.CoreLib", managed.children.first().name)
        val app = managed.children.single { it.name == "app" }
        assertTrue(app.children.any { ns -> ns.name == "app.Pages" && ns.children.any { it.name == "Counter" } })
    }

    @Test
    fun `the runtime's symbol map names its functions`() {
        val native = dir("HEXANA_BLAZOR_RUNTIME_NATIVE_DIR") ?: return
        val file = native.resolve("dotnet.native.wasm")
        val provider = wasm(file)
        assertNull(provider.nameSection?.functionSubsection)
        assertTrue(WasmSymbolMapCompanionLoader.load(provider, file.path, fileSystem))
        val names = assertNotNull(provider.nameSection?.functionSubsection?.namemap)
        assertEquals(provider.functionIndexSpaceSize, names.size)
        assertTrue(names.values.any { it.startsWith("mono_") })
    }
}
