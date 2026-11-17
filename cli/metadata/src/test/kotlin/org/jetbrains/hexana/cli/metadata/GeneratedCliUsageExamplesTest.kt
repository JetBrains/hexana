package org.jetbrains.hexana.cli.metadata

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedCliUsageExamplesTest {
    @Test
    fun examplesUseTheGeneratedRuntimeInputDecoder() {
        validateHexanaCliUsageExamples()

        val migrated = HexanaGeneratedCliToolRegistry.TOOLS.filter { it.examples.isNotEmpty() }
        assertEquals(listOf("summarize-module"), migrated.map { it.commandName })
        assertEquals(2, migrated.single().examples.size)
        assertTrue(migrated.single().examples.all { it.arguments.isNotEmpty() })
    }
}
