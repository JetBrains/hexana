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

    // -- function bodies ------------------------------------------------------

    private fun openTransform(source: String) =
        ScriptYieldTransform.apply(source, suspending = ScriptYieldTransform.SUSPENDING_GLOBALS)

    @Test
    fun aCallInsideAFunctionDeclarationIsLeftAlone() {
        // `yield` inside a nested ordinary function is a syntax error, so this
        // used to make the whole script fail to compile in the browser host.
        val result = transform(
            """
            function report(name) {
                print(name);
            }
            report("a");
            """.trimIndent()
        )

        assertFalse(result.contains("yield print("), result)
    }

    @Test
    fun aCallInsideACallbackIsLeftAlone() {
        val classic = transform("""[1, 2].forEach(function(v) { print(v); });""")
        assertFalse(classic.contains("yield print("), classic)

        val arrow = transform("""[1, 2].forEach(v => { print(v); });""")
        assertFalse(arrow.contains("yield print("), arrow)

        val parenthesizedArrow = transform("""[1, 2].forEach((v) => { print(v); });""")
        assertFalse(parenthesizedArrow.contains("yield print("), parenthesizedArrow)
    }

    @Test
    fun aCallInsideAConciseArrowBodyIsLeftAlone() {
        // A braceless arrow body is a function body too, so a `yield` in it is
        // the same syntax error as in a braced one.
        val single = transform("""[1, 2].forEach(v => print(v));""")
        assertFalse(single.contains("yield print("), single)

        val parenthesized = transform("""[1, 2].forEach((v) => print(v));""")
        assertFalse(parenthesized.contains("yield print("), parenthesized)

        val nested = transform("""[[1]].forEach(xs => xs.forEach(v => print(v)));""")
        assertFalse(nested.contains("yield print("), nested)

        val opened = openTransform("""["a"].map(p => open(p).size);""")
        assertFalse(opened.contains("yield open("), opened)
    }

    @Test
    fun aConciseArrowBodyDoesNotSilenceTheRestOfTheScript() {
        val semicolon = transform(
            """
            const twice = v => v * 2;
            print(twice(2));
            """.trimIndent()
        )
        assertEquals(1, Regex("yield print\\(").findAll(semicolon).count(), semicolon)

        // No semicolon: the body ends at the line break, as it does for the
        // parser, so the statement after it still yields.
        val newline = transform(
            """
            const twice = v => v * 2
            print(twice(2))
            """.trimIndent()
        )
        assertEquals(1, Regex("yield print\\(").findAll(newline).count(), newline)

        val afterCallback = transform("""[1].forEach(v => v * 2); print("outer");""")
        assertEquals(1, Regex("yield print\\(").findAll(afterCallback).count(), afterCallback)

        // An `open` after a concise arrow must still suspend: silencing it would
        // turn a working call into the nested-call error.
        val openAfter = openTransform(
            """
            const twice = v => v * 2
            const other = open("/tmp/b.wasm");
            """.trimIndent()
        )
        assertTrue(openAfter.contains("(yield open(\"/tmp/b.wasm\"))"), openAfter)
    }

    @Test
    fun aCallInsideAMethodIsLeftAlone() {
        val result = transform(
            """
            const reporter = {
                report(name) { print(name); }
            };
            reporter.report("a");
            """.trimIndent()
        )

        assertFalse(result.contains("yield print("), result)
    }

    @Test
    fun controlFlowBlocksStillYield() {
        // A control-flow head is parenthesised too, but its block is part of the
        // generator, so calls inside it must keep yielding.
        val result = transform(
            """
            if (binary.size > 0) { print("a"); }
            for (const r of binary.structure) { print(r.name); }
            while (false) { print("never"); }
            try { print("b"); } catch (error) { print("c"); }
            """.trimIndent()
        )

        assertEquals(5, Regex("yield print\\(").findAll(result).count(), result)
    }

    @Test
    fun aFunctionBodyDoesNotSilenceTheRestOfTheScript() {
        // The skip has to end with the body: a call after it is back in the
        // generator and must yield again.
        val result = transform(
            """
            function helper() { print("inner"); }
            print("outer");
            """.trimIndent()
        )

        assertEquals(1, Regex("yield print\\(").findAll(result).count(), result)
        assertTrue(result.contains("yield print(\"outer\")"), result)
    }

    // -- open -----------------------------------------------------------------

    @Test
    fun openIsYieldedOnlyWhenTheHostAsksForIt() {
        val source = """const other = open("/tmp/b.wasm");"""

        assertTrue(openTransform(source).contains("= (yield open(\"/tmp/b.wasm\"))"), openTransform(source))
        // A host that reads the file inside the call must not have it rewritten.
        assertFalse(transform(source).contains("yield open("), transform(source))
    }

    @Test
    fun aSuspendingCallIsParenthesizedSoMemberAccessReadsTheResult() {
        // `yield` binds looser than member access. Without the wrapping parens
        // `yield open(p).size` reads `.size` off the request object and yields
        // undefined, so the host is never asked and the script sees nothing.
        val result = openTransform("""print(open("/b.wasm").size);""")

        assertTrue(result.contains("""(yield open("/b.wasm")).size"""), result)
    }

    @Test
    fun aSuspendingCallInsideAnotherCallIsStillParenthesized() {
        val result = openTransform("""print(open("/a.wasm").size, open("/b.wasm").size);""")

        assertEquals(2, Regex("""\(yield open\(""").findAll(result).count(), result)
        assertEquals(2, Regex("""\)\)\.size""").findAll(result).count(), result)
    }

    @Test
    fun nestedSuspendingCallsCloseInTheRightOrder() {
        val result = openTransform("""print(open(open("/a.wasm").path).size);""")

        // Both calls wrapped, and the wrapping parens balance.
        assertEquals(2, Regex("""\(yield open\(""").findAll(result).count(), result)
        assertEquals(result.count { it == '(' }, result.count { it == ')' }, result)
    }

    @Test
    fun argumentsOfASuspendingCallKeepTheirOwnParens() {
        val result = openTransform("""const o = open(binary.dir + "/" + (1 + 2) + ".bin");""")

        assertTrue(result.contains("""(yield open(binary.dir + "/" + (1 + 2) + ".bin"))"""), result)
    }

    @Test
    fun openInsideAFunctionIsLeftAloneRatherThanBreakingCompilation() {
        val result = openTransform(
            """
            function sizeOf(path) {
                return open(path).size;
            }
            print(sizeOf("/tmp/b.wasm"));
            """.trimIndent()
        )

        assertFalse(result.contains("yield open("), result)
        assertTrue(result.contains("yield print("), result)
    }

    @Test
    fun aMemberCalledOpenIsNotRewritten() {
        val result = openTransform("""binary.open("x");""")

        assertFalse(result.contains("yield open("), result)
    }

    @Test
    fun aScriptDeclaringItsOwnOpenKeepsItsDeclaration() {
        // Shadowing the global is the user's business; the declaration must not
        // turn into `function yield open(...)`.
        val result = openTransform(
            """
            function open(path) { return path; }
            const p = open("/tmp/b.wasm");
            """.trimIndent()
        )

        assertTrue(result.contains("function open(path)"), result)
    }

    @Test
    fun theCompareBinariesExampleKeepsItsHelpersYieldFree() {
        // That example's helpers deliberately only compute. An output call inside
        // one is now left alone rather than breaking compilation, but it would
        // still cost the tab its streaming, and an `open` there cannot suspend at
        // all — so this pins the shape the bundled example is written in.
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
