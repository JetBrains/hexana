package org.jetbrains.hexana.providers.memory

import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.elf.ElfHeader
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSectionType
import org.jetbrains.hexana.providers.elf.ElfProgramHeaderType

// ELF section flag constants (not in ElfConstants.kt)
private const val SHF_ALLOC: ULong = 0x2uL

// ELF file type constants (raw e_type values)
private const val ET_EXEC: Int = 2
private const val ET_DYN: Int = 3

// Segment-merge gap threshold: treat PT_LOAD segments within this many bytes as one region.
private const val SEGMENT_MERGE_GAP: ULong = 65536uL

// ---------------------------------------------------------------------------
// Public data model
// ---------------------------------------------------------------------------

/** Classifies a memory region as FLASH (ROM), RAM, or an unknown type. */
public enum class MemoryRegionKind { FLASH, RAM, OTHER }

/**
 * A named region of the address space with an origin and optional capacity.
 *
 * @param name Region label (e.g. "FLASH", "RAM", "load-0x08000000").
 * @param origin Start address.
 * @param length Capacity in bytes, or null when unknown (e.g. derived from LOAD segments).
 * @param kind Classification used for ROM/RAM budget assignment.
 */
public data class MemoryRegion(
    val name: String,
    val origin: ULong,
    val length: ULong?,
    val kind: MemoryRegionKind,
)

/** Supplemental tags for sections beyond the primary ROM/RAM classification. */
public enum class SectionTag {
    /** Section has no initialization data (.noinit / noinit). Lives in RAM, skips copy-init. */
    NOINIT,

    /** Section appears in both a FLASH (load image) region and a RAM (runtime) region. */
    BOTH_ROM_AND_RAM,
}

/**
 * A single ELF section's contribution to the memory budget.
 *
 * @param name Section name string.
 * @param size Section size in bytes (sh_size).
 * @param vma Virtual Memory Address (sh_addr): where code runs.
 * @param lma Load Memory Address: where the section resides in the load image (ROM for initialized
 *   data copied to RAM at startup).
 * @param occupiesLoadImage True when the section has actual bytes in the load image
 *   (type != SHT_NOBITS and size > 0). BSS/NOINIT sections are false.
 * @param tags Optional supplemental classification flags.
 */
public data class BudgetSection(
    val name: String,
    val size: ULong,
    val vma: ULong,
    val lma: ULong,
    val occupiesLoadImage: Boolean,
    val tags: Set<SectionTag>,
)

/**
 * Usage summary for one [MemoryRegion].
 *
 * @param region The memory region definition.
 * @param usedBytes Sum of sizes of all sections assigned to this region.
 * @param percent Percentage of region capacity used, or null when capacity is unknown or zero.
 * @param sections Sections assigned to this region, sorted by size descending.
 */
public data class RegionUsage(
    val region: MemoryRegion,
    val usedBytes: ULong,
    val percent: Double?,
    val sections: List<BudgetSection>,
)

/** Identifies how region capacity figures were obtained. */
public enum class CapacitySource {
    /** Parsed from a GNU ld or LLD linker map "Memory Configuration" block. */
    LINKER_MAP,

    /** Parsed from a Zephyr merged devicetree text file (zephyr.dts). */
    DEVICETREE,

    /** Parsed from a flattened devicetree blob (.dtb). */
    FDT_BLOB,

    /** Supplied directly by the caller. */
    MANUAL,

    /** Inferred from PT_LOAD segments; capacity is unknown for all regions. */
    LOAD_SEGMENTS,
}

/**
 * The complete memory budget computed from an ELF binary.
 *
 * @param romUsages Usage entries for FLASH-kind regions, sorted by origin ascending.
 * @param ramUsages Usage entries for RAM-kind regions, sorted by origin ascending.
 * @param unmapped Sections with SHF_ALLOC that do not fall inside any known region.
 * @param notLoaded Names of non-alloc sections excluded from budget computation.
 * @param warnings Human-readable diagnostic messages (stale-map disagreement, unmapped sections,
 *   unknown capacity, etc.).
 * @param capacitySource How the region capacity figures were determined.
 * @param capacitySourceDescription Short human-readable label for the UI footer, e.g.
 *   "from zephyr.map" or "from LOAD segments (capacity unknown)".
 */
public data class MemoryBudget(
    val romUsages: List<RegionUsage>,
    val ramUsages: List<RegionUsage>,
    val unmapped: List<BudgetSection>,
    val notLoaded: List<String>,
    val warnings: List<String>,
    val capacitySource: CapacitySource,
    val capacitySourceDescription: String,
)

// ---------------------------------------------------------------------------
// Eligibility check
// ---------------------------------------------------------------------------

/**
 * Returns true when the ELF is a linked executable or shared object (ET_EXEC or ET_DYN) that
 * contains at least one SHF_ALLOC section. Relocatable objects (ET_REL) are not eligible.
 * Returns false when either argument is null.
 */
public fun isMemoryBudgetEligible(header: ElfHeader?, sections: List<ElfSectionHeader>?): Boolean {
    if (header == null || sections == null) return false
    if (header.fileType != ET_EXEC && header.fileType != ET_DYN) return false
    return sections.any { it.flags and SHF_ALLOC != 0uL }
}

// ---------------------------------------------------------------------------
// Region discovery
// ---------------------------------------------------------------------------

/**
 * Parses the "Memory Configuration" block from a GNU ld or LLD linker map text.
 *
 * The block has the form:
 * ```
 * Memory Configuration
 *
 * Name             Origin             Length             Attributes
 * FLASH            0x0000000000000000 0x0000000000100000 xr
 * RAM              0x0000000020000000 0x0000000000040000 rw
 * *default*        0x0000000000000000 0xffffffffffffffff
 * ```
 * Parsing is whitespace-tolerant. The `*default*` row is skipped. Kind: 'w' in attributes =>
 * RAM; 'x' or 'r' without 'w' => FLASH; otherwise OTHER. Returns an empty list when no
 * "Memory Configuration" header is found.
 */
public fun parseLinkerMapRegions(mapText: String): List<MemoryRegion> {
    val lines = mapText.lines()
    val headerIdx = lines.indexOfFirst { it.trim() == "Memory Configuration" }
    if (headerIdx < 0) return emptyList()

    val regions = mutableListOf<MemoryRegion>()
    var i = headerIdx + 1
    // Advance past blank lines and the column-header line (starts with "Name").
    while (i < lines.size && (lines[i].isBlank() || lines[i].trim().startsWith("Name"))) i++

    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) break
        val trimLine = line.trimStart()
        if (trimLine.startsWith("Linker script") || trimLine.startsWith("OUTPUT")) break

        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 3) { i++; continue }

        val name = parts[0]
        if (name == "*default*") { i++; continue }

        val origin = parseHexOrDecimal(parts[1]) ?: run { i++; continue }
        val length = parseHexOrDecimal(parts[2]) ?: run { i++; continue }
        val attrs = if (parts.size >= 4) parts[3].lowercase() else ""

        val kind = when {
            'w' in attrs -> MemoryRegionKind.RAM
            'x' in attrs || 'r' in attrs -> MemoryRegionKind.FLASH
            else -> MemoryRegionKind.OTHER
        }
        regions += MemoryRegion(name = name, origin = origin, length = length, kind = kind)
        i++
    }
    return regions
}

/**
 * Parses Zephyr merged devicetree text (zephyr.dts) for memory and flash regions.
 *
 * This is a heuristic text parser, not a full DTS grammar. It recognises nodes whose name
 * starts with "memory@" or has `device_type = "memory"` (=> RAM), and nodes whose name
 * starts with "flash@" or whose `compatible` contains "soc-nv-flash" (=> FLASH), at any
 * nesting depth. Nodes whose name starts with "flash-controller" or contains "-controller"
 * are not regions and are descended into rather than consumed as a region. Partition
 * sub-nodes (name containing "partition") are skipped. Once a node is accepted as a region
 * its body is not searched further for nested regions.
 *
 * The `reg` property is parsed as either a 2-cell form (addr, size) or a 4-cell form
 * (addrHi, addrLo, sizeHi, sizeLo).
 */
public fun parseDtsMemoryRegions(dtsText: String): List<MemoryRegion> {
    val regions = mutableListOf<MemoryRegion>()
    parseDtsNodes(dtsText.lines(), 0, regions)
    return regions
}

/**
 * Recursive descent over DTS lines starting at [startLine], appending found regions to [out].
 * Returns the line index after the closing brace of the current block, or lines.size if EOF.
 */
private fun parseDtsNodes(lines: List<String>, startLine: Int, out: MutableList<MemoryRegion>): Int {
    var i = startLine
    while (i < lines.size) {
        val trimmed = lines[i].trim()

        // Match a DTS node opening: [label:] name@addr {
        val nodeMatch = Regex("""^(?:\w+\s*:\s*)?(\w[\w-]*)@([0-9a-fA-F]+)\s*\{""").find(trimmed)
        if (nodeMatch != null) {
            val rawName = nodeMatch.groupValues[1]
            val nodeName = rawName.lowercase()
            val nodeAddrHex = nodeMatch.groupValues[2]

            // Collect the node body until the matching close brace.
            val bodyLines = mutableListOf<String>()
            var depth = 1
            var j = i + 1
            while (j < lines.size && depth > 0) {
                val bl = lines[j].trim()
                depth += bl.count { it == '{' } - bl.count { it == '}' }
                if (depth > 0) bodyLines += lines[j]
                j++
            }
            val body = bodyLines.joinToString("\n")

            when {
                "partition" in nodeName -> {
                    // Partition sub-nodes are never regions; skip the whole body.
                }
                "-controller" in nodeName -> {
                    // Controller nodes (e.g. flash-controller@4001e000) are not regions.
                    // Descend into the body to find nested region nodes.
                    parseDtsNodes(bodyLines, 0, out)
                }
                else -> {
                    val hasDeviceTypeMemory = Regex("""device_type\s*=\s*"memory"""").containsMatchIn(body)
                    val compatible = Regex("""compatible\s*=([^;]+);""").find(body)?.groupValues?.get(1) ?: ""
                    val hasSocNvFlash = "soc-nv-flash" in compatible
                    val isFlash = nodeName.startsWith("flash") || hasSocNvFlash
                    val isMemory = nodeName.startsWith("memory") || nodeName.startsWith("sram") ||
                        nodeName.startsWith("psram") || hasDeviceTypeMemory

                    if (isFlash || isMemory) {
                        val regMatch = Regex("""reg\s*=\s*<([^>]+)>""").find(body)
                        if (regMatch != null) {
                            val cellStrings = regMatch.groupValues[1].trim().split(Regex("""\s+"""))
                            val cells = cellStrings.mapNotNull { parseHexOrDecimal(it) }
                            val pair: Pair<ULong, ULong>? = when (cells.size) {
                                2 -> Pair(cells[0], cells[1])
                                4 -> Pair((cells[0] shl 32) or cells[1], (cells[2] shl 32) or cells[3])
                                else -> null
                            }
                            if (pair != null) {
                                val kind = if (isFlash) MemoryRegionKind.FLASH else MemoryRegionKind.RAM
                                out += MemoryRegion(
                                    name = "${nodeName}@${nodeAddrHex}",
                                    origin = pair.first,
                                    length = pair.second,
                                    kind = kind,
                                )
                            }
                        }
                        // Do not descend further into an accepted region node.
                    } else {
                        // Unknown node: descend to find nested regions.
                        parseDtsNodes(bodyLines, 0, out)
                    }
                }
            }
            i = j
            continue
        }
        i++
    }
    return i
}

/**
 * Parses a Flattened Device Tree (FDT/DTB) blob for memory regions.
 *
 * Reads the FDT structure from the beginning of [bytes]. Walks FDT_BEGIN_NODE, FDT_END_NODE,
 * FDT_PROP, and FDT_END tokens. Finds nodes named "memory" or "memory@..." (and nodes with a
 * `device_type` property value of "memory") and reads their `reg` property using
 * `#address-cells` / `#size-cells` from the root node (default 2 / 1 per FDT spec).
 * Returns an empty list on any malformed input.
 */
public fun parseFdtMemoryRegions(bytes: ByteArray): List<MemoryRegion> = parseFdtFrom(bytes, 0)

/**
 * Locates the FDT magic (0xd00dfeed) anywhere in [image] and returns memory regions from the
 * first valid FDT blob found. Returns an empty list when the magic is not found or the blob is
 * malformed.
 */
public fun findFdtBlob(image: ByteArray): List<MemoryRegion> {
    var i = 0
    while (i <= image.size - 4) {
        if (readBigEndianU32(image, i) == FDT_MAGIC) {
            val result = parseFdtFrom(image, i)
            if (result.isNotEmpty()) return result
        }
        i++
    }
    return emptyList()
}

/**
 * Clusters PT_LOAD segments by VMA proximity and creates synthetic [MemoryRegion] entries.
 *
 * Adjacent or near-adjacent segments (gap < 64 KiB) are merged into one cluster. Each cluster
 * produces one region with `kind = OTHER` and `length = null` (capacity unknown).
 */
public fun deriveRegionsFromLoadSegments(programHeaders: List<ElfProgramHeader>): List<MemoryRegion> {
    val loads = programHeaders
        .filter { it.type == ElfProgramHeaderType.Load }
        .sortedBy { it.virtualAddress }
    if (loads.isEmpty()) return emptyList()

    data class Cluster(val start: ULong, var end: ULong)
    val clusters = mutableListOf<Cluster>()
    for (ph in loads) {
        val segEnd = ph.virtualAddress + ph.memorySize
        val last = clusters.lastOrNull()
        if (last != null && ph.virtualAddress <= last.end + SEGMENT_MERGE_GAP) {
            if (segEnd > last.end) last.end = segEnd
        } else {
            clusters += Cluster(ph.virtualAddress, segEnd)
        }
    }
    return clusters.map { c ->
        MemoryRegion(
            name = "load-0x${c.start.toString(16).padStart(8, '0')}",
            origin = c.start,
            length = null,
            kind = MemoryRegionKind.OTHER,
        )
    }
}

// ---------------------------------------------------------------------------
// Stale-map sanity check
// ---------------------------------------------------------------------------

/**
 * Returns true when the linker map and ELF section table appear to be in sync.
 *
 * Matching is driven by the ELF's own alloc-section names. For each name the function
 * also checks the dot-toggled form (strip or prepend a leading dot) so that GNU ld maps
 * with dotted names (.text) match ELFs with undotted names (text), and vice versa.
 *
 * Recognised map-line forms (after the "Linker script and memory map" header):
 * - Single-line: `name   0x<addr>  0x<size>` (with optional trailing fields)
 * - GNU two-line: `name` alone on one line, then `   0x<addr>  0x<size>` on the next
 *
 * A line matches when its first token is in the ELF section-name set (as-is or dot-toggled).
 * The map VMA is compared to that section's sh_addr.
 *
 * Returns true when at least 80 % of matched sections agree, or when fewer than 3 match
 * (benefit of the doubt). Requires [sections] to contain SHF_ALLOC sections.
 */
public fun linkerMapMatchesElf(mapText: String, sections: List<ElfSectionHeader>): Boolean {
    val allocSections = sections.filter { it.flags and SHF_ALLOC != 0uL }
    if (allocSections.isEmpty()) return true

    // Build a lookup from both the canonical name and its dot-toggled variant.
    val sectionByName = buildMap<String, ElfSectionHeader> {
        for (sec in allocSections) {
            put(sec.name, sec)
            val toggled = if (sec.name.startsWith(".")) sec.name.removePrefix(".") else ".${sec.name}"
            if (!containsKey(toggled)) put(toggled, sec)
        }
    }

    val lines = mapText.lines()
    val scriptIdx = lines.indexOfFirst { it.trim().startsWith("Linker script and memory map") }
    if (scriptIdx < 0) return true

    var total = 0
    var agree = 0
    var prevName: String? = null

    for (i in (scriptIdx + 1) until lines.size) {
        val line = lines[i]

        // Single-line form: "  name   0x<addr>  0x<size>  ..." (leading whitespace optional)
        val singleLine = Regex("""^\s*(\S+)\s+(0x[0-9a-fA-F]+)\s+0x[0-9a-fA-F]+""").find(line)
        if (singleLine != null) {
            val name = singleLine.groupValues[1]
            val addr = parseHexOrDecimal(singleLine.groupValues[2])
            val sec = if (addr != null) sectionByName[name] else null
            if (sec != null) { total++; if (sec.address == addr) agree++ }
            prevName = null
            continue
        }
        // Two-line form: first line is the section name alone (not starting with 0x).
        val nameLine = Regex("""^\s*([^0\s]\S*)\s*$""").find(line)
        if (nameLine != null) {
            val candidate = nameLine.groupValues[1]
            // Only treat it as a section-name candidate if it appears in the ELF set.
            prevName = if (sectionByName.containsKey(candidate)) candidate else null
            continue
        }
        // Second line of the two-line form: indented address.
        if (prevName != null) {
            val addrLine = Regex("""^\s+(0x[0-9a-fA-F]+)\s+0x[0-9a-fA-F]+""").find(line)
            if (addrLine != null) {
                val addr = parseHexOrDecimal(addrLine.groupValues[1])
                val sec = if (addr != null) sectionByName[prevName] else null
                if (sec != null) { total++; if (sec.address == addr) agree++ }
            }
            prevName = null
        }
    }

    if (total < 3) return true
    return agree.toDouble() / total.toDouble() >= 0.8
}

/**
 * Orders the linker-map candidates among an ELF's sibling files, most likely first.
 *
 * Order: `<elf-base-name>.map`, then `zephyr.map`, then every other `*.map` sibling in
 * case-insensitive alphabetical order. Only names present in [siblingFileNames] are
 * returned, so every returned name resolves to an existing sibling. Callers must still
 * validate each candidate against the ELF (see [linkerMapMatchesElf]); ordering only
 * decides which file gets the first attempt.
 */
public fun siblingMapCandidates(elfFileName: String, siblingFileNames: Collection<String>): List<String> {
    val baseName = elfFileName.substringBeforeLast('.', elfFileName)
    val present = siblingFileNames.toSet()
    val ordered = listOf("$baseName.map", "zephyr.map").distinct()
        .filter { it in present && it != elfFileName }
        .toMutableList()
    siblingFileNames
        .filter { it.endsWith(".map", ignoreCase = true) && it != elfFileName && it !in ordered }
        .sortedBy { it.lowercase() }
        .forEach { ordered += it }
    return ordered
}

// ---------------------------------------------------------------------------
// Budget computation
// ---------------------------------------------------------------------------

/**
 * Computes the memory budget for an ELF binary.
 *
 * Region discovery priority (highest wins):
 * 1. [manualRegions] if non-empty.
 * 2. Linker map ([mapText]) if it yields non-empty regions.
 * 3. Devicetree ([dtsText]) if it yields non-empty regions.
 * 4. FDT blob ([fdtBytes]) if it yields non-empty regions.
 * 5. Derived from PT_LOAD segments (capacity unknown).
 *
 * When both [mapText] and [dtsText] are provided and both yield non-empty results, the map wins
 * and a warning is added for each region where the origin or length disagrees.
 *
 * Classification is purely structural (flags and type), not name-based. Section names are only
 * used for the [SectionTag.NOINIT] tag (section named "noinit" or ".noinit").
 *
 * @param header ELF file header.
 * @param sections All ELF section headers.
 * @param programHeaders All ELF program headers (may be empty for ET_REL).
 * @param mapText Optional text content of a GNU ld/LLD linker map file.
 * @param dtsText Optional text content of a Zephyr merged devicetree source file.
 * @param fdtBytes Optional raw bytes of an FDT blob.
 * @param manualRegions Caller-supplied regions; if non-empty, all other sources are ignored.
 * @param sourceLabel Optional human-readable label for the capacity source (e.g. "zephyr.map").
 */
public fun computeMemoryBudget(
    header: ElfHeader,
    sections: List<ElfSectionHeader>,
    programHeaders: List<ElfProgramHeader>,
    mapText: String? = null,
    dtsText: String? = null,
    fdtBytes: ByteArray? = null,
    manualRegions: List<MemoryRegion> = emptyList(),
    sourceLabel: String? = null,
): MemoryBudget {
    val warnings = mutableListOf<String>()
    val (regions, capacitySource, capacityDesc) = selectRegions(
        manualRegions, mapText, dtsText, fdtBytes, programHeaders, sourceLabel, warnings,
    )

    val allocSections = sections.filter { it.flags and SHF_ALLOC != 0uL && it.size > 0uL }
    val notLoaded = sections
        .filter { it.flags and SHF_ALLOC == 0uL && it.name.isNotEmpty() }
        .map { it.name }

    val budgetSections = allocSections.map { sec ->
        val lma = resolveLma(sec, programHeaders)
        val occupies = sec.type != ElfSectionType.NoBits && sec.size > 0uL
        val tags: Set<SectionTag> = if (sec.name == "noinit" || sec.name == ".noinit")
            setOf(SectionTag.NOINIT) else emptySet()
        BudgetSection(name = sec.name, size = sec.size, vma = sec.address, lma = lma,
            occupiesLoadImage = occupies, tags = tags)
    }

    val romRegions = regions.filter { it.kind == MemoryRegionKind.FLASH }
    val ramRegions = regions.filter { it.kind == MemoryRegionKind.RAM }
    val otherRegions = regions.filter { it.kind == MemoryRegionKind.OTHER }

    val romAssign = LinkedHashMap<MemoryRegion, MutableList<BudgetSection>>()
    val ramAssign = LinkedHashMap<MemoryRegion, MutableList<BudgetSection>>()
    val otherAssign = LinkedHashMap<MemoryRegion, MutableList<BudgetSection>>()
    for (r in romRegions) romAssign[r] = mutableListOf()
    for (r in ramRegions) ramAssign[r] = mutableListOf()
    for (r in otherRegions) otherAssign[r] = mutableListOf()

    val unmapped = mutableListOf<BudgetSection>()

    for (sec in budgetSections) {
        val flashRegion = if (sec.occupiesLoadImage)
            romRegions.firstOrNull { regionContains(it, sec.lma) } else null
        val ramRegion = ramRegions.firstOrNull { regionContains(it, sec.vma) }
        val otherByVma = if (flashRegion == null && ramRegion == null)
            otherRegions.firstOrNull { regionContains(it, sec.vma) } else null
        val otherByLma = if (sec.occupiesLoadImage && flashRegion == null && ramRegion == null &&
            otherByVma == null)
            otherRegions.firstOrNull { regionContains(it, sec.lma) } else null

        if (flashRegion == null && ramRegion == null && otherByVma == null && otherByLma == null) {
            unmapped += sec
            continue
        }

        val finalSec = if (flashRegion != null && ramRegion != null)
            sec.copy(tags = sec.tags + SectionTag.BOTH_ROM_AND_RAM) else sec

        flashRegion?.let { romAssign.getValue(it) += finalSec }
        ramRegion?.let { ramAssign.getValue(it) += finalSec }
        otherByVma?.let { otherAssign.getValue(it) += finalSec }
        if (otherByLma != null && otherByVma == null) otherAssign.getValue(otherByLma) += finalSec
    }

    if (unmapped.isNotEmpty()) {
        warnings += "${unmapped.size} section(s) fall outside all known memory regions"
    }
    if (capacitySource == CapacitySource.LOAD_SEGMENTS) {
        warnings += "regions inferred from LOAD segments, capacity unknown"
    }

    fun buildUsages(assign: Map<MemoryRegion, List<BudgetSection>>): List<RegionUsage> =
        assign.entries.sortedBy { it.key.origin }.map { (region, secs) ->
            val sorted = secs.sortedByDescending { it.size }
            val used = secs.fold(0uL) { acc, s -> acc + s.size }
            val pct = if (region.length != null && region.length > 0uL)
                used.toDouble() / region.length.toDouble() * 100.0 else null
            RegionUsage(region = region, usedBytes = used, percent = pct, sections = sorted)
        }

    // OTHER regions: sections with occupiesLoadImage belong to ROM view; others to RAM view.
    val otherRom = LinkedHashMap<MemoryRegion, MutableList<BudgetSection>>()
    val otherRam = LinkedHashMap<MemoryRegion, MutableList<BudgetSection>>()
    for ((r, secs) in otherAssign) {
        val romSecs = secs.filter { it.occupiesLoadImage }
        val ramSecs = secs.filter { !it.occupiesLoadImage }
        if (romSecs.isNotEmpty()) otherRom[r] = romSecs.toMutableList()
        if (ramSecs.isNotEmpty()) otherRam[r] = ramSecs.toMutableList()
    }

    val allRomUsages = (buildUsages(romAssign) + buildUsages(otherRom)).sortedBy { it.region.origin }
    val allRamUsages = (buildUsages(ramAssign) + buildUsages(otherRam)).sortedBy { it.region.origin }

    return MemoryBudget(
        romUsages = allRomUsages,
        ramUsages = allRamUsages,
        unmapped = unmapped,
        notLoaded = notLoaded,
        warnings = warnings,
        capacitySource = capacitySource,
        capacitySourceDescription = capacityDesc,
    )
}

/**
 * Convenience extension for use with an [ElfBinaryProvider] instance.
 * Returns null when the binary is not eligible (see [isMemoryBudgetEligible]).
 */
public fun ElfBinaryProvider.computeMemoryBudget(
    mapText: String? = null,
    dtsText: String? = null,
    fdtBytes: ByteArray? = null,
    manualRegions: List<MemoryRegion> = emptyList(),
    sourceLabel: String? = null,
): MemoryBudget? {
    val h = header ?: return null
    val s = sections ?: return null
    if (!isMemoryBudgetEligible(h, s)) return null
    return computeMemoryBudget(
        header = h,
        sections = s,
        programHeaders = programHeaders ?: emptyList(),
        mapText = mapText,
        dtsText = dtsText,
        fdtBytes = fdtBytes,
        manualRegions = manualRegions,
        sourceLabel = sourceLabel,
    )
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private data class RegionSelection(
    val regions: List<MemoryRegion>,
    val source: CapacitySource,
    val description: String,
)

private fun selectRegions(
    manual: List<MemoryRegion>,
    mapText: String?,
    dtsText: String?,
    fdtBytes: ByteArray?,
    programHeaders: List<ElfProgramHeader>,
    sourceLabel: String?,
    warnings: MutableList<String>,
): RegionSelection {
    if (manual.isNotEmpty()) {
        return RegionSelection(manual, CapacitySource.MANUAL, sourceLabel?.let { "from $it" } ?: "manual")
    }
    val mapRegions = mapText?.let { parseLinkerMapRegions(it) }.orEmpty()
    val dtsRegions = dtsText?.let { parseDtsMemoryRegions(it) }.orEmpty()

    if (mapRegions.isNotEmpty() && dtsRegions.isNotEmpty()) {
        val dtsByName = dtsRegions.associateBy { it.name }
        for (mr in mapRegions) {
            val dr = dtsByName[mr.name] ?: continue
            if (mr.origin != dr.origin || mr.length != dr.length) {
                warnings += "linker map and devicetree disagree on ${mr.name}: using the map"
            }
        }
    }
    if (mapRegions.isNotEmpty()) {
        return RegionSelection(mapRegions, CapacitySource.LINKER_MAP, "from ${sourceLabel ?: "linker map"}")
    }
    if (dtsRegions.isNotEmpty()) {
        return RegionSelection(dtsRegions, CapacitySource.DEVICETREE, "from ${sourceLabel ?: "devicetree"}")
    }
    val fdtRegions = fdtBytes?.let { parseFdtMemoryRegions(it) }.orEmpty()
    if (fdtRegions.isNotEmpty()) {
        return RegionSelection(fdtRegions, CapacitySource.FDT_BLOB, "from ${sourceLabel ?: "FDT blob"}")
    }
    val derived = deriveRegionsFromLoadSegments(programHeaders)
    return RegionSelection(derived, CapacitySource.LOAD_SEGMENTS, "from LOAD segments (capacity unknown)")
}

/** Returns the Load Memory Address for [sec] by finding the containing PT_LOAD segment. */
private fun resolveLma(sec: ElfSectionHeader, programHeaders: List<ElfProgramHeader>): ULong {
    if (programHeaders.isEmpty()) return sec.address
    val ph = programHeaders.firstOrNull { ph ->
        ph.type == ElfProgramHeaderType.Load &&
            ph.virtualAddress <= sec.address &&
            sec.address + sec.size <= ph.virtualAddress + ph.memorySize
    } ?: programHeaders.firstOrNull { ph ->
        ph.type == ElfProgramHeaderType.Load &&
            ph.virtualAddress <= sec.address &&
            sec.address + sec.size <= ph.virtualAddress + ph.fileSize
    } ?: return sec.address
    return ph.physicalAddress + (sec.address - ph.virtualAddress)
}

/** Returns true when [addr] falls within [region]'s address range. */
private fun regionContains(region: MemoryRegion, addr: ULong): Boolean {
    if (addr < region.origin) return false
    val len = region.length ?: return true  // unknown capacity: origin-only containment
    return addr < region.origin + len
}

/** Parses a hex (0x...) or decimal string to ULong, returning null on failure. */
private fun parseHexOrDecimal(s: String): ULong? {
    val trimmed = s.trim()
    return if (trimmed.startsWith("0x", ignoreCase = true))
        trimmed.substring(2).toULongOrNull(16)
    else
        trimmed.toULongOrNull()
}

// ---------------------------------------------------------------------------
// FDT parsing internals
// ---------------------------------------------------------------------------

private const val FDT_MAGIC: UInt = 0xd00dfeedu
private const val FDT_BEGIN_NODE: UInt = 0x00000001u
private const val FDT_END_NODE: UInt = 0x00000002u
private const val FDT_PROP: UInt = 0x00000003u
private const val FDT_NOP: UInt = 0x00000004u
private const val FDT_END: UInt = 0x00000009u

private fun parseFdtFrom(bytes: ByteArray, startOffset: Int): List<MemoryRegion> = try {
    parseFdtUnsafe(bytes, startOffset)
} catch (_: Exception) {
    emptyList()
}

private fun parseFdtUnsafe(bytes: ByteArray, base: Int): List<MemoryRegion> {
    if (base + 40 > bytes.size) return emptyList()
    if (readBigEndianU32(bytes, base) != FDT_MAGIC) return emptyList()

    val offDtStruct = readBigEndianU32(bytes, base + 8).toInt()
    val offDtStrings = readBigEndianU32(bytes, base + 12).toInt()
    if (base + offDtStruct > bytes.size || base + offDtStrings > bytes.size) return emptyList()

    // Per-node context accumulated while walking the structure block
    data class NodeCtx(
        val name: String,
        var deviceType: String? = null,
        var reg: ByteArray? = null,
        var addrCells: Int = 2,
        var sizeCells: Int = 1,
    )

    var rootAddrCells = 2
    var rootSizeCells = 1
    val stack = ArrayDeque<NodeCtx>()
    val regions = mutableListOf<MemoryRegion>()
    var pos = base + offDtStruct

    fun alignUp4() { pos = (pos + 3) and 3.inv() }

    while (pos + 4 <= bytes.size) {
        when (readBigEndianU32(bytes, pos).also { pos += 4 }) {
            FDT_BEGIN_NODE -> {
                var end = pos
                while (end < bytes.size && bytes[end] != 0.toByte()) end++
                val name = bytes.decodeToString(pos, end)
                pos = end + 1
                alignUp4()
                val parent = stack.lastOrNull()
                stack.addLast(NodeCtx(
                    name = name,
                    addrCells = parent?.addrCells ?: rootAddrCells,
                    sizeCells = parent?.sizeCells ?: rootSizeCells,
                ))
            }
            FDT_END_NODE -> {
                val ctx = stack.removeLastOrNull() ?: break
                val baseName = ctx.name.substringBefore('@').lowercase()
                if ((baseName == "memory" || ctx.deviceType == "memory") && ctx.reg != null) {
                    val regBytes = ctx.reg!!
                    val pairSize = (ctx.addrCells + ctx.sizeCells) * 4
                    var rpos = 0
                    while (rpos + pairSize <= regBytes.size) {
                        var addr = 0uL
                        for (c in 0 until ctx.addrCells) {
                            addr = (addr shl 32) or readBigEndianU32(regBytes, rpos + c * 4).toULong()
                        }
                        var size = 0uL
                        for (c in 0 until ctx.sizeCells) {
                            size = (size shl 32) or
                                readBigEndianU32(regBytes, rpos + ctx.addrCells * 4 + c * 4).toULong()
                        }
                        regions += MemoryRegion(
                            name = ctx.name, origin = addr, length = size, kind = MemoryRegionKind.RAM,
                        )
                        rpos += pairSize
                    }
                }
            }
            FDT_PROP -> {
                val propLen = readBigEndianU32(bytes, pos).toInt(); pos += 4
                val nameOff = readBigEndianU32(bytes, pos).toInt(); pos += 4
                val propData = bytes.copyOfRange(pos, minOf(pos + propLen, bytes.size))
                pos += propLen
                alignUp4()
                val propName = readFdtString(bytes, base + offDtStrings, nameOff)
                val ctx = stack.lastOrNull() ?: continue
                when (propName) {
                    "#address-cells" -> {
                        val v = readBigEndianU32(propData, 0).toInt()
                        ctx.addrCells = v
                        if (stack.size == 1) rootAddrCells = v
                    }
                    "#size-cells" -> {
                        val v = readBigEndianU32(propData, 0).toInt()
                        ctx.sizeCells = v
                        if (stack.size == 1) rootSizeCells = v
                    }
                    "device_type" -> ctx.deviceType = propData.decodeToString().trimEnd(' ')
                    "reg" -> ctx.reg = propData
                }
            }
            FDT_NOP -> { /* skip */ }
            FDT_END -> break
            else -> break  // unknown token: stop defensively
        }
    }
    return regions
}

private fun readFdtString(bytes: ByteArray, stringsBase: Int, offset: Int): String {
    val start = stringsBase + offset
    var end = start
    while (end < bytes.size && bytes[end] != 0.toByte()) end++
    return bytes.decodeToString(start, end)
}

private fun readBigEndianU32(bytes: ByteArray, offset: Int): UInt {
    if (offset + 4 > bytes.size) return 0u
    return ((bytes[offset].toUInt() and 0xFFu) shl 24) or
        ((bytes[offset + 1].toUInt() and 0xFFu) shl 16) or
        ((bytes[offset + 2].toUInt() and 0xFFu) shl 8) or
        (bytes[offset + 3].toUInt() and 0xFFu)
}
