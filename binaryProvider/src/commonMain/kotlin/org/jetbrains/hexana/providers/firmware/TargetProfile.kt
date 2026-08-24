package org.jetbrains.hexana.providers.firmware

/**
 * Broad architecture class derived from ELF header and/or DTS compatible strings.
 * Used to select the appropriate QEMU binary and Renode platform strategy.
 */
public enum class ArchClass {
    /** ARM Cortex-A or application-class (AArch64 or 32-bit Cortex-A). */
    CortexA,

    /** ARM Cortex-M or Cortex-R microcontroller / real-time class. */
    CortexM,

    /** RISC-V (either RV32 or RV64). */
    RiscV,

    /** Unknown or unsupported architecture. */
    Other,
}

/**
 * Extracted ELF header fields needed for target detection.
 *
 * This is a plain data transfer object filled by the IntelliJ-layer from the
 * existing [ElfBinaryProvider.header] parse. Keeping it here in commonMain
 * means [detectTargetProfile] is pure and testable without a VirtualFile.
 *
 * @param machine ELF e_machine value (e.g. 40 for EM_ARM, 183 for EM_AARCH64).
 * @param is64Bit True when EI_CLASS is ELFCLASS64.
 * @param flags ELF e_flags (used for ARM-specific sub-type hints). Int is large
 *   enough for all current e_flags values; callers widen from UInt as needed.
 */
public data class ElfHeaderFacts(
    val machine: Int,
    val is64Bit: Boolean,
    val flags: Int,
)

/**
 * Confidence level of the detected [TargetProfile].
 *
 * Higher confidence sources override lower ones; they never downgrade an
 * already-set [archClass] from a higher-confidence source.
 */
public enum class Confidence {
    /** Board identified from CONFIG_BOARD or DTS root compatible property. */
    Exact,

    /** ArchClass derived from DTS cpu compatible strings; no board ID. */
    Heuristic,

    /** Only the ELF header was available; no DTS or config text. */
    ElfOnly,
}

/**
 * Detected target profile for an ELF firmware binary.
 *
 * Produced by [detectTargetProfile]. [qemuMachine] is intentionally absent here;
 * the backend selection ([selectBackend] / [BackendSelection.Ok]) carries the
 * resolved QEMU machine string, so [TargetProfile] stays pure detection output.
 *
 * @param archClass Broad architecture class.
 * @param boardId Board identifier from CONFIG_BOARD or derived from DTS root
 *   compatible's vendor-tail token, or null when neither source is available.
 * @param socCompatibles All compatible strings collected from DTS root and cpu
 *   nodes, in DTS order. Empty when no DTS text was provided.
 * @param elfIs64Bit Whether the ELF is 64-bit (ELFCLASS64).
 * @param elfMachine The e_machine value from the ELF header.
 * @param confidence How the [archClass] and [boardId] were determined.
 */
public data class TargetProfile(
    val archClass: ArchClass,
    val boardId: String?,
    val socCompatibles: List<String>,
    val elfIs64Bit: Boolean,
    val elfMachine: Int,
    val confidence: Confidence,
)
