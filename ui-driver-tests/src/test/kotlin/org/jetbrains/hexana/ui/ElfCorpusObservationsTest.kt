package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaEditor
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Opens 12 Zephyr/ZMK ELF artifacts sequentially in one IDE launch, collects observations
 * (panel names, provider kind, diff sections), takes screenshots, and writes raw data to
 * /tmp/hexana-corpus-observations.json. Fails if fewer than 12 artifact entries are collected.
 *
 * Disassembly panel state is NOT observable via probe: the load result lives in Compose-local
 * `remember` state inside NativeDisassemblyPanelVirtualized and is unreachable without
 * modifying production code. Screenshots capture the panel visually only.
 */
class ElfCorpusObservationsTest {

    private companion object {
        const val DISASM_TAB = "Disassembly (virtualized)"
        const val SEARCH_PLACEHOLDER = "Search symbol, string, or hex offset (0x\u2026)"
    }

    private data class Artifact(
        val path: Path,
        val screenshotPrefix: String,
    )

    private val artifacts: List<Artifact> = listOf(
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-debug/zephyr.elf"),
            "hello_world-nrf52840dk-debug",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-Os/zephyr.elf"),
            "hello_world-nrf52840dk-Os",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-O2/zephyr.elf"),
            "hello_world-nrf52840dk-O2",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nucleo_f429zi-Os/zephyr.elf"),
            "hello_world-nucleo_f429zi-Os",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-debug/zephyr.elf"),
            "peripheral_hr-nrf52840dk-debug",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-Os/zephyr.elf"),
            "peripheral_hr-nrf52840dk-Os",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-size-opt/zephyr.elf"),
            "peripheral_hr-nrf52840dk-size-opt",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/usb_hid-mouse/nrf52840dk-debug/zephyr.elf"),
            "usb_hid-mouse-nrf52840dk-debug",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("apps/usb_hid-mouse/nrf52840dk-Os/zephyr.elf"),
            "usb_hid-mouse-nrf52840dk-Os",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("external/zmk/xiao-nrf52840-intercom.elf"),
            "external-zmk-intercom",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("external/zmk/xiao-nrf52840-loa-controller.elf"),
            "external-zmk-loa-controller",
        ),
        Artifact(
            HexanaUiDriverTestSupport.corpusElf("external/zmk/xiao-nrf52840-loa-receiver.elf"),
            "external-zmk-loa-receiver",
        ),
    )

    private data class ArtifactObservation(
        val prefix: String,
        val path: String,
        val providerKind: String,
        val panelsResolved: Boolean,
        val panelNames: List<String>,
        val selectedTabAtEnd: String?,
        val topSizeEntries: List<String>,
        val errors: List<String>,
        val screenshotsTaken: List<String>,
    )

    private data class DiffObservation(
        val name: String,
        val leftPath: String,
        val rightPath: String,
        val status: String,
        val changedRowCount: Int,
        val sections: List<String>,
        val error: String?,
        val screenshotTaken: String?,
    )

    @Test
    fun observeElfCorpus() {
        artifacts.forEach { artifact ->
            require(artifact.path.exists()) { "Missing corpus artifact: ${artifact.path}" }
        }
        val screenshotDir = corpusScreenshotDir()
        val artifactObservations = mutableListOf<ArtifactObservation>()
        val diffObservations = mutableListOf<DiffObservation>()

        hexanaIde(
            testName = "elf-corpus-observations",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()

            // --- per-artifact loop ---
            for (artifact in artifacts) {
                val errors = mutableListOf<String>()
                val screenshotsTaken = mutableListOf<String>()

                // Close all editors before opening the next artifact to avoid tab-name ambiguity.
                closeAllEditors()
                Thread.sleep(500)

                val editor: HexanaEditor
                try {
                    editor = openAbsoluteFile(artifact.path)
                } catch (e: Exception) {
                    errors += "openAbsoluteFile failed: ${e.message}"
                    artifactObservations += ArtifactObservation(
                        prefix = artifact.screenshotPrefix,
                        path = artifact.path.toString(),
                        providerKind = "error",
                        panelsResolved = false,
                        panelNames = emptyList(),
                        selectedTabAtEnd = null,
                        topSizeEntries = emptyList(),
                        errors = errors,
                        screenshotsTaken = screenshotsTaken,
                    )
                    continue
                }

                // Poll until panels resolved (90s timeout).
                val snapshot = try {
                    waitForPanelsResolved(editor, timeout = 90.seconds)
                } catch (e: Exception) {
                    errors += "waitForPanelsResolved: ${e.message}"
                    val s = runCatching { editor.snapshot() }.getOrNull()
                    artifactObservations += ArtifactObservation(
                        prefix = artifact.screenshotPrefix,
                        path = artifact.path.toString(),
                        providerKind = s?.providerKind ?: "unknown",
                        panelsResolved = s?.panelsResolved ?: false,
                        panelNames = s?.panelNames ?: emptyList(),
                        selectedTabAtEnd = s?.selectedTabName,
                        topSizeEntries = emptyList(),
                        errors = errors,
                        screenshotsTaken = screenshotsTaken,
                    )
                    continue
                }
                logOutput("[${ artifact.screenshotPrefix}] panels resolved; tabs: ${snapshot.panelNames}")

                // Sections tab.
                runCatching {
                    if ("Sections" in snapshot.panelNames) {
                        editor.selectTab("Sections")
                        waitForSelectedTab(editor, "Sections")
                        val name = "${artifact.screenshotPrefix}-sections"
                        copyScreenshot(driver, name, screenshotDir.resolve("$name.png"))
                        screenshotsTaken += "$name.png"
                    } else {
                        errors += "tab 'Sections' not in panelNames: ${snapshot.panelNames}"
                    }
                }.onFailure { errors += "sections tab: ${it.message}" }

                // Disassembly tab (prefer virtualized).
                val disasmTab = snapshot.panelNames.firstOrNull { it == "Disassembly (virtualized)" }
                    ?: snapshot.panelNames.firstOrNull { it == "Disassembly" }
                runCatching {
                    if (disasmTab != null) {
                        editor.selectTab(disasmTab)
                        // Wait for tab selection only (disassembly text not observable via probe).
                        waitForSelectedTab(editor, disasmTab)
                        Thread.sleep(4_000) // give Capstone time to load
                        val name = "${artifact.screenshotPrefix}-disassembly"
                        copyScreenshot(driver, name, screenshotDir.resolve("$name.png"))
                        screenshotsTaken += "$name.png"
                    } else {
                        errors += "no disassembly tab found in: ${snapshot.panelNames}"
                    }
                }.onFailure { errors += "disassembly tab: ${it.message}" }

                // Debug Context tab.
                runCatching {
                    if ("Debug Context" in snapshot.panelNames) {
                        editor.selectTab("Debug Context")
                        waitForSelectedTab(editor, "Debug Context")
                        val name = "${artifact.screenshotPrefix}-debug-context"
                        copyScreenshot(driver, name, screenshotDir.resolve("$name.png"))
                        screenshotsTaken += "$name.png"
                    } else {
                        errors += "tab 'Debug Context' not in panelNames: ${snapshot.panelNames}"
                    }
                }.onFailure { errors += "debug context tab: ${it.message}" }

                // Debug Info tab.
                runCatching {
                    val debugInfoTab = snapshot.panelNames.firstOrNull { it == "Debug Info" || it.startsWith("DWARF") }
                    if (debugInfoTab != null) {
                        editor.selectTab(debugInfoTab)
                        waitForSelectedTab(editor, debugInfoTab)
                        val name = "${artifact.screenshotPrefix}-debug-info"
                        copyScreenshot(driver, name, screenshotDir.resolve("$name.png"))
                        screenshotsTaken += "$name.png"
                    } else {
                        errors += "no Debug Info / DWARF tab in: ${snapshot.panelNames}"
                    }
                }.onFailure { errors += "debug info tab: ${it.message}" }

                // Top tab.
                runCatching {
                    if ("Top" in snapshot.panelNames) {
                        editor.selectTab("Top")
                        waitForSelectedTab(editor, "Top")
                        val name = "${artifact.screenshotPrefix}-top"
                        copyScreenshot(driver, name, screenshotDir.resolve("$name.png"))
                        screenshotsTaken += "$name.png"
                    } else {
                        errors += "tab 'Top' not in panelNames: ${snapshot.panelNames}"
                    }
                }.onFailure { errors += "top tab: ${it.message}" }

                val topSizeEntries = runCatching { editor.topSizeState() }.getOrElse {
                    errors += "topSizeState: ${it.message}"; emptyList()
                }
                val finalSnapshot = runCatching { editor.snapshot() }.getOrNull()

                artifactObservations += ArtifactObservation(
                    prefix = artifact.screenshotPrefix,
                    path = artifact.path.toString(),
                    providerKind = snapshot.providerKind,
                    panelsResolved = snapshot.panelsResolved,
                    panelNames = snapshot.panelNames,
                    selectedTabAtEnd = finalSnapshot?.selectedTabName,
                    topSizeEntries = topSizeEntries,
                    errors = errors,
                    screenshotsTaken = screenshotsTaken,
                )
            }

            // --- diff pairs ---
            closeAllEditors()
            Thread.sleep(500)

            val diffPairs = listOf(
                Triple(
                    "diff-hello_world-debug-vs-Os",
                    HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-debug/zephyr.elf"),
                    HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-Os/zephyr.elf"),
                ),
                Triple(
                    "diff-hello_world-Os-vs-O2",
                    HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-Os/zephyr.elf"),
                    HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-O2/zephyr.elf"),
                ),
                Triple(
                    "diff-peripheral_hr-debug-vs-Os",
                    HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-debug/zephyr.elf"),
                    HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-Os/zephyr.elf"),
                ),
            )

            for ((diffName, leftPath, rightPath) in diffPairs) {
                var screenshotTaken: String? = null
                val obs = runCatching {
                    closeAllEditors()
                    Thread.sleep(500)
                    val diffEditor = openNativeDiff(leftPath, rightPath)
                    Thread.sleep(3_000)
                    val diffState = diffEditor.nativeDiffState()
                    val name = diffName
                    copyScreenshot(driver, name, screenshotDir.resolve("$name.png"))
                    screenshotTaken = "$name.png"
                    DiffObservation(
                        name = diffName,
                        leftPath = leftPath.toString(),
                        rightPath = rightPath.toString(),
                        status = diffState.status,
                        changedRowCount = diffState.changedRowCount,
                        sections = diffState.sections,
                        error = diffState.error,
                        screenshotTaken = screenshotTaken,
                    )
                }.getOrElse { e ->
                    DiffObservation(
                        name = diffName,
                        leftPath = leftPath.toString(),
                        rightPath = rightPath.toString(),
                        status = "error",
                        changedRowCount = 0,
                        sections = emptyList(),
                        error = e.message,
                        screenshotTaken = screenshotTaken,
                    )
                }
                diffObservations += obs
            }

            // Write observations JSON.
            writeObservationsJson(artifactObservations, diffObservations)
        }

        // Enforce minimum: all 12 artifacts must have been collected.
        check(artifactObservations.size == 12) {
            "Expected 12 artifact observations, got ${artifactObservations.size}"
        }
        val withErrors = artifactObservations.filter { it.errors.isNotEmpty() }
        if (withErrors.isNotEmpty()) {
            logOutput("WARNING: ${withErrors.size} artifacts had errors:")
            withErrors.forEach { logOutput("  ${it.prefix}: ${it.errors}") }
        }
    }

    private data class DisasmDrillTarget(
        val path: Path,
        val prefix: String,
        val query: String,
    )

    private data class DisasmDrillResult(
        val prefix: String,
        val query: String,
        val uiQueryUsed: String?,
        val probeEntries: List<String>,
        val screenshotTaken: String?,
        val errors: List<String>,
    )

    /**
     * Focused follow-up pass: for three artifacts, drill into a real function on the
     * Disassembly (virtualized) tab so actual instruction rows render, screenshot them,
     * and capture the first 40 decoded instructions programmatically via the
     * nativeDisassemblyInstructions probe (which mirrors the panel search's
     * first-contains-match selection and reuses the production decode pipeline).
     */
    @Test
    fun observeDisassemblyInstructions() {
        val targets = listOf(
            DisasmDrillTarget(
                HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-debug/zephyr.elf"),
                "hello_world-nrf52840dk-debug",
                "main",
            ),
            DisasmDrillTarget(
                HexanaUiDriverTestSupport.corpusElf("apps/peripheral_hr/nrf52840dk-Os/zephyr.elf"),
                "peripheral_hr-nrf52840dk-Os",
                "memcpy",
            ),
            DisasmDrillTarget(
                HexanaUiDriverTestSupport.corpusElf("external/zmk/xiao-nrf52840-intercom.elf"),
                "external-zmk-intercom",
                "memcpy",
            ),
        )
        targets.forEach { require(it.path.exists()) { "Missing corpus artifact: ${it.path}" } }
        val screenshotDir = corpusScreenshotDir()
        val results = mutableListOf<DisasmDrillResult>()

        quitSystemSettingsQuietly()
        hexanaIde(
            testName = "elf-corpus-disasm-instructions",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()

            for (target in targets) {
                val errors = mutableListOf<String>()
                var probeEntries: List<String> = emptyList()
                var uiQueryUsed: String? = null
                var screenshotTaken: String? = null

                runCatching {
                    maximizeWindow()
                    closeAllEditors()
                    Thread.sleep(500)
                    val editor = openAbsoluteFile(target.path)
                    waitForPanelsResolved(editor, 90.seconds)
                    editor.selectTab(DISASM_TAB)
                    waitForSelectedTab(editor, DISASM_TAB)

                    // Programmatic capture first: mirrors the search's first-contains-match pick.
                    probeEntries = editor.nativeDisassemblyInstructions(target.query, 40)
                    val resolvedName = probeEntries.entryValue("resolvedName")
                        ?: error("Probe payload has no resolvedName: $probeEntries")
                    logOutput("[${target.prefix}] probe resolved '$resolvedName' " +
                        "(${probeEntries.entryValue("instructionCount")} instructions)")

                    // UI drill: search field -> type query -> Enter picks the first result,
                    // which unfolds the function header and navigates to its body chunk.
                    // The remote driver cannot enumerate Compose text on IDEs before the 261
                    // driver line (byText on the search placeholder finds nothing on IU-252,
                    // works on IU-263; verified 2026-08-27, TC build 1043623577 + local A/B).
                    // On such IDEs skip the drill: the probe capture above already verified the
                    // instructions, and the screenshot below shows the folded function list.
                    if (context.ide.isMajorBuildVersionAtLeast(261)) {
                        val resultIndex = probeEntries.entryValue("resultIndex")?.toIntOrNull() ?: -1
                        check(resultIndex >= 0) { "Probe found no search match for '${target.query}'" }
                        uiQueryUsed = target.query
                        // Paste instead of typing: Robot key injection is keyboard-layout dependent
                        // (a Cyrillic layout turns "memcpy" into garbage); Cmd+V, arrows, Enter are not.
                        // Arrow down to the probe-reported result index, mirroring the panel's
                        // range-ordered contains-match list, then Enter unfolds and navigates.
                        setClipboardText(target.query)
                        driver.withContext {
                            val field = ui.x { byText(SEARCH_PLACEHOLDER) }.waitFound(90.seconds)
                            field.click()
                            ui.keyboard { hotKey(java.awt.event.KeyEvent.VK_META, java.awt.event.KeyEvent.VK_V) }
                            Thread.sleep(1_500)
                            ui.keyboard { repeat(resultIndex) { down() } }
                            ui.keyboard { enter() }
                        }
                        // Give the AsyncFunctionBody decode + row composition time to land.
                        Thread.sleep(6_000)
                    }
                    quitSystemSettingsQuietly()
                    val name = "${target.prefix}-disasm-instructions"
                    copyScreenshot(driver, name, screenshotDir.resolve("$name.png"))
                    screenshotTaken = "$name.png"
                }.onFailure { errors += "drill: ${it.message}" }

                results += DisasmDrillResult(
                    prefix = target.prefix,
                    query = target.query,
                    uiQueryUsed = uiQueryUsed,
                    probeEntries = probeEntries,
                    screenshotTaken = screenshotTaken,
                    errors = errors,
                )
            }
        }

        appendDisassemblyInstructionsJson(results)
        val complete = results.count { it.screenshotTaken != null && it.probeEntries.isNotEmpty() }
        check(complete == targets.size) {
            "Expected ${targets.size} complete drill results, got $complete; " +
                "errors: ${results.filter { it.errors.isNotEmpty() }.map { "${it.prefix}: ${it.errors}" }}"
        }
    }

    /**
     * Thumb-2 decode acceptance: every Cortex-M corpus ELF must decode as Thumb-2 through the
     * production pipeline (M-profile from .ARM.attributes -> global THUMB arch, bit 0 of
     * Thumb symbol addresses stripped), and an A-profile mixed ARM/Thumb fixture must keep
     * per-symbol mode selection working. Asserts exact instruction addresses and mnemonics
     * against arm-zephyr-eabi-objdump ground truth recorded in comments below.
     *
     * The A-profile fixture is bundled at testData/fixtures/aprofile/ together with its
     * mixed.c source (arm_add is A32, thumb_mul has attribute target("thumb")); the build
     * command is recorded in testData/fixtures/zephyr-corpus/README.md.
     */
    @Test
    fun thumbDecodeAcceptance() {
        val aprofileFixture = HexanaUiDriverTestSupport.aprofileFixture()
        artifacts.forEach { require(it.path.exists()) { "Missing corpus artifact: ${it.path}" } }
        require(aprofileFixture.exists()) {
            "Missing A-profile fixture $aprofileFixture; see testData/fixtures/zephyr-corpus/README.md"
        }
        val rows = mutableListOf<String>()

        hexanaIde(
            testName = "elf-thumb-acceptance",
            configure = { showWhatsNewOnNewVersion(false) },
        ) {
            maximizeWindow()
            closeProjectToolWindow()

            // --- all 12 Cortex-M ELFs: global THUMB arch, even addresses, sane fallback rate ---
            for (artifact in artifacts) {
                closeAllEditors()
                Thread.sleep(500)
                val editor = openAbsoluteFile(artifact.path)
                waitForPanelsResolved(editor, 90.seconds)
                val summary = editor.nativeDisassemblySummary(100_000)
                val arch = summary.entryValue("arch")
                val insnCount = summary.entryValue("insnCount")?.toLongOrNull() ?: 0L
                val byteCount = summary.entryValue("byteFallbackCount")?.toLongOrNull() ?: -1L
                val oddVa = summary.entryValue("oddVaRangeCount")?.toIntOrNull() ?: -1
                val rangeCount = summary.entryValue("rangeCount")?.toIntOrNull() ?: 0
                val ratio = if (insnCount > 0) byteCount.toDouble() / insnCount else 1.0
                rows += "${artifact.screenshotPrefix}: arch=$arch ranges=$rangeCount insns=$insnCount " +
                    ".byte=$byteCount (${"%.3f".format(ratio * 100)}%) oddVa=$oddVa"
                logOutput("[thumb-acceptance] ${rows.last()}")
                check(arch == "THUMB") { "${artifact.screenshotPrefix}: expected arch THUMB, got $arch" }
                check(oddVa == 0) { "${artifact.screenshotPrefix}: $oddVa ranges still start at odd addresses" }
                check(insnCount > 0) { "${artifact.screenshotPrefix}: no instructions decoded" }
                // Literal pools (data words inside function ranges, objdump's $d regions) leave a
                // small unknown-byte residue; anything beyond a few percent means wrong-mode decode.
                check(ratio < 0.05) {
                    "${artifact.screenshotPrefix}: .byte fallback ratio ${"%.3f".format(ratio * 100)}% " +
                        "too high; offenders: ${summary.filter { it.startsWith("offender=") }}"
                }
            }

            // --- hello_world -Os main against objdump ground truth ---
            // 00000100 <main>: push {r3, lr}; ldr r1,[pc,#12]; ldr r0,[pc,#12]; bl 32fc <printf>;
            //                  movs r0,#0; pop {r3, pc}; nop; then 2 literal-pool words.
            run {
                closeAllEditors()
                Thread.sleep(500)
                val editor = openAbsoluteFile(
                    HexanaUiDriverTestSupport.corpusElf("apps/hello_world/nrf52840dk-Os/zephyr.elf"),
                )
                waitForPanelsResolved(editor, 90.seconds)
                val probe = editor.nativeDisassemblyInstructions("main", 12)
                check(probe.entryValue("resolvedName") == "main") {
                    "Expected to resolve 'main', got ${probe.entryValue("resolvedName")}"
                }
                check(probe.entryValue("virtualAddress") == "0x100") {
                    "main must land at even 0x100, got ${probe.entryValue("virtualAddress")}"
                }
                val insns = probe.filter { it.startsWith("insn=") }
                    .map { it.removePrefix("insn=").split('|', limit = 3) }
                val addresses = insns.map { it[0] }
                val mnemonics = insns.map { it[1] }
                rows += "hello_world-Os main: " + insns.take(7).joinToString("; ") { "${it[0]} ${it[1]} ${it[2]}" }
                logOutput("[thumb-acceptance] ${rows.last()}")
                // Narrow (2-byte) and wide (4-byte bl) advances, exactly as objdump reports.
                check(addresses.take(7) == listOf("0x100", "0x102", "0x104", "0x106", "0x10a", "0x10c", "0x10e")) {
                    "main instruction addresses diverge from objdump: $addresses"
                }
                // yaxpeax renders the 32-bit wide branch as "bl.w".
                check(mnemonics[0] == "push" && mnemonics[3].startsWith("bl") && mnemonics[5] == "pop" && mnemonics[6] == "nop") {
                    "main mnemonics diverge from objdump: $mnemonics"
                }
            }

            // --- A-profile regression: per-symbol mode, file arch stays ARM ---
            // objdump: arm_add (A32): 10000000 add r0,r0,r1,lsl#1; 10000004 bx lr
            //          thumb_mul (Thumb, st_value 0x10000009): 10000008 mul.w; 1000000c adds; 1000000e bx lr
            run {
                closeAllEditors()
                Thread.sleep(500)
                val editor = openAbsoluteFile(aprofileFixture)
                waitForPanelsResolved(editor, 90.seconds)
                val summary = editor.nativeDisassemblySummary(100)
                check(summary.entryValue("arch") == "ARM") {
                    "A-profile fixture must keep file-level arch ARM, got ${summary.entryValue("arch")}"
                }
                check((summary.entryValue("thumbOverrideCount")?.toIntOrNull() ?: 0) >= 1) {
                    "A-profile fixture must carry a per-symbol THUMB override for thumb_mul: $summary"
                }
                check(summary.entryValue("oddVaRangeCount") == "0") {
                    "A-profile fixture still has odd range starts: $summary"
                }

                val thumb = editor.nativeDisassemblyInstructions("thumb_mul", 8)
                val thumbInsns = thumb.filter { it.startsWith("insn=") }
                    .map { it.removePrefix("insn=").split('|', limit = 3) }
                rows += "aprofile thumb_mul: " + thumbInsns.joinToString("; ") { "${it[0]} ${it[1]} ${it[2]}" }
                logOutput("[thumb-acceptance] ${rows.last()}")
                check(thumb.entryValue("virtualAddress") == "0x10000008") {
                    "thumb_mul must land at even 0x10000008, got ${thumb.entryValue("virtualAddress")}"
                }
                check(thumbInsns.map { it[0] } == listOf("0x10000008", "0x1000000c", "0x1000000e")) {
                    "thumb_mul addresses diverge (wide mul.w then narrow): $thumbInsns"
                }
                check(thumbInsns[0][1].startsWith("mul") && thumbInsns[2][1] == "bx") {
                    "thumb_mul mnemonics diverge: $thumbInsns"
                }

                val arm = editor.nativeDisassemblyInstructions("arm_add", 8)
                val armInsns = arm.filter { it.startsWith("insn=") }
                    .map { it.removePrefix("insn=").split('|', limit = 3) }
                rows += "aprofile arm_add: " + armInsns.joinToString("; ") { "${it[0]} ${it[1]} ${it[2]}" }
                logOutput("[thumb-acceptance] ${rows.last()}")
                check(armInsns.map { it[0] } == listOf("0x10000000", "0x10000004")) {
                    "arm_add must decode as two 4-byte A32 instructions: $armInsns"
                }
                check(armInsns[0][1] == "add" && armInsns[1][1] == "bx") {
                    "arm_add mnemonics diverge: $armInsns"
                }
            }
        }

        Files.writeString(
            Path.of("/tmp/hexana-thumb-acceptance.json"),
            "{\n  \"generatedAt\": \"${java.time.Instant.now()}\",\n  \"rows\": ${rows.toJsonArray()}\n}\n",
        )
        logOutput("Thumb acceptance rows written to /tmp/hexana-thumb-acceptance.json")
    }

    /**
     * Screenshot destination: the `HEXANA_CORPUS_SCREENSHOT_DIR` environment variable when
     * set (local drills point it at a zephyr-corpus checkout), otherwise build/corpus-screenshots.
     */
    private fun corpusScreenshotDir(): Path {
        val override = System.getenv("HEXANA_CORPUS_SCREENSHOT_DIR")?.trim()?.ifBlank { null }
        val dir = override?.let { Path.of(it) } ?: Path.of("build", "corpus-screenshots")
        return dir.toAbsolutePath().normalize().createDirectories()
    }

    /**
     * The macOS accessibility permission prompt opens System Settings in the foreground,
     * which then wins the whole-screen capture. Quit it defensively; ignore all failures.
     */
    private fun quitSystemSettingsQuietly() {
        runCatching {
            ProcessBuilder("osascript", "-e", "tell application \"System Settings\" to quit")
                .start()
                .waitFor()
        }
    }

    private fun List<String>.entryValue(key: String): String? =
        firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    private fun appendDisassemblyInstructionsJson(results: List<DisasmDrillResult>) {
        val jsonPath = Path.of("/tmp/hexana-corpus-observations.json")
        val block = buildString {
            appendLine("  \"disassemblyInstructions\": [")
            results.forEachIndexed { index, result ->
                val instructions = result.probeEntries
                    .filter { it.startsWith("insn=") }
                    .map { entry ->
                        val parts = entry.removePrefix("insn=").split('|', limit = 3)
                        "${parts.getOrElse(0) { "" }} ${parts.getOrElse(1) { "" }} ${parts.getOrElse(2) { "" }}".trim()
                    }
                appendLine("    {")
                appendLine("      \"prefix\": ${result.prefix.toJsonString()},")
                appendLine("      \"query\": ${result.query.toJsonString()},")
                appendLine("      \"uiQueryUsed\": ${result.uiQueryUsed?.toJsonString() ?: "null"},")
                appendLine("      \"resolvedName\": ${result.probeEntries.entryValue("resolvedName")?.toJsonString() ?: "null"},")
                appendLine("      \"arch\": ${result.probeEntries.entryValue("arch")?.toJsonString() ?: "null"},")
                appendLine("      \"virtualAddress\": ${result.probeEntries.entryValue("virtualAddress")?.toJsonString() ?: "null"},")
                appendLine("      \"sizeBytes\": ${result.probeEntries.entryValue("sizeBytes") ?: "null"},")
                appendLine("      \"instructionCount\": ${result.probeEntries.entryValue("instructionCount") ?: "null"},")
                appendLine("      \"instructions\": ${instructions.toJsonArray()},")
                appendLine("      \"errors\": ${result.errors.toJsonArray()},")
                append("      \"screenshotTaken\": ${result.screenshotTaken?.toJsonString() ?: "null"}")
                appendLine()
                append("    }")
                if (index < results.size - 1) append(",")
                appendLine()
            }
            append("  ]")
        }
        val merged = if (Files.exists(jsonPath)) {
            val existingRaw = Files.readString(jsonPath).trimEnd()
            check(existingRaw.endsWith("}")) { "Unexpected observations JSON shape at $jsonPath" }
            // Idempotence: drop a previously appended disassemblyInstructions block
            // (it is always the last top-level key) so reruns replace, not duplicate.
            val keyIdx = existingRaw.indexOf("\"disassemblyInstructions\"")
            val existing = if (keyIdx >= 0) {
                existingRaw.substring(0, existingRaw.lastIndexOf(',', keyIdx)) + "\n}"
            } else {
                existingRaw
            }
            existing.dropLast(1).trimEnd().let { body ->
                val separator = if (body.endsWith("{")) "\n" else ",\n"
                body + separator + block + "\n}\n"
            }
        } else {
            "{\n" + block + "\n}\n"
        }
        Files.writeString(jsonPath, merged)
        logOutput("Disassembly instructions appended to $jsonPath")
    }

    // ---- helpers ----

    private fun waitForPanelsResolved(
        editor: HexanaEditor,
        timeout: kotlin.time.Duration,
    ): org.jetbrains.hexana.ui.framework.HexanaEditorSnapshot {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.panelsResolved) return lastSnapshot
            Thread.sleep(250)
        }
        error("Timed out waiting for panelsResolved; last snapshot: $lastSnapshot")
    }

    private fun waitForSelectedTab(editor: HexanaEditor, tabName: String) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        var lastSnapshot = editor.snapshot()
        while (System.nanoTime() < deadline) {
            lastSnapshot = editor.snapshot()
            if (lastSnapshot.selectedTabName == tabName) return
            Thread.sleep(250)
        }
        error("Timed out waiting for selected tab '$tabName'; snapshot: $lastSnapshot")
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, target: Path) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: run {
                logOutput("WARNING: driver returned null screenshot for '$screenshotName'")
                return
            }
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        if (!source.exists()) {
            logOutput("WARNING: screenshot path does not exist: $source")
            return
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Screenshot saved: $target")
    }

    private fun writeObservationsJson(
        artifactObservations: List<ArtifactObservation>,
        diffObservations: List<DiffObservation>,
    ) {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"generatedAt\": \"${java.time.Instant.now()}\",")
        sb.appendLine("  \"artifactCount\": ${artifactObservations.size},")
        sb.appendLine("  \"artifacts\": [")
        artifactObservations.forEachIndexed { index, obs ->
            sb.appendLine("    {")
            sb.appendLine("      \"prefix\": ${obs.prefix.toJsonString()},")
            sb.appendLine("      \"path\": ${obs.path.toJsonString()},")
            sb.appendLine("      \"providerKind\": ${obs.providerKind.toJsonString()},")
            sb.appendLine("      \"panelsResolved\": ${obs.panelsResolved},")
            sb.appendLine("      \"panelNames\": ${obs.panelNames.toJsonArray()},")
            sb.appendLine("      \"selectedTabAtEnd\": ${obs.selectedTabAtEnd?.toJsonString() ?: "null"},")
            sb.appendLine("      \"topSizeEntries\": ${obs.topSizeEntries.toJsonArray()},")
            sb.appendLine("      \"errors\": ${obs.errors.toJsonArray()},")
            sb.append("      \"screenshotsTaken\": ${obs.screenshotsTaken.toJsonArray()}")
            sb.appendLine()
            sb.append("    }")
            if (index < artifactObservations.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("  ],")
        sb.appendLine("  \"diffs\": [")
        diffObservations.forEachIndexed { index, obs ->
            sb.appendLine("    {")
            sb.appendLine("      \"name\": ${obs.name.toJsonString()},")
            sb.appendLine("      \"leftPath\": ${obs.leftPath.toJsonString()},")
            sb.appendLine("      \"rightPath\": ${obs.rightPath.toJsonString()},")
            sb.appendLine("      \"status\": ${obs.status.toJsonString()},")
            sb.appendLine("      \"changedRowCount\": ${obs.changedRowCount},")
            sb.appendLine("      \"sections\": ${obs.sections.toJsonArray()},")
            sb.appendLine("      \"error\": ${obs.error?.toJsonString() ?: "null"},")
            sb.append("      \"screenshotTaken\": ${obs.screenshotTaken?.toJsonString() ?: "null"}")
            sb.appendLine()
            sb.append("    }")
            if (index < diffObservations.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("  ]")
        sb.appendLine("}")
        val jsonPath = Path.of("/tmp/hexana-corpus-observations.json")
        Files.writeString(jsonPath, sb.toString())
        logOutput("Observations JSON written to $jsonPath")
    }

    private fun String.toJsonString(): String {
        val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun List<String>.toJsonArray(): String {
        if (isEmpty()) return "[]"
        return "[${joinToString(", ") { it.toJsonString() }}]"
    }
}
