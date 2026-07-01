package org.jetbrains.hexana.ui.framework

private const val WIRE_VERSION = "1"
private const val LAUNCH_SCHEMA = "hexana-run-configuration-launch"

internal data class HexanaRunConfigurationLaunchSnapshot(
    val wasmFilePath: String,
    val engine: String,
    val mode: String,
    val configurationName: String,
    val selectedConfigurationName: String,
    val selectedConfigurationType: String,
    val wasmtimePath: String,
    val dependencies: List<String>,
) {
    val dependencyCount: Int get() = dependencies.size
}

internal object HexanaRunConfigurationLaunchPayload {
    fun parse(payload: List<String>): HexanaRunConfigurationLaunchSnapshot {
        val fields = LaunchProbeFields(payload)
        val schema = fields.value("schema")
        val version = fields.value("version")
        if (schema != LAUNCH_SCHEMA || version != WIRE_VERSION) {
            launchPayloadError(
                payload,
                "schema mismatch: expected schema=$LAUNCH_SCHEMA version=$WIRE_VERSION, " +
                    "actual schema=$schema version=$version",
            )
        }

        return HexanaRunConfigurationLaunchSnapshot(
            wasmFilePath = fields.value("wasmFilePath"),
            engine = fields.value("engine"),
            mode = fields.value("mode"),
            configurationName = fields.value("configurationName"),
            selectedConfigurationName = fields.value("selectedConfigurationName"),
            selectedConfigurationType = fields.value("selectedConfigurationType"),
            wasmtimePath = fields.value("wasmtimePath"),
            dependencies = fields.values("dependency"),
        )
    }
}

private class LaunchProbeFields(private val payload: List<String>) {
    private val entries: List<Pair<String, String>> = payload.map { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0) {
            launchPayloadError(payload, "malformed entry '$entry'; expected key=value")
        }
        entry.substring(0, separator) to entry.substring(separator + 1)
    }

    fun value(key: String): String {
        val values = values(key)
        if (values.isEmpty()) {
            launchPayloadError(payload, "missing required key '$key'")
        }
        if (values.size > 1) {
            launchPayloadError(payload, "expected one value for key '$key', got ${values.size}")
        }
        return values.single()
    }

    fun values(key: String): List<String> =
        entries.filter { it.first == key }.map { it.second }
}

private fun launchPayloadError(payload: List<String>, reason: String): Nothing =
    throw AssertionError("Invalid Hexana run configuration probe payload: $reason; payload=$payload")
