package org.jetbrains.hexana.shared.blazor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boot manifests of real `dotnet new blazorwasm` builds (`src/jvmTest/resources/blazor`): the .NET 10 one
 * embedded in `dotnet.<fp>.js`, and the .NET 9 `blazor.boot.json`.
 */
class BlazorBootConfigTest {
    private fun resource(name: String): String =
        assertNotNull(javaClass.getResource("/blazor/$name"), name).readText()

    @Test
    fun `NET 10 manifest embedded in dotnet js`() {
        val text = resource("net10-release.dotnet.js")
        assertTrue(BlazorBootConfig.embedsManifest(text))
        val config = assertNotNull(BlazorBootConfig.parse(text))
        assertEquals(BlazorBootConfig.Source.EmbeddedInDotnetJs, config.source)
        assertEquals("app", config.mainAssemblyName)
        assertEquals("sharded", config.globalizationMode)
        assertEquals(0, config.debugLevel)
        assertEquals(true, config.linkerEnabled)

        val byKind = config.resources.groupBy { it.kind }
        assertEquals(listOf("dotnet.native.wasm"), byKind[BlazorResourceKind.WasmNative]?.map { it.virtualPath })
        assertEquals(2, byKind[BlazorResourceKind.CoreAssembly]?.size)
        assertEquals(33, byKind[BlazorResourceKind.Assembly]?.size)
        assertEquals(3, byKind[BlazorResourceKind.Icu]?.size)
        assertEquals(2, byKind[BlazorResourceKind.JsModule]?.size)
        val app = config.resources.single { it.virtualPath == "app.wasm" }
        assertEquals("app.ol0snvssxq.wasm", app.fileName)
        assertTrue(app.integrity!!.startsWith("sha256-"))
    }

    @Test
    fun `NET 10 Debug build lists the PDB and leaves out the linker flag`() {
        val config = assertNotNull(BlazorBootConfig.parse(resource("net10-debug.dotnet.js")))
        assertEquals(-1, config.debugLevel)
        assertNull(config.linkerEnabled)
        assertEquals("Development", config.applicationEnvironment)
        val pdb = config.resources.single { it.kind == BlazorResourceKind.Pdb }
        assertEquals("app.pdb", pdb.virtualPath)
        assertTrue(pdb.fileName.matches(Regex("""app\.[a-z0-9]+\.pdb""")), pdb.fileName)
        // Untrimmed: the whole shared framework ships.
        assertTrue(config.resources.count { it.kind == BlazorResourceKind.Assembly } > 150)
    }

    @Test
    fun `NET 9 blazor boot json with its fingerprinting map`() {
        val config = assertNotNull(BlazorBootConfig.parse(resource("net9-release.blazor.boot.json")))
        assertEquals(BlazorBootConfig.Source.BlazorBootJson, config.source)
        assertEquals("app", config.mainAssemblyName)
        val app = config.resources.single { it.kind == BlazorResourceKind.Assembly && it.virtualPath == "app.wasm" }
        assertTrue(app.fileName.matches(Regex("""app\.[a-z0-9]+\.wasm""")), app.fileName)
        assertEquals("System.Private.CoreLib.wasm", config.resources.single { it.fileName.startsWith("System.Private.CoreLib.") }.virtualPath)
        // Native and ICU names are not in the fingerprinting map; the name minus its fingerprint stands in.
        assertEquals(listOf("dotnet.native.wasm"), config.resources.filter { it.kind == BlazorResourceKind.WasmNative }.map { it.virtualPath })
        assertEquals("icudt_CJK.dat", config.resources.single { it.fileName.startsWith("icudt_CJK.") }.virtualPath)
        assertEquals("dotnet.native.js", BlazorBootConfig.unfingerprinted("dotnet.native.js"))
    }

    @Test
    fun `satellite resources keep their culture, and anything else is not a manifest`() {
        val config = assertNotNull(
            BlazorBootConfig.parse(
                """{"mainAssemblyName":"a","resources":{"satelliteResources":{"de":[{"virtualPath":"a.resources.wasm","name":"a.resources.x1.wasm","hash":"sha256-AA=="}]},
                   "lazyAssembly":{"b.y2.wasm":"sha256-BB=="},"fingerprinting":{"b.y2.wasm":"b.wasm"}}}""",
            ),
        )
        assertEquals("de", config.resources.single { it.kind == BlazorResourceKind.Satellite }.culture)
        assertEquals("b.wasm", config.resources.single { it.kind == BlazorResourceKind.LazyAssembly }.virtualPath)

        assertNull(BlazorBootConfig.parse("export function f() { return 1 }"))
        assertNull(BlazorBootConfig.parse("""{"mainAssemblyName":"a"}"""))
        assertNull(BlazorBootConfig.parse("/*json-start*/{ broken /*json-end*/"))
    }
}
