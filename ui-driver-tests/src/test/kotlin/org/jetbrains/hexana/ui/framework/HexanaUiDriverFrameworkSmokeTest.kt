package org.jetbrains.hexana.ui.framework

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("hexana")
class HexanaUiDriverFrameworkSmokeTest {
    @Test
    fun packagedEditorProbeRespondsThroughDsl() {
        hexanaIde(testName = "hexana-ui-driver-framework-smoke") {
            hexanaEditor {
                expect {
                    probeResponds()
                }
            }
        }
    }
}
