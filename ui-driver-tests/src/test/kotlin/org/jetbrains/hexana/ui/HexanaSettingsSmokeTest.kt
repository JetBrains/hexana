package org.jetbrains.hexana.ui

import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.hexanaSettings
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("hexana")
class HexanaSettingsSmokeTest {
    @Test
    fun packagedSettingsPageShowsHexanaControls() {
        hexanaIde(testName = "hexana-settings-controls") {
            hexanaSettings {
                expectControlsPresent()
            }
        }
    }
}
