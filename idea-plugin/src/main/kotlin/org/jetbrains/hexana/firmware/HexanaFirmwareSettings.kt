package org.jetbrains.hexana.firmware

import com.intellij.openapi.components.*
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.providers.firmware.QemuBinary
import org.jetbrains.hexana.providers.firmware.toCliName
import java.io.File

/**
 * Application-level persistent settings for firmware emulator tool paths
 * (QEMU system binaries, Renode, dts2repl).
 *
 * These paths are used by the firmware run/debug pipeline to locate required
 * tools. Blank fields fall back to [EmulatorDetection]-based auto-detection.
 */
@Service
@State(name = "HexanaFirmwareSettings", storages = [Storage("hexana-firmware.xml", roamingType = RoamingType.PER_OS)])
final class HexanaFirmwareSettings :
    SimplePersistentStateComponent<HexanaFirmwareSettingsState>(HexanaFirmwareSettingsState()) {

    companion object {
        @JvmStatic
        fun getInstance(): HexanaFirmwareSettings = service()
    }

    /** User-configured `qemu-system-arm` path for UI binding. */
    var qemuSystemArmPathInUi: String?
        get() = state.qemuSystemArmPath
        set(value) { state.qemuSystemArmPath = value?.takeIf { it.isNotBlank() } }

    /** User-configured `qemu-system-aarch64` path for UI binding. */
    var qemuSystemAarch64PathInUi: String?
        get() = state.qemuSystemAarch64Path
        set(value) { state.qemuSystemAarch64Path = value?.takeIf { it.isNotBlank() } }

    /** User-configured `qemu-system-riscv32` path for UI binding. */
    var qemuSystemRiscv32PathInUi: String?
        get() = state.qemuSystemRiscv32Path
        set(value) { state.qemuSystemRiscv32Path = value?.takeIf { it.isNotBlank() } }

    /** User-configured `qemu-system-riscv64` path for UI binding. */
    var qemuSystemRiscv64PathInUi: String?
        get() = state.qemuSystemRiscv64Path
        set(value) { state.qemuSystemRiscv64Path = value?.takeIf { it.isNotBlank() } }

    /** User-configured Renode executable path for UI binding. */
    var renodePathInUi: String?
        get() = state.renodePath
        set(value) { state.renodePath = value?.takeIf { it.isNotBlank() } }

    /** Directory where the Renode portable download is installed. */
    var renodeDownloadDirInUi: String?
        get() = state.renodeDownloadDir
        set(value) { state.renodeDownloadDir = value?.takeIf { it.isNotBlank() } }

    /**
     * Path to a local dts2repl checkout or extracted download directory.
     * When blank, [Dts2ReplRunner] uses its own managed download under the plugin cache.
     */
    var dts2replPathInUi: String?
        get() = state.dts2replPath
        set(value) { state.dts2replPath = value?.takeIf { it.isNotBlank() } }

    /**
     * Returns the effective `qemu-system-arm` path: configured value, then auto-detected,
     * then bare command name (PATH lookup at process-start time).
     */
    fun effectiveQemuSystemArmPath(): String? {
        val configured = state.qemuSystemArmPath
        if (!configured.isNullOrBlank()) return configured
        return EmulatorDetection.detectQemu(QemuBinary.SystemArm)?.path
    }

    /**
     * Returns the effective `qemu-system-aarch64` path.
     */
    fun effectiveQemuSystemAarch64Path(): String? {
        val configured = state.qemuSystemAarch64Path
        if (!configured.isNullOrBlank()) return configured
        return EmulatorDetection.detectQemu(QemuBinary.SystemAarch64)?.path
    }

    /**
     * Returns the effective `qemu-system-riscv32` path.
     */
    fun effectiveQemuSystemRiscv32Path(): String? {
        val configured = state.qemuSystemRiscv32Path
        if (!configured.isNullOrBlank()) return configured
        return EmulatorDetection.detectQemu(QemuBinary.SystemRiscv32)?.path
    }

    /**
     * Returns the effective `qemu-system-riscv64` path.
     */
    fun effectiveQemuSystemRiscv64Path(): String? {
        val configured = state.qemuSystemRiscv64Path
        if (!configured.isNullOrBlank()) return configured
        return EmulatorDetection.detectQemu(QemuBinary.SystemRiscv64)?.path
    }

    /**
     * Returns the effective QEMU binary path for the given [QemuBinary] variant.
     * Returns `null` if the tool is neither configured nor detected on the system.
     */
    fun effectiveQemuPath(binary: QemuBinary): String? {
        return when (binary) {
            QemuBinary.SystemArm -> effectiveQemuSystemArmPath()
            QemuBinary.SystemAarch64 -> effectiveQemuSystemAarch64Path()
            QemuBinary.SystemRiscv32 -> effectiveQemuSystemRiscv32Path()
            QemuBinary.SystemRiscv64 -> effectiveQemuSystemRiscv64Path()
        } ?: binary.toCliName() // bare name for PATH fallback at launch time
    }

    /**
     * Returns the effective Renode executable path, or `null` if not found.
     */
    fun effectiveRenodesPath(): String? {
        val configured = state.renodePath
        if (!configured.isNullOrBlank()) return configured
        return EmulatorDetection.detectRenode()?.path
    }

    /**
     * Returns the Renode download directory.
     * Defaults to `<system-cache>/hexana-firmware/renode-1.16.1` when not configured.
     */
    fun effectiveRenodeDownloadDir(): String {
        val configured = state.renodeDownloadDir
        if (!configured.isNullOrBlank()) return configured
        val base = com.intellij.openapi.application.PathManager.getSystemPath()
        return "$base/hexana-firmware/renode-${RenodeDownloader.PINNED_RENODE_VERSION}"
    }

    /**
     * Returns the effective dts2repl directory path (configured override), or `null`
     * to signal [Dts2ReplRunner] that it should use its own managed download.
     */
    fun effectiveDts2replPath(): String? {
        val configured = state.dts2replPath
        return configured?.takeIf { it.isNotBlank() && File(it).isDirectory }
    }

    /** User-configured GDB (DAP) path for UI binding. */
    var gdbPathInUi: String?
        get() = state.gdbPath
        set(value) { state.gdbPath = value?.takeIf { it.isNotBlank() } }

    /**
     * Returns the effective GDB path override, or null if not configured
     * (auto-detection via [EmulatorDetection.detectGdb] will run).
     */
    fun effectiveGdbPath(): String? = state.gdbPath?.takeIf { it.isNotBlank() }
}

/** Persistent state for [HexanaFirmwareSettings]. All fields `by string()` and nullable. */
class HexanaFirmwareSettingsState : BaseState() {
    var qemuSystemArmPath: String? by string()
    var qemuSystemAarch64Path: String? by string()
    var qemuSystemRiscv32Path: String? by string()
    var qemuSystemRiscv64Path: String? by string()
    var renodePath: String? by string()
    var renodeDownloadDir: String? by string()
    var dts2replPath: String? by string()
    var gdbPath: String? by string()
}
