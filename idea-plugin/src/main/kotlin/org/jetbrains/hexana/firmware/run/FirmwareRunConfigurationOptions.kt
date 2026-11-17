package org.jetbrains.hexana.firmware.run

import com.intellij.execution.configurations.RunConfigurationOptions

/**
 * Persistent options for [FirmwareRunConfiguration].
 *
 * All string fields use blank as the "not set" sentinel.
 * Enum fields are stored as their name strings.
 */
class FirmwareRunConfigurationOptions : RunConfigurationOptions() {

    private val elfPath = string("").provideDelegate(this, "elfPath")
    private val dtsPath = string("").provideDelegate(this, "dtsPath")
    private val configPath = string("").provideDelegate(this, "configPath")

    /**
     * Emulator selector: "RECOMMENDED", "QEMU", or "RENODE".
     * "RECOMMENDED" delegates the choice to [BackendSelection].
     */
    private val emulator = string(EmulatorChoice.RECOMMENDED.name).provideDelegate(this, "emulator")

    /** Extra arguments appended to the QEMU command line (space-separated). */
    private val extraQemuArgs = string("").provideDelegate(this, "extraQemuArgs")

    /** Extra Renode Monitor commands appended before start/gdbServer in the generated .resc. */
    private val extraRescLines = string("").provideDelegate(this, "extraRescLines")

    /**
     * Stop-at-entry policy for debug sessions: "ENTRY", "MAIN", or "NONE".
     * Default: "ENTRY" (break at ELF entry point / Reset_Handler).
     */
    private val stopAtEntry = string(StopAtEntry.ENTRY.name).provideDelegate(this, "stopAtEntry")

    /** Seconds to wait for the first console output before declaring a timeout. */
    private val startupTimeoutSeconds = string("30").provideDelegate(this, "startupTimeoutSeconds")

    fun getElfPath(): String = elfPath.getValue(this) ?: ""
    fun setElfPath(value: String) { elfPath.setValue(this, value) }

    fun getDtsPath(): String = dtsPath.getValue(this) ?: ""
    fun setDtsPath(value: String) { dtsPath.setValue(this, value) }

    fun getConfigPath(): String = configPath.getValue(this) ?: ""
    fun setConfigPath(value: String) { configPath.setValue(this, value) }

    fun getEmulator(): EmulatorChoice =
        EmulatorChoice.fromString(emulator.getValue(this) ?: EmulatorChoice.RECOMMENDED.name)

    fun setEmulator(value: EmulatorChoice) { emulator.setValue(this, value.name) }

    fun getExtraQemuArgs(): String = extraQemuArgs.getValue(this) ?: ""
    fun setExtraQemuArgs(value: String) { extraQemuArgs.setValue(this, value) }

    fun getExtraRescLines(): String = extraRescLines.getValue(this) ?: ""
    fun setExtraRescLines(value: String) { extraRescLines.setValue(this, value) }

    fun getStopAtEntry(): StopAtEntry =
        StopAtEntry.fromString(stopAtEntry.getValue(this) ?: StopAtEntry.ENTRY.name)

    fun setStopAtEntry(value: StopAtEntry) { stopAtEntry.setValue(this, value.name) }

    fun getStartupTimeoutSeconds(): Int =
        startupTimeoutSeconds.getValue(this)?.toIntOrNull()?.coerceAtLeast(1) ?: 30

    fun setStartupTimeoutSeconds(value: Int) { startupTimeoutSeconds.setValue(this, value.toString()) }
}

/**
 * User's emulator choice for a run configuration.
 *
 * [RECOMMENDED] defers to [selectBackend]; [QEMU] and [RENODE] force a specific backend.
 */
enum class EmulatorChoice(val displayName: String) {
    RECOMMENDED("Recommended"),
    QEMU("QEMU"),
    RENODE("Renode");

    companion object {
        fun fromString(s: String): EmulatorChoice =
            entries.firstOrNull { it.name == s } ?: RECOMMENDED
    }
}

/**
 * Where to place the initial breakpoint when starting a debug session.
 *
 * [ENTRY] breaks at the ELF entry point (or `Reset_Handler` for bare-metal targets).
 * [MAIN] breaks at the `main` symbol.
 * [NONE] resumes immediately after attach.
 */
enum class StopAtEntry(val displayName: String) {
    ENTRY("Entry point"),
    MAIN("main()"),
    NONE("None (run freely)");

    companion object {
        fun fromString(s: String): StopAtEntry =
            entries.firstOrNull { it.name == s } ?: ENTRY
    }
}
