package org.jetbrains.hexana.firmware.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jetbrains.hexana.firmware.Dts2ReplRunner
import org.jetbrains.hexana.firmware.HexanaFirmwareSettings
import org.jetbrains.hexana.firmware.PythonRuntimeDownloader
import org.jetbrains.hexana.providers.firmware.Backend
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.ElfHeaderFacts
import org.jetbrains.hexana.providers.firmware.ReplSource
import org.jetbrains.hexana.providers.firmware.detectTargetProfile
import org.jetbrains.hexana.providers.firmware.selectBackend
import java.io.File

/**
 * Run configuration for launching ELF firmware under QEMU or Renode emulation.
 *
 * [checkConfiguration] validates the ELF path, resolves the target profile and backend
 * selection, and verifies tool availability — all cheaply using cached state and bounded
 * file reads. A [BackendSelection.Fail] result, or a missing tool path, surfaces as a
 * [RuntimeConfigurationError] here (at config-validation time, never at process start).
 *
 * [getState] dispatches to [QemuCommandLineState] or [RenodeCommandLineState] based on
 * the resolved selection. [resolvedSelection] exposes the selection for the debug lane.
 */
class FirmwareRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<FirmwareRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): FirmwareRunConfigurationOptions =
        super.getOptions() as FirmwareRunConfigurationOptions

    var elfPath: String
        get() = options.getElfPath()
        set(value) { options.setElfPath(value) }

    var dtsPath: String
        get() = options.getDtsPath()
        set(value) { options.setDtsPath(value) }

    var configPath: String
        get() = options.getConfigPath()
        set(value) { options.setConfigPath(value) }

    var emulator: EmulatorChoice
        get() = options.getEmulator()
        set(value) { options.setEmulator(value) }

    var extraQemuArgs: String
        get() = options.getExtraQemuArgs()
        set(value) { options.setExtraQemuArgs(value) }

    var extraRescLines: String
        get() = options.getExtraRescLines()
        set(value) { options.setExtraRescLines(value) }

    var stopAtEntry: StopAtEntry
        get() = options.getStopAtEntry()
        set(value) { options.setStopAtEntry(value) }

    var startupTimeoutSeconds: Int
        get() = options.getStartupTimeoutSeconds()
        set(value) { options.setStartupTimeoutSeconds(value) }

    /** Returns the resolved [BackendSelection.Ok] for this configuration, or null when not yet validated. */
    @Volatile
    var resolvedSelection: BackendSelection.Ok? = null
        private set

    override fun checkConfiguration() {
        val path = elfPath
        if (path.isBlank()) throw RuntimeConfigurationError("ELF path is not specified")
        val elfFile = File(path)
        if (!elfFile.exists()) throw RuntimeConfigurationError("ELF file not found: $path")
        if (!elfFile.isFile) throw RuntimeConfigurationError("ELF path is not a file: $path")

        // Read ELF header facts (max 64 bytes — cheap regardless of file size).
        val headerFacts = readElfHeaderFactsFromFile(elfFile)
            ?: throw RuntimeConfigurationError("Not a valid ELF file: $path")

        // Read DTS text from configured dtsPath, or the discovered path if blank.
        val dtsText = readTextIfExists(dtsPath)
        val configText = readTextIfExists(configPath)

        val profile = detectTargetProfile(headerFacts, dtsText, configText)

        val forcedBackend: Backend? = when (emulator) {
            EmulatorChoice.RECOMMENDED -> null
            EmulatorChoice.QEMU -> Backend.Qemu
            EmulatorChoice.RENODE -> Backend.Renode
        }

        val selection = selectBackend(profile, forcedBackend)
        when (selection) {
            is BackendSelection.Fail -> throw RuntimeConfigurationError(
                "${selection.reason}${selection.alternative?.let { " $it" } ?: ""}"
            )
            is BackendSelection.Ok -> {
                resolvedSelection = selection
                validateToolAvailability(selection)
            }
        }
    }

    /**
     * Validates that the required emulator binary is available for [selection].
     *
     * Throws [RuntimeConfigurationError] when a required tool is definitively absent,
     * or [RuntimeConfigurationWarning] when auto-detection is used (tool may work at launch).
     */
    private fun validateToolAvailability(selection: BackendSelection.Ok) {
        val settings = HexanaFirmwareSettings.getInstance()
        when (selection.backend) {
            Backend.Qemu -> {
                val binary = selection.qemuBinary
                    ?: throw RuntimeConfigurationError("Internal error: QEMU selection has no binary")
                val resolved = settings.effectiveQemuPath(binary)
                if (!File(resolved).isFile) {
                    // bare name = PATH lookup; warn rather than hard-error
                    if (File(resolved).name == resolved) {
                        throw RuntimeConfigurationWarning(
                            "${resolved} not found on PATH. Install QEMU via your package manager " +
                                "or set the path in Settings → Build → Firmware Emulator."
                        )
                    } else {
                        throw RuntimeConfigurationError(
                            "QEMU binary not found: $resolved. Set the path in " +
                                "Settings → Build → Firmware Emulator."
                        )
                    }
                }
            }
            Backend.Renode -> {
                val renode = settings.effectiveRenodesPath()
                if (renode == null) {
                    throw RuntimeConfigurationError(
                        "Renode not found. Use the Download button in the Tools section of this run configuration."
                    )
                }
                if (!File(renode).isFile) {
                    throw RuntimeConfigurationError(
                        "Renode not found at configured path: $renode. " +
                            "Update the path in Settings → Build → Firmware Emulator, " +
                            "or re-download via the Tools section of this run configuration."
                    )
                }
                // For Generated .repl, we need dts2repl.
                if (selection.replSource == ReplSource.Generated) {
                    val dts = dtsPath.takeIf { it.isNotBlank() } ?: ""
                    if (dts.isBlank() || !File(dts).exists()) {
                        throw RuntimeConfigurationError(
                            "Renode platform generation requires a zephyr.dts file. " +
                                "Set the DTS path in the run configuration."
                        )
                    }
                    // Check dts2repl is available (managed install or override).
                    val dts2replDir = settings.effectiveDts2replPath()
                    val installDir = Dts2ReplRunner.managedInstallDir()
                    val dts2replAvailable = (dts2replDir != null && File(dts2replDir).isDirectory) ||
                        Dts2ReplRunner.isInstalled(installDir)
                    if (!dts2replAvailable) {
                        throw RuntimeConfigurationError(
                            "dts2repl is required to generate a Renode platform from zephyr.dts, " +
                                "but it is not installed. Use the Fetch button in the Tools section " +
                                "of this run configuration."
                        )
                    }
                    // Check Python is available for dts2repl.
                    val pythonAvailable = run {
                        val configured = settings.effectivePythonPath()
                        if (configured != null && File(configured).isFile) return@run true
                        val pyInstallDir = PythonRuntimeDownloader.pythonInstallDir()
                        if (pyInstallDir.toFile().isDirectory &&
                            PythonRuntimeDownloader.locatePython(pyInstallDir) != null) {
                            return@run true
                        }
                        false  // PATH probe deferred to runtime; cannot block EDT here
                    }
                    if (!pythonAvailable) {
                        throw RuntimeConfigurationWarning(
                            "Python 3.9+ is required to generate a Renode platform from zephyr.dts. " +
                                "Install python3 or use the Download Python button in the Tools section " +
                                "of this run configuration."
                        )
                    }
                }
            }
        }
    }

    override fun getConfigurationEditor(): SettingsEditor<FirmwareRunConfiguration> =
        FirmwareRunSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val selection = resolvedSelection
            ?: error("getState called before checkConfiguration; resolvedSelection is null")
        return when (selection.backend) {
            Backend.Qemu -> QemuCommandLineState(this, environment, selection)
            Backend.Renode -> RenodeCommandLineState(this, environment, selection)
        }
    }
}

/** Reads up to [maxBytes] of text from a file path, returning null if blank/absent. */
private fun readTextIfExists(path: String, maxBytes: Int = 256 * 1024): String? {
    if (path.isBlank()) return null
    val f = File(path)
    if (!f.exists() || !f.isFile) return null
    return f.inputStream().bufferedReader().use { it.read(CharArray(maxBytes)).let { n ->
        if (n <= 0) null else f.readText(Charsets.UTF_8)
    }}
}

/**
 * Reads ELF header facts from a plain [File] (not a VirtualFile).
 * Used in [checkConfiguration], which runs in the EDT-adjacent validation context
 * where a VirtualFile may not be available.
 */
private fun readElfHeaderFactsFromFile(file: File): ElfHeaderFacts? {
    return try {
        val bytes = file.inputStream().use { it.readNBytes(64) }
        if (bytes.size < 20) return null
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 0x45.toByte() ||
            bytes[2] != 0x4c.toByte() || bytes[3] != 0x46.toByte()
        ) return null
        val is64Bit = bytes[4].toInt() == 2
        val littleEndian = bytes[5].toInt() == 1
        val machine = if (littleEndian) {
            (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
        } else {
            ((bytes[18].toInt() and 0xff) shl 8) or (bytes[19].toInt() and 0xff)
        }
        val flagsOffset = if (is64Bit) 48 else 36
        val flags = if (bytes.size >= flagsOffset + 4) {
            if (littleEndian) {
                (bytes[flagsOffset].toInt() and 0xff) or
                    ((bytes[flagsOffset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[flagsOffset + 2].toInt() and 0xff) shl 16) or
                    ((bytes[flagsOffset + 3].toInt() and 0xff) shl 24)
            } else {
                ((bytes[flagsOffset].toInt() and 0xff) shl 24) or
                    ((bytes[flagsOffset + 1].toInt() and 0xff) shl 16) or
                    ((bytes[flagsOffset + 2].toInt() and 0xff) shl 8) or
                    (bytes[flagsOffset + 3].toInt() and 0xff)
            }
        } else 0
        ElfHeaderFacts(machine = machine, is64Bit = is64Bit, flags = flags)
    } catch (_: Exception) {
        null
    }
}
