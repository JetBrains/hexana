package org.jetbrains.hexana.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class CliTestTarget(
    val kind: String,
    val version: String,
    private val launcher: Path,
) {
    fun run(
        args: List<String>,
        environment: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
    ): CliProcessResult = CliRunner.run(
        command = listOf(launcher.toString()) + args,
        stdin = stdin,
        environment = environment,
        unsetJavaHome = kind == "native",
    )

    fun jvmUserHome(home: Path): Map<String, String> =
        if (kind == "jvm") mapOf("JAVA_OPTS" to "-Duser.home=$home") else emptyMap()

    companion object {
        fun fromSystemProperties(): CliTestTarget {
            val launcher = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.launcher")))
            check(launcher.toFile().canExecute()) { "Missing executable launcher: $launcher" }
            return CliTestTarget(
                kind = requireNotNull(System.getProperty("hexana.cli.integration.target")),
                version = requireNotNull(System.getProperty("hexana.cli.integration.version")),
                launcher = launcher,
            )
        }
    }
}

class FakeCliTelemetryServer : AutoCloseable {
    private val requests = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val raw = exchange.requestBody.use { it.readBytes() }
            val body = if (exchange.requestHeaders["Content-Encoding"].orEmpty().any { it.equals("gzip", true) }) {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } else raw
            requests += body.toString(Charsets.UTF_8)
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun awaitEvents(count: Int): List<CliTelemetryEvent> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val events = requests.flatMap(::events)
            if (events.size >= count) return events
            Thread.sleep(20)
        }
        error("Expected $count local telemetry events, got $requests")
    }

    fun assertNoRequests() {
        Thread.sleep(250)
        check(requests.isEmpty()) { "Expected telemetry opt-out to make no local request, got $requests" }
    }

    override fun close() = server.stop(0)

    private fun events(body: String): List<CliTelemetryEvent> {
        val root = JsonParser.parseString(body).asJsonObject
        val entries = root["batch"]?.asJsonArray?.map { it.asJsonObject } ?: listOf(root)
        return entries.mapNotNull { event -> event.toTelemetryEvent() }
    }
}

data class CliTelemetryEvent(
    val productId: String,
    val commandName: String,
    val surface: String,
    val distinctId: String,
    val customPropertyKeys: Set<String>,
)

private fun JsonObject.toTelemetryEvent(): CliTelemetryEvent? {
    if (get("event")?.asString != "cli_command_invoked") return null
    val properties = getAsJsonObject("properties")
    return CliTelemetryEvent(
        productId = properties["product_id"]?.asString ?: return null,
        commandName = properties["command_name"]?.asString ?: return null,
        surface = properties["surface"]?.asString ?: return null,
        distinctId = get("distinct_id")?.asString ?: get("distinctId")?.asString ?: return null,
        customPropertyKeys = properties.keySet().filterNot { it.startsWith("$") || it == "token" }.toSet(),
    )
}
