package org.jetbrains.hexana.shared.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptYieldTransformTest {

    private fun transform(source: String) = ScriptYieldTransform.apply(source)

    // -- what it must rewrite ------------------------------------------------

    @Test
    fun bareOutputCallsBecomeYields() {
        assertEquals("yield print(\"a\");", transform("print(\"a\");"))
        assertEquals("yield table(\"A\");", transform("table(\"A\");"))
        assertEquals("yield row(1);", transform("row(1);"))
    }

    @Test
    fun whitespaceBeforeTheParenthesisStillCounts() {
        assertEquals("yield print (1);", transform("print (1);"))
    }

    @Test
    fun callsInsideStatementsAreRewritten() {
        assertEquals(
            "for (const r of rows) { yield row(r.name, r.size); }",
            transform("for (const r of rows) { row(r.name, r.size); }"),
        )
    }

    // -- what it must not rewrite -------------------------------------------

    @Test
    fun memberCallsAreLeftAlone() {
        // The bug this scanner replaces: `tab.row(i)` became `tab.yield row(i)`,
        // a syntax error, breaking the documented HexanaTab API.
        assertEquals("tab.row(0);", transform("tab.row(0);"))
        assertEquals("t.rows();", transform("t.rows();"))
        assertEquals("obj.print(1);", transform("obj.print(1);"))
        assertEquals("obj?.row(1);", transform("obj?.row(1);"))
        assertEquals("a. row(1);", transform("a. row(1);"))
    }

    @Test
    fun textInStringLiteralsIsLeftAlone() {
        // The other half of the bug: a script printing "12 row(s)" had its own
        // output corrupted into "12 yield row(s)".
        assertEquals("""yield print("12 row(s)");""", transform("""print("12 row(s)");"""))
        assertEquals("""yield print('a print( b');""", transform("""print('a print( b');"""))
    }

    @Test
    fun textInTemplateLiteralsIsLeftAlone() {
        assertEquals("yield print(`12 row(s)`);", transform("yield print(`12 row(s)`);".replace("yield ", "")))
        assertEquals("const s = `row(s)`;", transform("const s = `row(s)`;"))
    }

    @Test
    fun codeInsideTemplateHolesIsStillRewritten() {
        assertEquals(
            "const s = `x\${yield row(1)}y`;",
            transform("const s = `x\${row(1)}y`;"),
        )
    }

    @Test
    fun nestedBracesInsideATemplateHoleDoNotEndItEarly() {
        assertEquals(
            "const s = `\${f({ a: 1 })} row(s)`;",
            transform("const s = `\${f({ a: 1 })} row(s)`;"),
        )
    }

    @Test
    fun commentsAreLeftAlone() {
        assertEquals("// call print(x) here\n", transform("// call print(x) here\n"))
        assertEquals("/* row(s) and table(x) */", transform("/* row(s) and table(x) */"))
        assertEquals(
            "yield print(1); // print(2)",
            transform("print(1); // print(2)"),
        )
    }

    @Test
    fun declarationsAreLeftAlone() {
        // A script may shadow a global; `function yield row(...)` would not parse.
        assertEquals("function row(a) {}", transform("function row(a) {}"))
        assertEquals("const print = (x) => x;", transform("const print = (x) => x;"))
    }

    @Test
    fun identifiersThatMerelyContainAGlobalNameAreLeftAlone() {
        assertEquals("myprint(1);", transform("myprint(1);"))
        assertEquals("rowCount(1);", transform("rowCount(1);"))
        assertEquals("printer(1);", transform("printer(1);"))
    }

    @Test
    fun regexLiteralsAreLeftAlone() {
        assertEquals("""const re = /print\(/;""", transform("""const re = /print\(/;"""))
        assertEquals("""x.replace(/row\(/g, "");""", transform("""x.replace(/row\(/g, "");"""))
    }

    @Test
    fun divisionIsNotMistakenForARegexLiteral() {
        // If `/` after a value were treated as opening a literal, the scanner
        // would skip the rest of the line and miss the call on it.
        assertEquals("const half = total / 2; yield print(half);", transform("const half = total / 2; print(half);"))
        assertEquals("yield print(a[0] / b); yield row(1);", transform("print(a[0] / b); row(1);"))
    }

    @Test
    fun anUnterminatedStringDoesNotSwallowTheRestOfTheScript() {
        val result = transform("print(\"oops\nrow(1);\n")

        assertTrue(result.contains("yield print("), result)
        // The next line is still scanned rather than being eaten as string text.
        assertTrue(result.contains("yield row(1);"), result)
    }

    // -- the bundled examples ------------------------------------------------

    @Test
    fun theDumpTabExampleSurvivesTheTransform() {
        // This example is the reason the regex had to go: it calls tab.row(i) and
        // prints the words "row(s)".
        val source = """
            const TAB = binary.tabs[0];
            print("Available tabs: " + binary.tabs.join(", "));
            const tab = binary.tab(TAB);
            print(tab.name + ": " + tab.rowCount + " row(s), " + tab.columns.length + " column(s)");
            table.apply(null, tab.columns);
            for (let i = 0; i < tab.rowCount; i++) {
                row.apply(null, tab.row(i));
            }
        """.trimIndent()

        val result = transform(source)

        assertTrue(result.contains("tab.row(i)"), "member call was rewritten: $result")
        assertTrue(result.contains("\" row(s), \""), "string literal was rewritten: $result")
        assertFalse(result.contains("yield row.apply"), "row.apply is not an output call: $result")
        assertFalse(result.contains("yield table.apply"), "table.apply is not an output call: $result")
        // The two real print calls are still yielded.
        assertEquals(2, Regex("yield print\\(").findAll(result).count(), result)
    }

    @Test
    fun theCompareBinariesExampleKeepsItsHelpersYieldFree() {
        // That example's helpers deliberately only compute: a `yield` inside a
        // nested ordinary function is a syntax error in a generator, so an output
        // call moved into a helper would stop the whole script from compiling in
        // the browser host. Nothing here can enforce that for a user's script —
        // this pins the shape the bundled example is written in.
        val source = """
            function regionSizes(target) {
                const sizes = {};
                for (const region of target.structure) {
                    sizes[region.name] = (sizes[region.name] || 0) + region.size;
                }
                return sizes;
            }
            if (!OTHER) {
                print("Set OTHER at the top of this script");
            } else {
                table("Region", "Open", "Other", "Delta");
                for (const entry of changed.slice(0, TOP)) {
                    row(entry.name, entry.mine, entry.theirs, signed(entry.mine - entry.theirs));
                }
            }
        """.trimIndent()

        val result = transform(source)

        val helperEnd = result.indexOf("return sizes;")
        assertFalse(result.substring(0, helperEnd).contains("yield "), "helper yields: $result")
        // The top-level calls, inside blocks rather than functions, still yield.
        assertTrue(result.contains("yield print("), result)
        assertTrue(result.contains("yield table("), result)
        assertTrue(result.contains("yield row(entry.name"), result)
    }

    @Test
    fun theStarterTemplateSurvivesTheTransform() {
        val result = transform(ScriptApiReference.starterTemplate())

        assertTrue(result.contains("yield print("), result)
        assertTrue(result.contains("yield table("), result)
        assertTrue(result.contains("yield row("), result)
        // Nothing inside a comment or a member access may be touched.
        assertFalse(result.contains("yield print(...values)"), result)
        assertFalse(result.contains(".yield "), result)
    }
}
