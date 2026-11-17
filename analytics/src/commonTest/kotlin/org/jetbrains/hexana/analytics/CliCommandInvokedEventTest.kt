package org.jetbrains.hexana.analytics

import kotlin.test.Test
import kotlin.test.assertEquals

class CliCommandInvokedEventTest {
    @Test
    fun commandPropertiesIncludeCliSurface() {
        val event = HexanaEvent.CliCommandInvoked(productId = "hexana-cli", commandName = "version")
        assertEquals("cli_command_invoked", event.name)
        assertEquals(
            mapOf("product_id" to "hexana-cli", "command_name" to "version", "surface" to "cli"),
            event.properties,
        )
    }
}
