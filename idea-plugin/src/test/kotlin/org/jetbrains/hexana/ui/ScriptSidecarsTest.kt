package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import org.jetbrains.hexana.ui.panel.ScriptSidecars
import java.io.File
import java.nio.file.Files

/**
 * Covers what actually lands on disk next to a script, which is the difference
 * between `binary.` completing and not.
 */
class ScriptSidecarsTest : UsefulTestCase() {

    private lateinit var directory: File

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("hexana-script-sidecars-").toFile()
    }

    override fun tearDown() {
        try {
            directory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun typesFile() = File(directory, ScriptApiReference.TYPES_FILE_NAME)
    private fun jsConfigFile() = File(directory, ScriptApiReference.JS_CONFIG_FILE_NAME)

    fun testWritesBothSidecarsIntoAnEmptyDirectory() {
        val written = ScriptSidecars.write(directory)

        assertEquals(setOf(typesFile(), jsConfigFile()), written.toSet())
        assertTrue("the type definitions must exist", typesFile().isFile)
        assertTrue("the JavaScript project config must exist", jsConfigFile().isFile)
    }

    fun testTypeDefinitionsAreTheGeneratedReference() {
        ScriptSidecars.write(directory)

        assertEquals(ScriptApiReference.dts(), typesFile().readText())
        // The globals a script uses have to be declared, or nothing resolves.
        val types = typesFile().readText()
        for (declaration in listOf(
            "declare const binary: HexanaBinary;",
            "declare function print(...values: any[]): void;",
            "declare function table(...columns: any[]): void;",
            "declare function row(...cells: any[]): void;",
        )) {
            assertTrue("missing '$declaration'", types.contains(declaration))
        }
    }

    fun testJsConfigPointsAtTheTypeDefinitions() {
        ScriptSidecars.write(directory)

        val config = jsConfigFile().readText()
        assertTrue(config, config.contains(ScriptApiReference.TYPES_FILE_NAME))
        assertTrue(config, config.contains("*.js"))
    }

    fun testASecondCallWritesNothingWhenEverythingIsUpToDate() {
        ScriptSidecars.write(directory)

        assertEmpty(ScriptSidecars.write(directory))
    }

    fun testStaleTypeDefinitionsAreRefreshed() {
        // What a user upgrading the plugin hits: an older `.d.ts` from a previous
        // version has to be replaced, or completion describes an API that moved.
        typesFile().writeText("declare const binary: unknown;\n")

        val written = ScriptSidecars.write(directory)

        assertContainsElements(written, typesFile())
        assertEquals(ScriptApiReference.dts(), typesFile().readText())
    }

    fun testAnExistingJsConfigIsLeftAlone() {
        val existing = "{ \"compilerOptions\": { \"strict\": true } }\n"
        jsConfigFile().writeText(existing)

        val written = ScriptSidecars.write(directory)

        assertEquals(existing, jsConfigFile().readText())
        assertDoesntContain(written, jsConfigFile())
        // The types are still refreshed: only the config is the user's.
        assertContainsElements(written, typesFile())
    }

    fun testAnExistingTsConfigSuppressesTheJsConfig() {
        File(directory, "tsconfig.json").writeText("{}\n")

        val written = ScriptSidecars.write(directory)

        assertFalse("must not add a competing config", jsConfigFile().exists())
        assertEquals(listOf(typesFile()), written)
    }
}
