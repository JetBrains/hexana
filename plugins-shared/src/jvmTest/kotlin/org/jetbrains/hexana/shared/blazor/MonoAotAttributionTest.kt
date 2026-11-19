package org.jetbrains.hexana.shared.blazor

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Splitting an AOT runtime module's functions by assembly, namespace, type and method. The metadata is
 * the real `sample-clr.exe`; the symbols are spelled the way Mono's AOT compiler spells them (checked
 * against a .NET 10 `RunAOTCompilation` publish, see `BlazorPublishOutputTest`).
 */
class MonoAotAttributionTest {
    private val metadata = assertNotNull(
        (createBinaryProvider(File("../parsing/native-fixtures/test-data/pe/sample-clr.exe").readBytes().toCommonByteBuffer(), "sample-clr.exe") as PeBinaryProvider)
            .clrMetadata,
    )
    private val assemblies = listOf(MonoAotAssembly("sample-clr", metadata), MonoAotAssembly("Other.Lib", null))

    private var nextIndex = 0
    private fun f(name: String, size: Int) = MonoAotFunction(nextIndex++, name, offset = 1000 + nextIndex * 100, size = size)

    private val functions = listOf(
        f("sample_clr_Hexana_Sample_Greeter_Greet_string", 300),
        f("sample_clr_Hexana_Sample_Greeter__ctor_string", 100),
        f("ut_sample_clr_Hexana_Sample_Greeter_Greet_string", 20),
        f("sample_clr_Hexana_Sample_Greeter_Nested__ctor", 10),
        f("sample_clr_Hexana_Sample_Program_Main_string__", 200),
        f("aot_instances_Hexana_Sample_Box_1_int_Map_U_INT_System_Func_2_int_int", 400),
        f("sample_clr_wrapper_delegate_invoke_System_Func_2_int_int", 50),
        f("Other_Lib_Some_Type_Method", 70),
        f("mono_runtime_invoke", 1000),
        f("ucnv_open", 500),
        f("SystemNative_Read", 30),
        f("wasm_invoke_vii", 5),
    )

    private fun BinarySizeNode.at(vararg path: String): BinarySizeNode =
        path.fold(this) { node, name -> assertNotNull(node.children.firstOrNull { it.name == name }, "no '$name' under '${node.name}': ${node.children.map { it.name }}") }

    @Test
    fun `sanitizes the way Mono spells symbols`() {
        assertEquals("Counter__c", MonoAotAttribution.sanitize("Counter+<>c"))
        assertEquals("Program___Main_d__0", MonoAotAttribution.sanitize("Program+<<Main>$>d__0"))
        assertEquals("TryGetInt64_long_", MonoAotAttribution.sanitize("TryGetInt64 (long&)"))
        assertEquals("corlib", MonoAotAttribution.symbolPrefix("System.Private.CoreLib"))
        assertEquals("Microsoft_AspNetCore_Components", MonoAotAttribution.symbolPrefix("Microsoft.AspNetCore.Components"))
    }

    @Test
    fun `groups managed code by assembly, namespace, type and method`() {
        val root = MonoAotAttribution.attribute(functions, assemblies)
        assertEquals(functions.sumOf { it.size }, root.size)
        val greeter = root.at(MonoAotGroups.MANAGED, "sample-clr", "Hexana.Sample", "Greeter")
        assertEquals(
            setOf("string Greet(string name)", "string Greet(string name)${MonoAotGroups.UNBOXING_STUB}", "void .ctor(string prefix)"),
            greeter.children.map { it.name }.toSet(),
        )
        assertEquals(420, greeter.size)
        // Nested types are their own row, named as in the Types tab.
        assertEquals(10, root.at(MonoAotGroups.MANAGED, "sample-clr", "Hexana.Sample", "Greeter+Nested").size)
        assertEquals("int Main(string[] args)", root.at(MonoAotGroups.MANAGED, "sample-clr", "Hexana.Sample", "Program").children.single().name)
        // No metadata: still the assembly's code, by symbol name.
        assertEquals("Some_Type_Method", root.at(MonoAotGroups.MANAGED, "Other.Lib", MonoAotGroups.UNRESOLVED).children.single().name)
    }

    @Test
    fun `generic instances go to the assembly that defines the generic type`() {
        val root = MonoAotAttribution.attribute(functions, assemblies)
        val map = root.at(MonoAotGroups.GENERIC_INSTANCES, "sample-clr", "Hexana.Sample", "Box<T>").children.single()
        assertEquals(400, map.size)
        assertTrue(map.name.startsWith("Map ["), map.name)
    }

    @Test
    fun `wrappers, the runtime, ICU and native libraries are told apart`() {
        val root = MonoAotAttribution.attribute(functions, assemblies)
        assertEquals(55, root.at(MonoAotGroups.WRAPPERS).size)
        assertEquals(50, root.at(MonoAotGroups.WRAPPERS, "sample-clr").size)
        assertEquals(1000, root.at(MonoAotGroups.RUNTIME).size)
        assertEquals(500, root.at(MonoAotGroups.ICU).size)
        assertEquals(30, root.at(MonoAotGroups.NATIVE_LIBRARIES).size)
        // Largest first, the way the Top tab sorts.
        assertEquals(root.children.sortedByDescending { it.size }, root.children)
    }

    @Test
    fun `only a module with AOT code for the app's assemblies gets a report and a tab`() {
        assertTrue(MonoAotAttribution.isAotModule(functions.map { it.name }, listOf("sample-clr")))
        assertFalse(MonoAotAttribution.isAotModule(listOf("mono_wasm_load_runtime", "mini_init"), listOf("sample-clr")))

        val report = MonoAotReport(MonoAotAttribution.attribute(functions, assemblies), moduleSize = 10_000, withoutMetadata = listOf("Other.Lib"))
        val prologue = monoAotPrologue(report)
        assertTrue(prologue.startsWith("Ahead-of-time compiled .NET app:"), prologue)
        assertTrue("No metadata for Other.Lib" in prologue, prologue)
        assertTrue("Largest: sample-clr" in prologue, prologue)

        val provider = org.jetbrains.hexana.providers.WasmBinaryProvider(
            File("../wasmParser/src/commonTest/resources/c/utf8-c.wasm").readBytes().toCommonByteBuffer(),
        )
        val tabs = BlazorBundleTableTabsProvider()
        assertEquals(listOf(DOTNET_AOT_TAB_NAME), tabs.availableTabNames(provider, TableTabsContext(monoAot = report)).first)
        val tab = assertNotNull(tabs.singleTab(DOTNET_AOT_TAB_NAME, provider, TableTabsContext(monoAot = report)))
        assertEquals(report.root.children.size, tab.rowCount)
        assertNotNull(tab.treemapData)
    }
}
