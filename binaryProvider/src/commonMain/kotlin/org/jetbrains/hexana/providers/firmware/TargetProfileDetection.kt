package org.jetbrains.hexana.providers.firmware

// ELF e_machine constants used for detection.
private const val EM_ARM: Int = 40
private const val EM_AARCH64: Int = 183
private const val EM_RISCV: Int = 243

/**
 * Detects the [TargetProfile] for an ELF firmware binary from available metadata.
 *
 * Detection precedence (highest authority wins; later signals refine but never
 * downgrade an already-determined [ArchClass] from a higher-confidence source):
 *
 * 1. **CONFIG_BOARD** (from `.config` text): sets [boardId] and [Confidence.Exact].
 * 2. **DTS root compatible**: derives [boardId] from the vendor-tail of the first
 *    token when CONFIG_BOARD is absent. Sets [Confidence.Exact].
 * 3. **DTS cpu compatible**: drives [archClass] toward a specific class.
 *    Sets [Confidence.Heuristic] when no board was found.
 * 4. **ELF header only**: [archClass] from e_machine + EI_CLASS.
 *    Sets [Confidence.ElfOnly].
 *
 * @param header Extracted ELF header primitives.
 * @param dtsText Full text of the merged `zephyr.dts` file, or null when absent.
 * @param configText Full text of the Zephyr `.config` file, or null when absent.
 */
public fun detectTargetProfile(
    header: ElfHeaderFacts,
    dtsText: String?,
    configText: String?,
): TargetProfile {
    // Step 1: board ID from CONFIG_BOARD.
    var boardId: String? = if (configText != null) parseConfigBoard(configText) else null

    // Step 2: DTS root compatible for board ID (when CONFIG_BOARD absent) and
    //         socCompatibles accumulation.
    val rootCompatibles: List<String> = if (dtsText != null) parseDtsRootCompatible(dtsText) else emptyList()
    if (boardId == null && rootCompatibles.isNotEmpty()) {
        // Derive boardId from the first token: e.g. "nordic,nrf52840-dk-nrf52840"
        // → take the part after the vendor comma: "nrf52840-dk-nrf52840".
        val firstToken = rootCompatibles.first()
        val vendorSep = firstToken.indexOf(',')
        boardId = if (vendorSep >= 0) firstToken.substring(vendorSep + 1) else firstToken
    }

    // Step 3: DTS cpu compatible strings.
    val cpuCompatibles: List<String> = if (dtsText != null) parseDtsCpuCompatible(dtsText) else emptyList()
    val allCompatibles = rootCompatibles + cpuCompatibles

    // Step 4: Determine archClass and confidence.
    // Priority: CONFIG_BOARD / DTS root → cpu compatible → ELF header.
    val archClassFromCpu = archClassFromCpuCompatibles(cpuCompatibles)
    val archClassFromElf = archClassFromElfHeader(header)

    val (archClass, confidence) = when {
        // Exact: board known from CONFIG_BOARD or DTS root compatible.
        (configText != null && parseConfigBoard(configText) != null) || rootCompatibles.isNotEmpty() -> {
            // ArchClass from cpu compatible takes precedence over ELF header.
            val ac = archClassFromCpu ?: archClassFromElf
            Pair(ac, Confidence.Exact)
        }
        // Heuristic: cpu compatible present but no board.
        archClassFromCpu != null -> Pair(archClassFromCpu, Confidence.Heuristic)
        // ElfOnly: only ELF header available.
        else -> Pair(archClassFromElf, Confidence.ElfOnly)
    }

    return TargetProfile(
        archClass = archClass,
        boardId = boardId,
        socCompatibles = allCompatibles,
        elfIs64Bit = header.is64Bit,
        elfMachine = header.machine,
        confidence = confidence,
    )
}

/**
 * Derives [ArchClass] from DTS cpu compatible strings.
 *
 * Rules:
 * - Any string starting with `"arm,cortex-a"` → [ArchClass.CortexA].
 * - Any string starting with `"arm,cortex-m"` or `"arm,cortex-r"` → [ArchClass.CortexM].
 * - Any string equal to or starting with `"riscv"` → [ArchClass.RiscV].
 *
 * Returns null when no matching compatible string is found.
 */
private fun archClassFromCpuCompatibles(cpuCompatibles: List<String>): ArchClass? {
    for (compat in cpuCompatibles) {
        val lower = compat.lowercase()
        when {
            lower.startsWith("arm,cortex-a") -> return ArchClass.CortexA
            lower.startsWith("arm,cortex-m") || lower.startsWith("arm,cortex-r") -> return ArchClass.CortexM
            lower.startsWith("riscv") -> return ArchClass.RiscV
        }
    }
    return null
}

/**
 * Derives [ArchClass] from ELF header facts alone.
 *
 * - EM_ARM (40) → [ArchClass.CortexM] (default for ARM32 bare-metal, unless
 *   a Cortex-A cpu compatible was seen — caller handles that override).
 * - EM_AARCH64 (183) → [ArchClass.CortexA].
 * - EM_RISCV (243) → [ArchClass.RiscV].
 * - Other → [ArchClass.Other].
 */
private fun archClassFromElfHeader(header: ElfHeaderFacts): ArchClass = when (header.machine) {
    EM_ARM -> ArchClass.CortexM
    EM_AARCH64 -> ArchClass.CortexA
    EM_RISCV -> ArchClass.RiscV
    else -> ArchClass.Other
}
