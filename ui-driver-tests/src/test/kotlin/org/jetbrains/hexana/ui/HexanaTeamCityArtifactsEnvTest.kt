package org.jetbrains.hexana.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HexanaTeamCityArtifactsEnvTest {
    @Test
    fun missingBuildTypeFailsNamingTheVariable() {
        val failure = assertThrows(IllegalStateException::class.java) {
            HexanaTeamCityArtifacts.requireBuildTypeEnv("HEXANA_IDE_BUILD_TYPE", "download an IDE installer") { null }
        }
        assertTrue(failure.message.orEmpty().startsWith("HEXANA_IDE_BUILD_TYPE is required"), failure.message)
    }

    @Test
    fun blankBuildTypeIsTreatedAsMissing() {
        assertThrows(IllegalStateException::class.java) {
            HexanaTeamCityArtifacts.requireBuildTypeEnv("HEXANA_PLUGIN_BUILD_TYPE", "download the plugin") { "  " }
        }
    }

    @Test
    fun configuredBuildTypeIsTrimmedAndReturned() {
        val value = HexanaTeamCityArtifacts.requireBuildTypeEnv("HEXANA_PLUGIN_BUILD_TYPE", "download the plugin") { " example.invalid_Plugin " }
        assertEquals("example.invalid_Plugin", value)
    }
}
