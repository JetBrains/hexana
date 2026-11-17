package org.jetbrains.hexana.ui

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Project-level persistent storage for user-linked memory capacity sources.
 *
 * Keyed by the absolute path of the ELF binary file opened in the hex editor.
 * Each entry stores either a path to a linked .map or .dts file, or manual
 * flash/ram region origin+size values entered by the user.
 */
@Service(Service.Level.PROJECT)
@State(name = "HexanaMemoryBudget", storages = [Storage("hexanaMemoryBudget.xml")])
class MemoryBudgetSettings : SimplePersistentStateComponent<MemoryBudgetSettingsState>(MemoryBudgetSettingsState()) {

    companion object {
        fun getInstance(project: Project): MemoryBudgetSettings = project.service()
    }

    fun getEntry(elfPath: String): MemoryBudgetEntry? = state.entries[elfPath]

    fun setLinkedFile(elfPath: String, linkedFilePath: String) {
        val entry = MemoryBudgetEntry().also {
            it.linkedFilePath = linkedFilePath
            it.manualFlashOrigin = ""
            it.manualFlashSize = ""
            it.manualRamOrigin = ""
            it.manualRamSize = ""
        }
        state.entries = state.entries.toMutableMap().also { it[elfPath] = entry }
    }

    fun setManualRegions(
        elfPath: String,
        flashOrigin: String,
        flashSize: String,
        ramOrigin: String,
        ramSize: String,
    ) {
        val entry = MemoryBudgetEntry().also {
            it.linkedFilePath = ""
            it.manualFlashOrigin = flashOrigin
            it.manualFlashSize = flashSize
            it.manualRamOrigin = ramOrigin
            it.manualRamSize = ramSize
        }
        state.entries = state.entries.toMutableMap().also { it[elfPath] = entry }
    }

    fun clearEntry(elfPath: String) {
        state.entries = state.entries.toMutableMap().also { it.remove(elfPath) }
    }

    /** Whether the user chose to hide the "linker map not found" notification for this ELF. */
    fun isNotificationDismissed(elfPath: String): Boolean = elfPath in state.dismissedNotifications

    fun dismissNotification(elfPath: String) {
        state.addDismissedNotification(elfPath)
    }
}

class MemoryBudgetSettingsState : BaseState() {
    @get:XCollection
    var entries by map<String, MemoryBudgetEntry>()

    /** ELF paths for which the missing-capacity-source notification was dismissed. */
    @get:XCollection(style = XCollection.Style.v2)
    var dismissedNotifications by stringSet()

    fun addDismissedNotification(elfPath: String) {
        if (dismissedNotifications.add(elfPath)) incrementModificationCount()
    }
}

@Tag("MemoryBudgetEntry")
class MemoryBudgetEntry : BaseState() {
    /** Absolute path to the linked .map or .dts file, or empty if using manual regions. */
    var linkedFilePath by string()

    /** Manual FLASH region origin (hex or decimal string), or empty. */
    var manualFlashOrigin by string()

    /** Manual FLASH region size (hex or decimal string), or empty. */
    var manualFlashSize by string()

    /** Manual RAM region origin (hex or decimal string), or empty. */
    var manualRamOrigin by string()

    /** Manual RAM region size (hex or decimal string), or empty. */
    var manualRamSize by string()
}
