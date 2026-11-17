package org.jetbrains.hexana.navigation

import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import org.jetbrains.hexana.WasmFileType
import java.nio.file.Path

class HexanaGotoSymbolContributorTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String =
        Path.of("src/test/data/navigation").toAbsolutePath().toString()

    private val contributor = HexanaGotoSymbolContributor()

    private fun collectNames(): Set<String> {
        val names = mutableSetOf<String>()
        contributor.processNames(
            Processor { names.add(it); true },
            GlobalSearchScope.allScope(project),
            null
        )
        return names
    }

    private fun findItems(name: String): List<NavigationItem> {
        val items = mutableListOf<NavigationItem>()
        contributor.processElementsWithName(
            name,
            Processor { items.add(it); true },
            FindSymbolParameters.wrap(name, project, true)
        )
        return items
    }

    fun testWitPackageNameInProcessNames() {
        myFixture.addFileToProject(
            "hello.wit",
            """
            package my:pkg;
            world my-world {}
            """.trimIndent()
        )
        assertTrue(
            "expected my:pkg in processNames result, got ${collectNames()}",
            collectNames().contains("my:pkg")
        )
    }

    fun testWitPackagePresentationCarriesVersionAndKind() {
        myFixture.addFileToProject(
            "hello.wit",
            """
            package my:pkg@0.2.0;
            world my-world {}
            """.trimIndent()
        )

        val items = findItems("my:pkg")
        assertEquals("expected single navigation item for my:pkg, got $items", 1, items.size)

        val item = items.single()
        val pres = item.presentation!!
        assertEquals("my:pkg", item.name)
        assertEquals("my:pkg @0.2.0", pres.presentableText)
        assertNotNull(pres.locationString)
        assertTrue(
            "location string should start with 'package · ', got ${pres.locationString}",
            pres.locationString!!.startsWith("package · ")
        )
        assertTrue(item.canNavigate())
        assertTrue(item.canNavigateToSource())
    }

    fun testWitWorldExportSurfacesWithSlashKey() {
        myFixture.addFileToProject(
            "hello.wit",
            """
            package my:pkg;

            interface my-inter {
                type my-type = u32;
            }

            world my-world {
                export my-inter;
            }
            """.trimIndent()
        )

        // Per WitComponentIndex, export keys are "${pkgName}/${usePathText}".
        val key = "my:pkg/my-inter"
        assertTrue(
            "expected export key '$key' to surface, got ${collectNames()}",
            collectNames().contains(key)
        )

        val items = findItems(key)
        assertEquals(1, items.size)
        assertEquals(key, items.single().name)
        assertTrue(
            "export presentation should be tagged 'export · '",
            items.single().presentation!!.locationString!!.startsWith("export · ")
        )
    }

    fun testWasmComponentExportSurfaces() {
        val vfile = myFixture.copyFileToProject("calculator.wasm")
        assertEquals(WasmFileType.INSTANCE, vfile.fileType)

        val names = collectNames()
        assertFalse(
            "expected component-model exports from calculator.wasm to surface via processNames; got $names",
            names.isEmpty()
        )

        val (name, items) = names.asSequence()
            .map { candidate -> candidate to findItems(candidate) }
            .firstOrNull { (_, items) -> items.isNotEmpty() }
            ?: error("none of the surfaced wasm names round-tripped through processElementsWithName: $names")

        val binaryItem = items.first { it.name == name }
        val pres = binaryItem.presentation!!
        assertTrue(
            "binary export should be tagged 'export · ', got ${pres.locationString}",
            pres.locationString!!.startsWith("export · ")
        )
        assertTrue(binaryItem.canNavigate())
    }

    fun testWasmModuleExportSurfaces() {
        myFixture.copyFileToProject("simple.wasm")

        val names = collectNames()
        assertTrue(
            "expected 'exported_func' from simple.wasm to surface in processNames; got $names",
            names.contains("exported_func")
        )

        val items = findItems("exported_func")
        assertEquals("expected one navigation item for exported_func, got $items", 1, items.size)

        val item = items.single()
        assertEquals("exported_func", item.name)
        val pres = item.presentation!!
        assertEquals("exported_func", pres.presentableText)
        assertEquals(
            "function exports should be labelled 'function export · simple.wasm'",
            "function export · simple.wasm",
            pres.locationString
        )
        assertTrue(item.canNavigate())
        assertTrue(item.canNavigateToSource())
    }

    fun testPendingTabSelectionFlowReplaysLastDescription() {
        val state = org.jetbrains.hexana.HexViewStateHolder(project)
        state.requestSelectTabByDescription("Imports")
        state.requestSelectTabByDescription("Exports")
        // replay=1 so a late collector (BinaryEditorView's LaunchedEffect) sees the last value.
        assertEquals("Exports", state.pendingTabSelection.replayCache.lastOrNull())
    }
}
