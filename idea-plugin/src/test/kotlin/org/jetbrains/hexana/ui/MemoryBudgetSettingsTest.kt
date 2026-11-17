package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.xmlb.XmlSerializer

/**
 * Covers the "Don't show again" choice of the missing-linker-map notification: it has to
 * reach the settings file, or the notification returns on the next IDE start.
 */
class MemoryBudgetSettingsTest : UsefulTestCase() {

    fun testDismissalIsRecordedAsAStateChangeOnce() {
        val settings = MemoryBudgetSettings()
        val before = settings.state.modificationCount

        settings.dismissNotification("/fw/app.elf")
        val afterFirst = settings.state.modificationCount
        settings.dismissNotification("/fw/app.elf")

        assertTrue("a dismissal must mark the state dirty so it is saved", afterFirst > before)
        assertEquals("dismissing again changes nothing", afterFirst, settings.state.modificationCount)
        assertTrue(settings.isNotificationDismissed("/fw/app.elf"))
        assertFalse(settings.isNotificationDismissed("/fw/other.elf"))
    }

    fun testDismissalsSurviveSerialization() {
        val settings = MemoryBudgetSettings()
        settings.dismissNotification("/fw/app.elf")
        settings.dismissNotification("/fw/boot.elf")
        settings.setLinkedFile("/fw/app.elf", "/fw/app.map")

        val element = XmlSerializer.serialize(settings.state)
        val restored = MemoryBudgetSettings()
        restored.loadState(XmlSerializer.deserialize(element, MemoryBudgetSettingsState::class.java))

        assertTrue(restored.isNotificationDismissed("/fw/app.elf"))
        assertTrue(restored.isNotificationDismissed("/fw/boot.elf"))
        assertFalse(restored.isNotificationDismissed("/fw/other.elf"))
        // The dismissed set sits next to the linked entries without disturbing them.
        assertEquals("/fw/app.map", restored.getEntry("/fw/app.elf")?.linkedFilePath)
    }
}
