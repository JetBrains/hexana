package org.jetbrains.hexana.cli.scripting.sarif

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckRegistrationInfo
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SarifRendererTest {

    // --- schema validator setup ------------------------------------------------------------------

    private val schema by lazy {
        val schemaText = requireNotNull(
            SarifRendererTest::class.java.classLoader.getResourceAsStream("sarif-2.1.0-schema.json")
        ) { "sarif-2.1.0-schema.json not found in test resources" }
            .bufferedReader().readText()
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
            .getSchema(schemaText, InputFormat.JSON)
    }

    private fun assertSchemaValid(json: String) {
        val errors = schema.validate(json, InputFormat.JSON)
        assertTrue(errors.isEmpty(), "SARIF schema violations:\n${errors.joinToString("\n") { "  - $it" }}")
    }

    // --- fixture data ---------------------------------------------------------------------------

    private val allChecks = listOf(
        CheckRegistrationInfo(id = "check.b", severity = CheckSeverity.WARNING, description = "B check"),
        CheckRegistrationInfo(id = "check.a", severity = CheckSeverity.ERROR, description = "A check"),
        CheckRegistrationInfo(id = "check.c", severity = CheckSeverity.INFO, description = null),
    )

    private val allFindings = listOf(
        SarifFinding(
            finding = CheckFinding(checkId = "check.a", severity = CheckSeverity.ERROR, message = "error message",
                anchor = ScriptAnchor.Function(funcidx = 5)),
        ),
        SarifFinding(
            finding = CheckFinding(checkId = "check.b", severity = CheckSeverity.WARNING, message = "warning message",
                anchor = ScriptAnchor.ByteRange(offset = 100, length = 32)),
        ),
        SarifFinding(
            finding = CheckFinding(checkId = "check.c", severity = CheckSeverity.INFO, message = "info message",
                anchor = null),
        ),
    )

    // --- tests ----------------------------------------------------------------------------------

    @Test
    fun syntheticSchemaValid() {
        val sarif = renderSarif(
            checks = allChecks,
            findings = allFindings,
            toolVersion = "1.2.3",
        )
        assertSchemaValid(sarif)
    }

    @Test
    fun rulesAreSortedById() {
        val sarif = renderSarif(checks = allChecks, findings = emptyList())
        val root = Json.parseToJsonElement(sarif).jsonObject
        val rules = root["runs"]!!.jsonArray[0].jsonObject["tool"]!!
            .jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray
        val ids = rules.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("check.a", "check.b", "check.c"), ids)
    }

    @Test
    fun resultsAreSortedByRuleIdThenAnchorThenMessage() {
        val findings = listOf(
            SarifFinding(CheckFinding("check.b", CheckSeverity.WARNING, "msg z", ScriptAnchor.Function(10))),
            SarifFinding(CheckFinding("check.a", CheckSeverity.ERROR, "msg b", ScriptAnchor.Function(1))),
            SarifFinding(CheckFinding("check.a", CheckSeverity.ERROR, "msg a", ScriptAnchor.Function(1))),
            SarifFinding(CheckFinding("check.a", CheckSeverity.ERROR, "msg c", null)),
        )
        val sarif = renderSarif(checks = allChecks, findings = findings)
        val root = Json.parseToJsonElement(sarif).jsonObject
        val results = root["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray
        val ruleIds = results.map { it.jsonObject["ruleId"]!!.jsonPrimitive.content }
        val messages = results.map { it.jsonObject["message"]!!.jsonObject["text"]!!.jsonPrimitive.content }

        assertEquals(listOf("check.a", "check.a", "check.a", "check.b"), ruleIds)
        // check.a: function:1 -> file (null anchor comes later in string sort)
        // within function:1: msg a < msg b
        assertEquals("msg a", messages[0])
        assertEquals("msg b", messages[1])
        assertEquals("msg c", messages[2]) // null anchor = "file" sorts after "function:1"
        assertEquals("msg z", messages[3])
    }

    @Test
    fun exactLevelValues() {
        val sarif = renderSarif(checks = allChecks, findings = allFindings)
        val root = Json.parseToJsonElement(sarif).jsonObject
        val results = root["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray

        // Results sorted: check.a (error), check.b (warning), check.c (info)
        val levels = results.map { it.jsonObject["level"]!!.jsonPrimitive.content }
        assertEquals(listOf("error", "warning", "note"), levels)
    }

    @Test
    fun byteRangeAnchorPhysicalLocation() {
        val finding = SarifFinding(
            CheckFinding("check.b", CheckSeverity.WARNING, "msg", ScriptAnchor.ByteRange(offset = 200, length = 64))
        )
        val sarif = renderSarif(checks = allChecks, findings = listOf(finding))
        val result = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        val physLoc = result["locations"]!!.jsonArray[0].jsonObject["physicalLocation"]!!.jsonObject
        val region = physLoc["region"]!!.jsonObject
        assertEquals(200, region["byteOffset"]!!.jsonPrimitive.content.toLong())
        assertEquals(64, region["byteLength"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun functionAnchorLogicalLocation() {
        val finding = SarifFinding(
            CheckFinding("check.a", CheckSeverity.ERROR, "msg", ScriptAnchor.Function(funcidx = 7))
        )
        val sarif = renderSarif(
            checks = allChecks,
            findings = listOf(finding),
            functionName = { idx -> if (idx == 7L) "myFunction" else null },
        )
        val result = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        val loc = result["locations"]!!.jsonArray[0].jsonObject
        val logicalLocs = loc["logicalLocations"]!!.jsonArray
        assertEquals(1, logicalLocs.size)
        assertEquals("myFunction", logicalLocs[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("function", logicalLocs[0].jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun functionAnchorFallbackName() {
        val finding = SarifFinding(
            CheckFinding("check.a", CheckSeverity.ERROR, "msg", ScriptAnchor.Function(funcidx = 99))
        )
        val sarif = renderSarif(checks = allChecks, findings = listOf(finding))
        val result = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        val logicalLocs = result["locations"]!!.jsonArray[0].jsonObject["logicalLocations"]!!.jsonArray
        assertEquals("func[99]", logicalLocs[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun nullAnchorNoLocations() {
        val finding = SarifFinding(
            CheckFinding("check.c", CheckSeverity.INFO, "msg", anchor = null)
        )
        val sarif = renderSarif(checks = allChecks, findings = listOf(finding))
        val result = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        assertTrue(!result.containsKey("locations"), "null anchor must produce no locations key")
    }

    @Test
    fun partialFingerprintsAlwaysPresent() {
        val sarif = renderSarif(checks = allChecks, findings = allFindings)
        val results = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray
        for (result in results) {
            val fp = result.jsonObject["partialFingerprints"]
            assertNotNull(fp, "partialFingerprints must always be present")
            assertNotNull(
                fp.jsonObject["primaryLocationLineHash"],
                "primaryLocationLineHash must be present"
            )
        }
    }

    @Test
    fun baselineStateMapping() {
        val findings = listOf(
            SarifFinding(
                CheckFinding("check.a", CheckSeverity.ERROR, "new", null),
                baselineState = BaselineState.NEW,
            ),
            SarifFinding(
                CheckFinding("check.b", CheckSeverity.WARNING, "unchanged", null),
                baselineState = BaselineState.UNCHANGED,
            ),
            SarifFinding(
                CheckFinding("check.c", CheckSeverity.INFO, "absent", null),
                baselineState = BaselineState.ABSENT,
            ),
        )
        val sarif = renderSarif(checks = allChecks, findings = findings)
        val results = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray
        val states = results.map { it.jsonObject["baselineState"]!!.jsonPrimitive.content }
        // Sorted: check.a|file -> new, check.b|file -> unchanged, check.c|file -> absent
        assertEquals(listOf("new", "unchanged", "absent"), states)
    }

    @Test
    fun absentResultHasNoteLevel() {
        val findings = listOf(
            SarifFinding(
                CheckFinding("check.a", CheckSeverity.ERROR, "absent error", null),
                baselineState = BaselineState.ABSENT,
            ),
        )
        val sarif = renderSarif(checks = allChecks, findings = findings)
        val result = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        assertEquals("note", result["level"]!!.jsonPrimitive.content)
    }

    @Test
    fun renderIsDeterministic() {
        val sarif1 = renderSarif(checks = allChecks, findings = allFindings, toolVersion = "0.1.0")
        val sarif2 = renderSarif(checks = allChecks, findings = allFindings, toolVersion = "0.1.0")
        assertEquals(sarif1, sarif2, "renderSarif must be byte-identical on repeated calls")
    }

    // --- real fixture tests ----------------------------------------------------------------------

    private val fixtureBase: String = run {
        // Tests run with cwd = <repo>/cli/scripting; step up two levels to reach the repo root.
        val fromModule = Paths.get("..", "..", "examples", "scripting-playground", "bin")
        fromModule.toAbsolutePath().normalize().toString()
    }

    private fun requireFixtureBytes(name: String): ByteArray {
        val f = File("$fixtureBase/$name")
        if (!f.exists()) {
            throw AssertionError(
                "Fixture not found: ${f.absolutePath}. " +
                "Copy examples/scripting-playground/bin/$name from the repo root into the working tree."
            )
        }
        return f.readBytes()
    }

    /**
     * app-debug.wasm (4703 bytes, built with --no-wasm-opt so the DWARF line programs
     * survive; wasm-opt post-link drops every line-program row):
     *   - "name" section present: funcidx 3 resolves to compute_fib via the name
     *     section step of resolveFunctionSymbols' chain (mcp/core CrashResolverQuery).
     *   - Full .debug_line tables; DWARF paths are project-root-relative via
     *     -ffile-prefix-map. Observed resolution for funcidx 3: src/app/main.c:21
     *     (compute_fib is defined at line 20; its body's first mapped row is 21).
     */
    @Test
    fun appDebugWasmSchemaValid() {
        val bytes = requireFixtureBytes("app-debug.wasm")
        val resolver = SarifSourceResolver.from(bytes)
        assertNotNull(resolver, "SarifSourceResolver.from must succeed for app-debug.wasm")

        // funcidx 3 = compute_fib (no imports; wasm-objdump confirms: func[3] <compute_fib>)
        val computeFibIdx = 3L
        val checks = listOf(CheckRegistrationInfo("fixture.check", CheckSeverity.WARNING, "fixture"))
        val findings = listOf(
            SarifFinding(
                CheckFinding("fixture.check", CheckSeverity.WARNING, "test finding",
                    ScriptAnchor.Function(funcidx = computeFibIdx)),
            ),
        )
        val sarif = renderSarif(
            checks = checks,
            findings = findings,
            toolVersion = "test",
            functionName = resolver::functionName,
            sourceLocation = resolver::sourceLocation,
        )
        assertSchemaValid(sarif)

        val result = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject

        // Logical location: name section resolves compute_fib.
        val locs = result["locations"]?.jsonArray
        assertNotNull(locs, "locations must be present for Function anchor")
        assertTrue(locs.isNotEmpty())
        val logicalLocs = locs[0].jsonObject["logicalLocations"]
        assertNotNull(logicalLocs, "logicalLocations must be present for Function anchor")
        val funcName = logicalLocs!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("compute_fib", funcName,
            "funcidx 3 must resolve to compute_fib via the name section")

        // Physical location: DWARF line table resolves the function's first mapped row.
        val physLoc = locs[0].jsonObject["physicalLocation"]
        assertNotNull(physLoc, "DWARF physicalLocation must be present for app-debug.wasm")
        val uri = physLoc!!.jsonObject["artifactLocation"]!!.jsonObject["uri"]!!.jsonPrimitive.content
        val startLine = physLoc.jsonObject["region"]!!.jsonObject["startLine"]!!.jsonPrimitive.content.toInt()
        assertEquals("src/app/main.c", uri)
        assertEquals(21, startLine)
    }

    /**
     * app-release.wasm (935 bytes) is stripped: no name section, no DWARF (custom
     * sections are only producers + target_features). Exports survive, so the name
     * chain can still answer for exported functions; this finding uses a ByteRange
     * anchor, so no name/source lookup happens at all.
     * Schema must still be valid, and the ByteRange anchor produces byteOffset/byteLength.
     */
    @Test
    fun appReleaseWasmSchemaValid() {
        val bytes = requireFixtureBytes("app-release.wasm")
        val resolver = SarifSourceResolver.from(bytes)

        val checks = listOf(CheckRegistrationInfo("fixture.check", CheckSeverity.ERROR, "fixture"))
        val findings = listOf(
            SarifFinding(
                CheckFinding("fixture.check", CheckSeverity.ERROR, "release finding",
                    ScriptAnchor.ByteRange(offset = 0, length = 4)),
            ),
        )
        val sarif = renderSarif(
            checks = checks,
            findings = findings,
            toolVersion = "test",
            functionName = resolver?.let { r -> r::functionName },
            sourceLocation = null, // release has no DWARF
        )
        assertSchemaValid(sarif)

        val result = Json.parseToJsonElement(sarif).jsonObject["runs"]!!
            .jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        val physLoc = result["locations"]!!.jsonArray[0].jsonObject["physicalLocation"]!!.jsonObject
        val region = physLoc["region"]!!.jsonObject
        assertEquals(0, region["byteOffset"]!!.jsonPrimitive.content.toLong())
        assertEquals(4, region["byteLength"]!!.jsonPrimitive.content.toLong())
    }
}
