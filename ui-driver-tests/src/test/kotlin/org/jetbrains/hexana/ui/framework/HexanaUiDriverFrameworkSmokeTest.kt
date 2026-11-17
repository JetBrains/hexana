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

    /**
     * The Hexana Suite run configuration ships as the org.jetbrains.hexana.suiteRunner
     * content module. On 2026.1+ the module must load, the type must be registered, and the
     * SM test-runner classes must be reachable through the module classloader. On
     * 2025.2/2025.3 the platform does not register intellij.platform.smRunner /
     * intellij.platform.testRunner, so the module must be SKIPPED while the plugin itself
     * stays alive (a plugin-level dependency instead of the module disables the whole plugin
     * there, TC build 1072434679 — that regression shows up here as a dead probe).
     */
    @Test
    fun suiteConsoleClassesReachableAndTypeRegistered() {
        hexanaIde(testName = "hexana-suite-console-classpath") {
            val payload = suiteConsoleClasspathState()
            if (context.ide.isMajorBuildVersionAtLeast(261)) {
                val expected = listOf(
                    "SMTestRunnerConnectionUtil=true",
                    "SMTRunnerConsoleProperties=true",
                    "AbstractRerunFailedTestsAction=true",
                    "suiteTypeRegistered=true",
                )
                val missing = expected.filterNot { it in payload }
                check(missing.isEmpty()) {
                    "Suite console classpath state mismatch; missing $missing in payload: $payload"
                }
            } else {
                // Pre-261: plugin alive (the probe answered), suite module skipped by design.
                check("suiteTypeRegistered=false" in payload) {
                    "Suite run configuration must be absent on pre-261 IDEs; payload: $payload"
                }
            }
        }
    }
}
