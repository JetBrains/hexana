package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.WasmBinaryWriter
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.api
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.arr
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.call
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.genericProvider
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.int
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.member
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.obj
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.str
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the binary.functions(), binary.imports(), and binary.exports() facade members.
 *
 * The fixture module has:
 *   - 2 imported functions (module "env", names "add" and "log")
 *   - 3 locally defined functions (body sizes 3, 5, 7 bytes respectively)
 *   - a name section naming global func 0 ("env.add") and global func 2 ("localA")
 *     leaving global func 1 ("env.log"), 3, and 4 unnamed in the name section
 *   - 2 exports: "addExport" (func, index 0) and "localExport" (func, index 2)
 *   - 1 memory (exported as "mem")
 *
 * Name section coverage: global func 0 -> "env.add", global func 2 -> "localA".
 * Global func 1 and code functions at global idx 3, 4 have no entry.
 * importFuncCount = 2.
 */
class ScriptBinaryFacadeTest {

    // -- fixture ---------------------------------------------------------------

    private fun richProvider(): WasmBinaryProvider = WasmBinaryProvider(richWasmBytes().toCommonByteBuffer())

    private fun richWasmBytes(): ByteArray {
        val writer = WasmBinaryWriter()
        // magic + version
        writer.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))

        // Type section (section id 1): 1 type: () -> ()
        val types = WasmBinaryWriter()
        types.writeULEB128(1)   // 1 type
        types.writeByte(0x60)   // func
        types.writeULEB128(0)   // 0 params
        types.writeULEB128(0)   // 0 results
        writer.writeSection(1, types.toByteArray())

        // Import section (section id 2): 2 func imports (env.add, env.log)
        val imports = WasmBinaryWriter()
        imports.writeULEB128(2)  // 2 imports
        imports.writeName("env")
        imports.writeName("add")
        imports.writeByte(0x00)  // func
        imports.writeULEB128(0)  // type index 0
        imports.writeName("env")
        imports.writeName("log")
        imports.writeByte(0x00)  // func
        imports.writeULEB128(0)  // type index 0
        writer.writeSection(2, imports.toByteArray())

        // Function section (section id 3): 3 local functions, all type 0
        val functions = WasmBinaryWriter()
        functions.writeULEB128(3)
        functions.writeULEB128(0)
        functions.writeULEB128(0)
        functions.writeULEB128(0)
        writer.writeSection(3, functions.toByteArray())

        // Memory section (section id 5): 1 memory
        val mem = WasmBinaryWriter()
        mem.writeULEB128(1)   // 1 memory
        mem.writeByte(0x00)   // no max
        mem.writeULEB128(1)   // min 1 page
        writer.writeSection(5, mem.toByteArray())

        // Export section (section id 7): 3 exports
        val exports = WasmBinaryWriter()
        exports.writeULEB128(3)
        exports.writeName("addExport")
        exports.writeByte(0x00)  // func
        exports.writeULEB128(0)  // func index 0 (import "env.add")
        exports.writeName("localExport")
        exports.writeByte(0x00)  // func
        exports.writeULEB128(2)  // func index 2 (first local)
        exports.writeName("mem")
        exports.writeByte(0x02)  // memory
        exports.writeULEB128(0)  // memory index 0
        writer.writeSection(7, exports.toByteArray())

        // Code section (section id 10): 3 local functions with bodies of 3, 5, 7 bytes
        val code = WasmBinaryWriter()
        code.writeULEB128(3)  // 3 bodies

        // body 0: 3 bytes (locals count=0, nop, end)
        val body0 = WasmBinaryWriter()
        body0.writeULEB128(0)  // 0 locals
        body0.writeByte(0x01)  // nop
        body0.writeByte(0x0B)  // end
        code.writeULEB128(body0.size())
        code.writeBytes(body0.toByteArray())

        // body 1: 5 bytes (locals count=0, nop, nop, nop, end)
        val body1 = WasmBinaryWriter()
        body1.writeULEB128(0)  // 0 locals
        body1.writeByte(0x01)  // nop
        body1.writeByte(0x01)  // nop
        body1.writeByte(0x01)  // nop
        body1.writeByte(0x0B)  // end
        code.writeULEB128(body1.size())
        code.writeBytes(body1.toByteArray())

        // body 2: 7 bytes (locals count=0, nop x5, end)
        val body2 = WasmBinaryWriter()
        body2.writeULEB128(0)  // 0 locals
        body2.writeByte(0x01)  // nop
        body2.writeByte(0x01)  // nop
        body2.writeByte(0x01)  // nop
        body2.writeByte(0x01)  // nop
        body2.writeByte(0x01)  // nop
        body2.writeByte(0x0B)  // end
        code.writeULEB128(body2.size())
        code.writeBytes(body2.toByteArray())

        writer.writeSection(10, code.toByteArray())

        // Custom name section (section id 0, name "name")
        // Name section subsection 1 (function names)
        // We name global idx 0 -> "env.add", global idx 2 -> "localA"
        // (global idx 1 and 3, 4 are intentionally left unnamed)
        val nameSub = WasmBinaryWriter()
        nameSub.writeULEB128(2)  // 2 entries
        nameSub.writeULEB128(0)  // func 0
        nameSub.writeName("env.add")
        nameSub.writeULEB128(2)  // func 2
        nameSub.writeName("localA")

        val nameSection = WasmBinaryWriter()
        nameSection.writeName("name")  // custom section name
        nameSection.writeByte(0x01)    // subsection id: function names
        nameSection.writeULEB128(nameSub.size())
        nameSection.writeBytes(nameSub.toByteArray())

        writer.writeSection(0, nameSection.toByteArray())

        return writer.toByteArray()
    }

    // helper: call binary.functions() with an optional Obj argument
    private fun callFunctions(provider: WasmBinaryProvider, vararg opts: Pair<String, ScriptValue>): ScriptValue {
        val binary = api(provider)
        val fn = member(binary, "functions")
        if (opts.isEmpty()) return call(fn)
        val optObj = ScriptValue.Obj(linkedMapOf(*opts.map { (k, v) -> k to { v } }.toTypedArray()))
        return call(fn, optObj)
    }

    private fun callImports(provider: WasmBinaryProvider): ScriptValue {
        val binary = api(provider)
        return call(member(binary, "imports"))
    }

    private fun callExports(provider: WasmBinaryProvider): ScriptValue {
        val binary = api(provider)
        return call(member(binary, "exports"))
    }

    // -- result shape: functions() -------------------------------------------

    @Test
    fun functionsPageHasExpectedTopLevelFields() {
        val result = obj(callFunctions(richProvider()))
        assertTrue("items" in result.members, "page must have 'items'")
        assertTrue("total" in result.members, "page must have 'total'")
        assertTrue("nextOffset" in result.members, "page must have 'nextOffset'")
    }

    @Test
    fun functionItemHasExpectedFieldsForImport() {
        val result = obj(callFunctions(richProvider()))
        val items = arr(result.members["items"]!!.invoke())
        assertTrue(items.size > 0, "items must not be empty")
        // first item is an import (env.add, global idx 0)
        val item = obj(items.at(0))
        assertTrue("funcidx" in item.members, "item must have funcidx")
        assertTrue("name" in item.members, "item must have name")
        assertTrue("source" in item.members, "item must have source")
        assertTrue("importModule" in item.members, "import item must have importModule")
        assertTrue("importName" in item.members, "import item must have importName")
    }

    @Test
    fun functionItemHasExpectedFieldsForCodeFunction() {
        val result = obj(callFunctions(richProvider()))
        val items = arr(result.members["items"]!!.invoke())
        // imports are first (2), so item at index 2 is the first code function
        val item = obj(items.at(2))
        assertTrue("funcidx" in item.members, "item must have funcidx")
        assertTrue("name" in item.members, "item must have name")
        assertTrue("source" in item.members, "item must have source")
        assertTrue("codeOffset" in item.members, "code item must have codeOffset")
        assertTrue("codeSize" in item.members, "code item must have codeSize")
    }

    @Test
    fun importItemsHaveCorrectSourceAndModuleFields() {
        val provider = richProvider()
        val result = obj(callFunctions(provider))
        val items = arr(result.members["items"]!!.invoke())

        // global idx 0: env.add, named in name section
        val item0 = obj(items.at(0))
        assertEquals("import", (item0.members["source"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("env", (item0.members["importModule"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("add", (item0.members["importName"]!!.invoke() as ScriptValue.Str).value)
        assertEquals(0, int(item0.members["funcidx"]!!.invoke()))
    }

    @Test
    fun codeItemHasCorrectFuncidxArithmetic() {
        // First code function's funcidx must equal importFuncCount (2).
        val provider = richProvider()
        val result = obj(callFunctions(provider))
        val items = arr(result.members["items"]!!.invoke())
        val firstCodeItem = obj(items.at(2))  // 2 imports, then code functions
        assertEquals(2, int(firstCodeItem.members["funcidx"]!!.invoke()),
            "first code function's funcidx must equal importFuncCount")
        assertEquals("code", (firstCodeItem.members["source"]!!.invoke() as ScriptValue.Str).value)
    }

    // -- total and counts ----------------------------------------------------

    @Test
    fun totalReflectsAllFunctions() {
        // 2 imports + 3 code = 5 total
        val result = obj(callFunctions(richProvider()))
        assertEquals(5, int(result.members["total"]!!.invoke()))
    }

    @Test
    fun nextOffsetIsNullWhenNoMoreItems() {
        val result = obj(callFunctions(richProvider()))
        assertEquals(ScriptValue.Null, result.members["nextOffset"]!!.invoke())
    }

    // -- pagination ----------------------------------------------------------

    @Test
    fun paginationWithLimitReturnsCorrectPage() {
        val provider = richProvider()
        val page1 = obj(callFunctions(provider, "limit" to ScriptValue.Num(2)))
        val items1 = arr(page1.members["items"]!!.invoke())
        assertEquals(2, items1.size)
        assertEquals(5, int(page1.members["total"]!!.invoke()))

        val next = page1.members["nextOffset"]!!.invoke()
        assertTrue(next is ScriptValue.Num, "nextOffset must be a number when more items remain")
        val nextVal = (next as ScriptValue.Num).value.toInt()
        assertEquals(2, nextVal)

        // fetch page 2 using nextOffset
        val page2 = obj(callFunctions(provider, "offset" to ScriptValue.Num(nextVal), "limit" to ScriptValue.Num(2)))
        val items2 = arr(page2.members["items"]!!.invoke())
        assertEquals(2, items2.size)
        assertEquals(5, int(page2.members["total"]!!.invoke()))

        // page 3: last item
        val next2 = page2.members["nextOffset"]!!.invoke() as ScriptValue.Num
        val page3 = obj(callFunctions(provider, "offset" to ScriptValue.Num(next2.value.toInt()), "limit" to ScriptValue.Num(2)))
        val items3 = arr(page3.members["items"]!!.invoke())
        assertEquals(1, items3.size)
        assertEquals(ScriptValue.Null, page3.members["nextOffset"]!!.invoke(),
            "last page nextOffset must be null/absent")
    }

    @Test
    fun totalIsConstantAcrossPages() {
        val provider = richProvider()
        val page1 = obj(callFunctions(provider, "limit" to ScriptValue.Num(2)))
        val page2 = obj(callFunctions(provider, "offset" to ScriptValue.Num(2), "limit" to ScriptValue.Num(2)))
        assertEquals(
            int(page1.members["total"]!!.invoke()),
            int(page2.members["total"]!!.invoke()),
            "total must be the same across all pages",
        )
    }

    // -- filters -------------------------------------------------------------

    @Test
    fun minBodySizeExcludesImportsAndSmallBodies() {
        // body sizes are 3, 5, 7. minBodySize=5 keeps bodies of 5 and 7.
        val result = obj(callFunctions(richProvider(), "minBodySize" to ScriptValue.Num(5)))
        val items = arr(result.members["items"]!!.invoke())
        // 2 code functions with body >= 5, imports excluded
        assertEquals(2, items.size)
        assertEquals(2, int(result.members["total"]!!.invoke()))
        // all returned items must be code functions
        for (i in 0 until items.size) {
            val item = obj(items.at(i))
            assertEquals("code", (item.members["source"]!!.invoke() as ScriptValue.Str).value)
        }
    }

    @Test
    fun minBodySizeOfOneExcludesImports() {
        val result = obj(callFunctions(richProvider(), "minBodySize" to ScriptValue.Num(1)))
        val items = arr(result.members["items"]!!.invoke())
        assertEquals(3, items.size, "minBodySize=1 must exclude all 2 imports and return 3 code functions")
        assertEquals(3, int(result.members["total"]!!.invoke()))
    }

    @Test
    fun nameFilterIsCaseInsensitiveSubstring() {
        // "env.add" is named at global idx 0; searching for "ADD" must find it
        val result = obj(callFunctions(richProvider(), "name" to ScriptValue.Str("ADD")))
        val items = arr(result.members["items"]!!.invoke())
        assertEquals(1, items.size)
        assertEquals(1, int(result.members["total"]!!.invoke()))
        val item = obj(items.at(0))
        assertEquals(0, int(item.members["funcidx"]!!.invoke()))
    }

    @Test
    fun unnamedFilterReturnsOnlyFunctionsWithoutResolvedName() {
        // Global idx 0 -> "env.add", global idx 2 -> "localA" are named.
        // Unnamed: global idx 1 (env.log), global idx 3, global idx 4 = 3 unnamed.
        val result = obj(callFunctions(richProvider(), "unnamed" to ScriptValue.Bool(true)))
        val items = arr(result.members["items"]!!.invoke())
        assertEquals(3, items.size)
        assertEquals(3, int(result.members["total"]!!.invoke()))
        // all returned items must have null name
        for (i in 0 until items.size) {
            val item = obj(items.at(i))
            assertEquals(ScriptValue.Null, item.members["name"]!!.invoke(),
                "unnamed filter must only return functions with null name")
        }
    }

    // -- imports() -----------------------------------------------------------

    @Test
    fun importsReturnsExpectedCount() {
        val result = arr(callImports(richProvider()))
        assertEquals(2, result.size)
    }

    @Test
    fun importItemHasCorrectFields() {
        val result = arr(callImports(richProvider()))
        val entry = obj(result.at(0))
        assertTrue("module" in entry.members, "import must have 'module'")
        assertTrue("name" in entry.members, "import must have 'name'")
        assertTrue("kind" in entry.members, "import must have 'kind'")
        assertTrue("index" in entry.members, "import must have 'index'")
    }

    @Test
    fun importContentMatchesFixtureGroundTruth() {
        val result = arr(callImports(richProvider()))
        // First import: env.add, func, funcidx 0 (index = position in the kind's index space)
        val entry0 = obj(result.at(0))
        assertEquals("env", (entry0.members["module"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("add", (entry0.members["name"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("func", (entry0.members["kind"]!!.invoke() as ScriptValue.Str).value)
        assertEquals(0, int(entry0.members["index"]!!.invoke()))
        // Second import: env.log, func, funcidx 1
        val entry1 = obj(result.at(1))
        assertEquals("env", (entry1.members["module"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("log", (entry1.members["name"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("func", (entry1.members["kind"]!!.invoke() as ScriptValue.Str).value)
        assertEquals(1, int(entry1.members["index"]!!.invoke()))
    }

    // -- exports() -----------------------------------------------------------

    @Test
    fun exportsReturnsExpectedCount() {
        val result = arr(callExports(richProvider()))
        assertEquals(3, result.size)
    }

    @Test
    fun exportItemHasCorrectFields() {
        val result = arr(callExports(richProvider()))
        val entry = obj(result.at(0))
        assertTrue("name" in entry.members, "export must have 'name'")
        assertTrue("kind" in entry.members, "export must have 'kind'")
        assertTrue("index" in entry.members, "export must have 'index'")
    }

    @Test
    fun exportContentMatchesFixtureGroundTruth() {
        val result = arr(callExports(richProvider()))
        // addExport: func, index 0
        val e0 = obj(result.at(0))
        assertEquals("addExport", (e0.members["name"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("func", (e0.members["kind"]!!.invoke() as ScriptValue.Str).value)
        assertEquals(0, int(e0.members["index"]!!.invoke()))
        // localExport: func, index 2
        val e1 = obj(result.at(1))
        assertEquals("localExport", (e1.members["name"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("func", (e1.members["kind"]!!.invoke() as ScriptValue.Str).value)
        assertEquals(2, int(e1.members["index"]!!.invoke()))
        // mem: memory, index 0
        val e2 = obj(result.at(2))
        assertEquals("mem", (e2.members["name"]!!.invoke() as ScriptValue.Str).value)
        assertEquals("memory", (e2.members["kind"]!!.invoke() as ScriptValue.Str).value)
        assertEquals(0, int(e2.members["index"]!!.invoke()))
    }

    // -- non-Wasm provider ---------------------------------------------------

    @Test
    fun functionsReturnsEmptyPageForNonWasm() {
        val binary = api(genericProvider(byteArrayOf(1, 2, 3)))
        val result = obj(call(member(binary, "functions")))
        assertEquals(0, int(result.members["total"]!!.invoke()))
        assertEquals(0, arr(result.members["items"]!!.invoke()).size)
    }

    @Test
    fun importsReturnsEmptyArrayForNonWasm() {
        val binary = api(genericProvider(byteArrayOf(1, 2, 3)))
        val result = arr(call(member(binary, "imports")))
        assertEquals(0, result.size)
    }

    @Test
    fun exportsReturnsEmptyArrayForNonWasm() {
        val binary = api(genericProvider(byteArrayOf(1, 2, 3)))
        val result = arr(call(member(binary, "exports")))
        assertEquals(0, result.size)
    }

    // -- funcidx arithmetic --------------------------------------------------

    @Test
    fun firstCodeFunctionFuncidxEqualsImportFuncCount() {
        val provider = richProvider()
        val importFuncCount = provider.importFuncCount
        assertEquals(2, importFuncCount)

        val result = obj(callFunctions(provider))
        val items = arr(result.members["items"]!!.invoke())
        // Find first code item
        val codeItem = (0 until items.size)
            .map { obj(items.at(it)) }
            .first { (it.members["source"]!!.invoke() as ScriptValue.Str).value == "code" }
        assertEquals(importFuncCount, int(codeItem.members["funcidx"]!!.invoke()))
    }
}
