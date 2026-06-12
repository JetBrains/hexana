package org.jetbrains.hexana.ui.framework

private const val WIRE_VERSION = "1"
private const val SNAPSHOT_SCHEMA = "hexana-editor-snapshot"
private const val SEARCH_STATE_SCHEMA = "hexana-editor-search-state"
private const val WAT_STATE_SCHEMA = "hexana-editor-wat-state"

internal object HexanaProbePayload {
    fun snapshot(payload: List<String>): HexanaEditorSnapshot =
        parse(payload, SNAPSHOT_SCHEMA) { fields ->
            HexanaEditorSnapshot(
                fileName = fields.value("fileName"),
                providerKind = fields.value("providerKind"),
                panelNames = fields.values("panel"),
                selectedTabName = fields.value("selectedTabName").takeIf { it.isNotEmpty() },
                cursorOffset = fields.optionalInt("cursorOffset"),
            )
        }

    fun searchState(payload: List<String>): HexanaEditorSearchState =
        parse(payload, SEARCH_STATE_SCHEMA) { fields ->
            HexanaEditorSearchState(
                query = fields.value("query"),
                binaryMode = fields.boolean("binaryMode"),
                resultCount = fields.int("resultCount"),
                currentResultIndex = fields.int("currentResultIndex"),
                cursorOffset = fields.optionalInt("cursorOffset"),
            )
        }

    fun watState(payload: List<String>): HexanaEditorWatState =
        parse(payload, WAT_STATE_SCHEMA) { fields ->
            HexanaEditorWatState(
                selectedTabName = fields.value("selectedTabName").takeIf { it.isNotEmpty() },
                conversionState = fields.value("conversionState"),
                containsModuleText = fields.boolean("containsModuleText"),
                containsFunctionText = fields.boolean("containsFunctionText"),
                textSummary = fields.value("textSummary"),
                error = fields.value("error").takeIf { it.isNotEmpty() },
            )
        }

    private fun <T> parse(
        payload: List<String>,
        expectedSchema: String,
        convert: (ProbeFields) -> T,
    ): T {
        val fields = ProbeFields(payload)
        val schema = fields.value("schema")
        val version = fields.value("version")
        if (schema != expectedSchema || version != WIRE_VERSION) {
            payloadError(
                payload,
                "schema mismatch: expected schema=$expectedSchema version=$WIRE_VERSION, " +
                    "actual schema=$schema version=$version",
            )
        }
        return convert(fields)
    }
}

private class ProbeFields(private val payload: List<String>) {
    private val entries: List<Pair<String, String>> = payload.map { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0) {
            payloadError(payload, "malformed entry '$entry'; expected key=value")
        }
        entry.substring(0, separator) to entry.substring(separator + 1)
    }

    fun value(key: String): String {
        val values = values(key)
        if (values.isEmpty()) {
            payloadError(payload, "missing required key '$key'")
        }
        if (values.size > 1) {
            payloadError(payload, "expected one value for key '$key', got ${values.size}")
        }
        return values.single()
    }

    fun values(key: String): List<String> =
        entries.filter { it.first == key }.map { it.second }

    fun int(key: String): Int {
        val value = value(key)
        return value.toIntOrNull()
            ?: payloadError(payload, "key '$key' must be an integer, got '$value'")
    }

    fun optionalInt(key: String): Int? {
        val value = value(key)
        if (value.isEmpty()) {
            return null
        }
        return value.toIntOrNull()
            ?: payloadError(payload, "key '$key' must be an integer or empty, got '$value'")
    }

    fun boolean(key: String): Boolean =
        when (val value = value(key)) {
            "true" -> true
            "false" -> false
            else -> payloadError(payload, "key '$key' must be true or false, got '$value'")
        }
}

private fun payloadError(payload: List<String>, reason: String): Nothing =
    throw AssertionError("Invalid Hexana probe payload: $reason; payload=$payload")
