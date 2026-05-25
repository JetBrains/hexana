package org.jetbrains.hexana.analytics.testing

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class FakePostHogServerTest {
    @Test
    fun recordsAndNormalizesBatchEvents() {
        FakePostHogServer.start().use { server ->
            postJson(
                "${server.url}/batch",
                """
                {
                  "api_key": "test-key",
                  "batch": [
                    {
                      "event": "binary_opened",
                      "distinct_id": "test-user",
                      "properties": {
                        "surface": "ide_plugin",
                        "file_size_bytes": 128
                      }
                    },
                    {
                      "event": "mcp_server_started",
                      "distinct_id": "test-user",
                      "properties": {
                        "transport": "stdio",
                        "embedded_in_ide": false
                      }
                    }
                  ]
                }
                """.trimIndent()
            )

            val events = server.awaitEvents(2)
            events.singleEvent("binary_opened")
                .requireDistinctId("test-user")
                .assertProperty("surface", "ide_plugin")
                .assertProperty("file_size_bytes", 128)

            events.singleEvent("mcp_server_started")
                .assertProperty("transport", "stdio")
                .assertProperty("embedded_in_ide", false)

            assertEquals("POST", server.requests.single().method)
            assertEquals("/batch", server.requests.single().path)
        }
    }

    @Test
    fun recordsAndNormalizesSingleCaptureEvents() {
        FakePostHogServer.start().use { server ->
            postJson(
                "${server.url}/capture",
                """
                {
                  "event": "hex_tab_selected",
                  "distinctId": "node-user",
                  "properties": {
                    "tab_name": "Functions",
                    "surface": "vscode_extension"
                  }
                }
                """.trimIndent()
            )

            server.awaitEvent("hex_tab_selected")
                .requireDistinctId("node-user")
                .assertProperty("tab_name", "Functions")
                .assertProperty("surface", "vscode_extension")
        }
    }

    @Test
    fun canAssertNoEvents() {
        FakePostHogServer.start().use { server ->
            server.assertNoEvents()
        }
    }

    private fun postJson(url: String, body: String) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }

        assertEquals(200, connection.responseCode)
        connection.inputStream.close()
        connection.disconnect()
    }
}
