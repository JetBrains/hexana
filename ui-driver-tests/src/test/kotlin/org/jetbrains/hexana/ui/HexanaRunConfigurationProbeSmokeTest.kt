package org.jetbrains.hexana.ui

import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.runConfigurationProbeResponds
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("hexana")
class HexanaRunConfigurationProbeSmokeTest {
    @Test
    fun packagedRunConfigurationProbeResponds() {
        hexanaIde(testName = "hexana-run-configuration-probe-smoke") {
            driver.runConfigurationProbeResponds()
        }
    }
}
