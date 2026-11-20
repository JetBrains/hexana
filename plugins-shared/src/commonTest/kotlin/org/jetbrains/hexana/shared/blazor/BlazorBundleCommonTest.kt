package org.jetbrains.hexana.shared.blazor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The manifest parsing and integrity check on every target: the VS Code webview runs them as
 * Kotlin/JS, IntelliJ on the JVM. `sha256-ungWv48B…` is the SHA-256 of "abc" (FIPS 180-2 example).
 */
class BlazorBundleCommonTest {
    private val abc = "abc".encodeToByteArray()
    private val abcIntegrity = "sha256-ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="

    private val config = assertNotNull(
        BlazorBootConfig.parse(
            """
            // dotnet.js
            const c = /*json-start*/{"mainAssemblyName":"app","resources":{
              "assembly":[{"virtualPath":"app.wasm","name":"app.abcdefghij.wasm","hash":"$abcIntegrity"}],
              "wasmNative":[{"name":"dotnet.native.a6k1euv12n.wasm","hash":"sha256-AAAA"}]
            }}/*json-end*/;
            """.trimIndent(),
        ),
    )

    @Test
    fun parsesTheEmbeddedManifest() {
        assertEquals(BlazorBootConfig.Source.EmbeddedInDotnetJs, config.source)
        assertEquals(listOf("app.wasm", "dotnet.native.wasm"), config.resources.map { it.virtualPath })
    }

    @Test
    fun checksIntegrityFromBytesOrAHostHash() {
        val app = config.resources.first()
        assertEquals(BlazorIntegrity.Matches, BlazorBundle.verify(app, abc))
        assertEquals(BlazorIntegrity.Mismatch, BlazorBundle.verify(app, "abd".encodeToByteArray()))
        assertEquals(BlazorIntegrity.Matches, BlazorBundle.integrityOf(app, "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="))
        assertEquals(BlazorIntegrity.NoHash, BlazorBundle.integrityOf(app.copy(integrity = null), "x"))
    }

    @Test
    fun assemblesOnlyForAListedFile() {
        val sizes = mapOf("app.abcdefghij.wasm" to 3L, "app.abcdefghij.wasm.br" to 1L)
        val bundle = assertNotNull(
            BlazorBundle.assemble("/fw", "dotnet.js", config, sizes, "app.abcdefghij.wasm") { BlazorIntegrity.Matches },
        )
        assertEquals(1L, bundle.current?.downloadSize)
        assertEquals(BlazorIntegrity.Missing, bundle.files.single { it.resource.kind == BlazorResourceKind.WasmNative }.integrity)
        assertNull(BlazorBundle.assemble("/fw", "dotnet.js", config, sizes, "other.wasm") { BlazorIntegrity.Matches })
    }
}
