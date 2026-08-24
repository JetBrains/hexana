package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.api
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.arr
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.assertNonEmpty
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.callMember
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.elfProviderWithStrings
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.member
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.obj
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.str
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.stringList
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.wasmProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the documented API and the implemented API in step.
 *
 * The reference in [ScriptApiReference] feeds the bundled `.d.ts`, the
 * Add Script Tab dialogs in both plugins, the starter template, and the MCP tool
 * guidance. Before it existed each of those was hand-written and they had already
 * drifted; these assertions are what stop that from happening again.
 */
class ScriptApiReferenceTest {

    private fun assertShapeMatches(shapeKey: String, value: ScriptValue) {
        val shape = ScriptApiReference.shape(shapeKey)
        val actual = obj(value).members.keys
        assertEquals(
            shape.memberNames.sorted(),
            actual.sorted(),
            "documented members of '$shapeKey' (${shape.typeName}) do not match what " +
                "scriptBinaryApi exposes — update ScriptApiReference.kt",
        )
    }

    @Test
    fun binaryShapeIsFullyDocumented() {
        assertShapeMatches(ScriptApiReference.BINARY_SHAPE, api(wasmProvider(), "fixture.wasm"))
    }

    @Test
    fun bytesAndIndicesShapesAreFullyDocumented() {
        val bytes = member(api(wasmProvider()), "bytes")
        assertShapeMatches(ScriptApiReference.BYTES_SHAPE, bytes)
        assertShapeMatches(ScriptApiReference.INDICES_SHAPE, member(bytes, "indices"))
    }

    @Test
    fun structureShapeIsFullyDocumented() {
        val structure = assertNonEmpty(member(api(wasmProvider()), "structure"), "binary.structure")
        assertShapeMatches(ScriptApiReference.STRUCTURE_SHAPE, structure.at(0))
    }

    @Test
    fun stringShapeIsFullyDocumented() {
        val strings = assertNonEmpty(member(api(elfProviderWithStrings()), "strings"), "binary.strings")
        assertShapeMatches(ScriptApiReference.STRING_SHAPE, strings.at(0))
    }

    @Test
    fun sizeNodeShapeIsFullyDocumentedIncludingChildren() {
        val root = member(api(wasmProvider()), "sizeAnalysis")
        assertShapeMatches(ScriptApiReference.SIZE_NODE_SHAPE, root)

        val children = arr(member(root, "children"))
        assertTrue(children.size > 0, "expected the Wasm size tree to have children")
        assertShapeMatches(ScriptApiReference.SIZE_NODE_SHAPE, children.at(0))
    }

    @Test
    fun tabShapeIsFullyDocumented() {
        val binary = api(wasmProvider())
        val tabNames = stringList(member(binary, "tabs"))
        assertTrue(tabNames.isNotEmpty(), "expected the Wasm fixture to advertise at least one tab")
        assertShapeMatches(ScriptApiReference.TAB_SHAPE, callMember(binary, "tab", str(tabNames.first())))
    }

    @Test
    fun everyDocumentedShapeIsCoveredByThisTest() {
        val covered = setOf(
            ScriptApiReference.BINARY_SHAPE,
            ScriptApiReference.BYTES_SHAPE,
            ScriptApiReference.INDICES_SHAPE,
            ScriptApiReference.STRUCTURE_SHAPE,
            ScriptApiReference.STRING_SHAPE,
            ScriptApiReference.SIZE_NODE_SHAPE,
            ScriptApiReference.TAB_SHAPE,
        )
        assertEquals(
            covered.sorted(),
            ScriptApiReference.shapes.map { it.shape }.sorted(),
            "a shape was added to ScriptApiReference without a matching assertion here",
        )
    }

    @Test
    fun globalsMatchTheOutputBindings() {
        val bound = ScriptOutput {}.bindings().keys
        val documented = ScriptApiReference.globals.map { it.name }.toSet()
        assertEquals(
            (bound + "binary").sorted(),
            documented.sorted(),
            "documented globals do not match the bound globals",
        )
    }

    @Test
    fun everyMemberCarriesADocumentedSignatureAndSummary() {
        val entries = ScriptApiReference.globals + ScriptApiReference.shapes.flatMap { it.members }
        for (entry in entries) {
            assertTrue(entry.signature.isNotBlank(), "'${entry.name}' has no signature")
            assertTrue(entry.summary.isNotBlank(), "'${entry.name}' has no summary")
            assertTrue(
                entry.signature.contains(entry.name),
                "'${entry.name}' signature '${entry.signature}' does not mention the member name",
            )
        }
    }

    @Test
    fun generatedDtsDeclaresEveryGlobalAndShape() {
        val dts = ScriptApiReference.dts()
        for (entry in ScriptApiReference.globals) {
            assertTrue(dts.contains(entry.signature), "the .d.ts is missing '${entry.signature}'")
        }
        for (shape in ScriptApiReference.shapes) {
            assertTrue(
                dts.contains("interface ${shape.typeName} {"),
                "the .d.ts is missing 'interface ${shape.typeName}'",
            )
            for (member in shape.members) {
                assertTrue(
                    dts.contains("  ${member.signature};"),
                    "the .d.ts is missing '${shape.typeName}.${member.name}'",
                )
            }
        }
    }

    @Test
    fun generatedDtsHasNoPrematurelyClosedDocComment() {
        // A summary or example containing `*/` would close its JSDoc block early
        // and leave the rest of the comment as broken declarations.
        for (entry in ScriptApiReference.globals + ScriptApiReference.shapes.flatMap { it.members }) {
            assertTrue(
                !entry.summary.contains("*/") && entry.example?.contains("*/") != true,
                "'${entry.name}' documentation contains */ and would break the generated .d.ts",
            )
        }

        // Every opened doc comment must close exactly once.
        val dts = ScriptApiReference.dts()
        assertEquals(
            Regex("""/\*\*""").findAll(dts).count(),
            Regex("""\*/""").findAll(dts).count(),
            "unbalanced doc comment delimiters in the generated .d.ts",
        )
    }

    @Test
    fun generatedDtsStaysSmallEnoughToRead() {
        // The point of the curated reference is that a user can read it. The
        // previous reflection dump was ~275 KB of internal Kotlin types.
        val dts = ScriptApiReference.dts()
        assertTrue(
            dts.length < 32_000,
            "the curated .d.ts grew to ${dts.length} characters; it is meant to stay skimmable",
        )
    }

    @Test
    fun starterTemplateOnlyUsesDocumentedGlobals() {
        val template = ScriptApiReference.starterTemplate()
        for (global in listOf("print(", "table(", "row(", "binary.")) {
            assertTrue(template.contains(global), "the starter template never demonstrates '$global'")
        }
    }

    @Test
    fun summaryLinesCoverTheOutputGlobalsAndBinaryEntryPoints() {
        val lines = ScriptApiReference.summaryLines()
        assertTrue(lines.any { it.startsWith("print(") }, "print is missing from the dialog summary")
        assertTrue(lines.any { it.startsWith("table(") }, "table is missing from the dialog summary")
        assertTrue(lines.any { it.startsWith("row(") }, "row is missing from the dialog summary")
        assertTrue(lines.any { it.startsWith("binary.tabs") }, "binary.tabs is missing from the dialog summary")
        assertTrue(lines.any { it.startsWith("binary.raw") }, "binary.raw is missing from the dialog summary")
    }
}
