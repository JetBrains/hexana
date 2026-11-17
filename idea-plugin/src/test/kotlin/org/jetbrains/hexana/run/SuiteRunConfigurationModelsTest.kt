package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.scripting.SuiteArtifactDeclaration

/**
 * Unit tests for the pure model functions in SuiteRunConfigurationModels.
 * No IDE platform runtime required.
 */
class SuiteRunConfigurationModelsTest : UsefulTestCase() {

    // ----- declarationsToRows -----

    fun testDeclarationsToRowsExactFields() {
        val decls = listOf(
            SuiteArtifactDeclaration(role = "app", formats = listOf("wasm"), optional = false),
            SuiteArtifactDeclaration(role = "baseline", formats = emptyList(), optional = true),
        )
        val stored = mapOf("app" to "/path/to/app.wasm", "baseline" to "/path/to/base.wasm")
        val rows = declarationsToRows(decls, stored)

        assertEquals(2, rows.size)

        val appRow = rows[0]
        assertEquals("app", appRow.role)
        assertEquals(listOf("wasm"), appRow.formats)
        assertFalse(appRow.optional)
        assertEquals("/path/to/app.wasm", appRow.path)

        val baseRow = rows[1]
        assertEquals("baseline", baseRow.role)
        assertTrue(baseRow.optional)
        assertEquals("/path/to/base.wasm", baseRow.path)
    }

    fun testDeclarationsToRowsMissingStoredPathIsEmpty() {
        val decls = listOf(
            SuiteArtifactDeclaration(role = "app", formats = listOf("elf"), optional = false),
        )
        val rows = declarationsToRows(decls, emptyMap())
        assertEquals(1, rows.size)
        assertEquals("", rows[0].path)
    }

    fun testDeclarationsToRowsUnknownStoredRoleDropped() {
        // A stored role not in declarations should not appear as a row.
        val decls = listOf(
            SuiteArtifactDeclaration(role = "known", formats = emptyList(), optional = false),
        )
        val stored = mapOf("known" to "/k.wasm", "unknown" to "/u.wasm")
        val rows = declarationsToRows(decls, stored)
        assertEquals(1, rows.size)
        assertEquals("known", rows[0].role)
        assertEquals("/k.wasm", rows[0].path)
    }

    // ----- rowsToArtifactValues -----

    fun testRowsToArtifactValuesSkipsEmptyPaths() {
        val rows = listOf(
            ArtifactRow(role = "app", formats = listOf("wasm"), optional = false, path = "/a.wasm"),
            ArtifactRow(role = "baseline", formats = emptyList(), optional = true, path = ""),
        )
        val values = rowsToArtifactValues(rows)
        assertEquals(listOf("app=/a.wasm"), values)
    }

    fun testRowsToArtifactValuesIncludeEmptyWhenRequested() {
        val rows = listOf(
            ArtifactRow(role = "app", formats = emptyList(), optional = false, path = ""),
        )
        val values = rowsToArtifactValues(rows, includeEmpty = true)
        assertEquals(listOf("app="), values)
    }

    fun testRowsToArtifactValuesRoundTrip() {
        val decls = listOf(
            SuiteArtifactDeclaration(role = "bin", formats = listOf("elf"), optional = false),
        )
        val stored = listOf("bin=/path/to/fw.elf")
        val storedMap = storedValuesToMap(stored)
        val rows = declarationsToRows(decls, storedMap)
        val values = rowsToArtifactValues(rows)
        assertEquals(stored, values)
    }

    // ----- matchingSuiteRole -----

    fun testMatchingSuiteRoleSingleRequiredMatch() {
        val decls = listOf(
            SuiteArtifactDeclaration(role = "app", formats = listOf("wasm"), optional = false),
        )
        assertEquals("app", matchingSuiteRole(decls, "wasm"))
    }

    fun testMatchingSuiteRoleEmptyFormatsMatchesAny() {
        val decls = listOf(
            SuiteArtifactDeclaration(role = "bin", formats = emptyList(), optional = false),
        )
        assertEquals("bin", matchingSuiteRole(decls, "elf"))
        assertEquals("bin", matchingSuiteRole(decls, "wasm"))
    }

    fun testMatchingSuiteRoleFormatMismatchReturnsNull() {
        val decls = listOf(
            SuiteArtifactDeclaration(role = "app", formats = listOf("wasm"), optional = false),
        )
        assertNull(matchingSuiteRole(decls, "elf"))
    }

    fun testMatchingSuiteRoleOptionalOnlySuiteExcluded() {
        // A suite with only optional declarations has no required roles; excluded.
        val decls = listOf(
            SuiteArtifactDeclaration(role = "opt", formats = emptyList(), optional = true),
        )
        assertNull(matchingSuiteRole(decls, "wasm"))
    }

    fun testMatchingSuiteRoleMultiRoleSuiteExcluded() {
        // A suite needing multiple required roles is not a single-match; excluded.
        val decls = listOf(
            SuiteArtifactDeclaration(role = "app", formats = listOf("wasm"), optional = false),
            SuiteArtifactDeclaration(role = "baseline", formats = listOf("wasm"), optional = false),
        )
        assertNull(matchingSuiteRole(decls, "wasm"))
    }

    fun testMatchingSuiteRoleRequiredMatchIgnoresOptional() {
        // One required match + one optional: still qualifies (optional not counted).
        val decls = listOf(
            SuiteArtifactDeclaration(role = "app", formats = listOf("wasm"), optional = false),
            SuiteArtifactDeclaration(role = "extra", formats = emptyList(), optional = true),
        )
        assertEquals("app", matchingSuiteRole(decls, "wasm"))
    }
}
