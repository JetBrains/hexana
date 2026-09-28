package org.jetbrains.hexana.ui

import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * WasmGC Type Graph acceptance checks on a real Kotlin/Wasm GC binary:
 * the focused graph opens fast on a heavily referenced type with a correct
 * "showing X of Y" counter, the type-info popup carries structure data for
 * ref.cast and struct.get target types, and the WAT <-> graph round trip
 * resolves in both directions. Assertions gate on UI-state breadcrumbs the
 * panels publish (HexanaFileEditor.wasmUiProbeState), not on sleeps.
 */
class HexanaWasmGcTypeGraphAcceptanceTest {
    @Test
    fun wasmGcTypeGraphRoundTrip() {
        val project = Files.createTempDirectory("hexana-wasmgc-acceptance-")
        val wasm = project.resolve("kotlin-hello.wasm")
        val fixture = repoRoot().resolve("wasmParser/src/commonTest/resources/kotlin/hello.wasm")
        require(fixture.exists()) { "Missing Kotlin/Wasm fixture: $fixture" }
        Files.copy(fixture, wasm)

        hexanaIde(
            testName = "hexana-wasmgc-type-graph-acceptance",
            projectDir = project,
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            closeProjectToolWindow()
            val editor = openFile(wasm.fileName.toString())
            waitFor("Type Graph panel resolved") {
                val snap = editor.snapshot()
                snap.panelsResolved && "Type Graph" in snap.panelNames
            }
            editor.selectTab("WAT (virtualized)")
            waitFor("WAT tab selected") { editor.snapshot().selectedTabName == "WAT (virtualized)" }

            val targets = editor.wasmTypeGraphDemoTargets()
            logOutput("WasmGC acceptance targets: $targets")
            val hub = targets.intValue("hubTypeIdx")
            val hubNeighborhood = targets.intValue("hubNeighborhood")
            val totalTypes = targets.intValue("totalTypes")
            val castFuncIdx = targets.intValue("castFuncIdx")
            val castTypeIdx = targets.intValue("castTypeIdx")
            val structGetTypeIdx = targets.intValue("structGetTypeIdx")
            assertTrue(hub >= 0, "no hub type found: $targets")
            assertTrue(
                hubNeighborhood > 50,
                "hub type must have >50 transitive referencers, got $hubNeighborhood: $targets",
            )
            assertTrue(castTypeIdx >= 0, "no ref.cast site found: $targets")
            assertTrue(structGetTypeIdx >= 0, "no struct.get site found: $targets")

            // 1. WAT -> graph: focus the hub type, open the Type Graph tab, and
            // assert the focused neighborhood builds fast with a correct counter.
            editor.showWasmTypeGraph(hub)
            editor.selectTab("Type Graph")
            waitFor("Type Graph tab selected") { editor.snapshot().selectedTabName == "Type Graph" }
            val graphState = waitForValue("typeGraph breadcrumb for focus=$hub") {
                editor.wasmUiProbeValue("typeGraph")?.takeIf { it.contains("focus=$hub ") }
            }
            logOutput("typeGraph state: $graphState")
            val shown = graphState.field("shown")
            val total = graphState.field("total")
            val buildMillis = graphState.field("buildMillis")
            assertEquals(hubNeighborhood, shown, "counter X must equal the computed neighborhood: $graphState")
            assertEquals(totalTypes, total, "counter Y must equal the module type count: $graphState")
            assertTrue(shown > 50, "focused neighborhood must show >50 types for the hub: $graphState")
            assertTrue(buildMillis in 0..999, "graph build must take <1s, got ${buildMillis}ms: $graphState")

            // 2. Graph -> WAT: drive the same typeNavFlow the graph's node
            // double-click callback writes, select the WAT tab, and assert the
            // navigator resolved the type's definition row.
            editor.navigateWatToType(hub)
            editor.selectTab("WAT (virtualized)")
            waitFor("WAT tab selected after graph nav") {
                editor.snapshot().selectedTabName == "WAT (virtualized)"
            }
            val navState = waitForValue("typeNavResult breadcrumb for typeIdx=$hub") {
                editor.wasmUiProbeValue("typeNavResult")?.takeIf { it.contains("typeIdx=$hub ") }
            }
            logOutput("typeNavResult: $navState")
            assertTrue(navState.contains("resolved=true"), "WAT must resolve the type row: $navState")

            // 3. Instruction popups: ref.cast target, then struct.get target.
            if (castFuncIdx >= 0) editor.navigateToWasmFunction(castFuncIdx)
            editor.showWasmTypeInfoPopup(castTypeIdx)
            val castInfo = waitForValue("typeInfo breadcrumb for ref.cast type $castTypeIdx") {
                editor.wasmUiProbeValue("typeInfo")?.takeIf { it.startsWith("typeIdx=$castTypeIdx ") }
            }
            logOutput("ref.cast typeInfo: $castInfo")
            assertTrue(castInfo.contains("kind="), "popup data must carry the type kind: $castInfo")

            if (structGetTypeIdx != castTypeIdx) {
                editor.showWasmTypeInfoPopup(structGetTypeIdx)
                val structInfo = waitForValue("typeInfo breadcrumb for struct.get type $structGetTypeIdx") {
                    editor.wasmUiProbeValue("typeInfo")?.takeIf { it.startsWith("typeIdx=$structGetTypeIdx ") }
                }
                logOutput("struct.get typeInfo: $structInfo")
                assertTrue(structInfo.field("fields") >= 0, "struct.get popup must carry fields: $structInfo")
            }
        }
    }

    private fun List<String>.intValue(key: String): Int =
        firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.toIntOrNull() ?: -1

    /** Extracts an integer field from a breadcrumb like "focus=1 shown=102 total=1467". */
    private fun String.field(key: String): Int =
        split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.toIntOrNull() ?: -1

    private fun waitFor(what: String, timeout: Duration = 90.seconds, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        error("Timed out waiting for $what")
    }

    private fun waitForValue(what: String, timeout: Duration = 60.seconds, supplier: () -> String?): String {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(250)
        }
        error("Timed out waiting for $what")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists() && !path.resolve("settings.gradle").exists()) {
            path = path.parent ?: error("Could not locate repository root")
        }
        return path
    }
}
