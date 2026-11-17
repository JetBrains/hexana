package org.jetbrains.hexana.cli.scripting.sarif

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckRegistrationInfo
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor

private const val SARIF_SCHEMA =
    "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Documents/CommitteeSpecifications/2.1.0/sarif-schema-2.1.0.json"
private const val SARIF_VERSION = "2.1.0"

/**
 * Renders a list of [SarifFinding]s into a SARIF 2.1.0 JSON string.
 *
 * @param checks registered check metadata (drives the rules[] array)
 * @param findings findings to emit as results[], pre-classified by [BaselineStore] if needed
 * @param toolVersion value reported in tool.driver.version
 * @param functionName optional resolver: maps a function index to its name string
 * @param sourceLocation optional resolver: maps a function index to a [SarifSourceLocation]
 */
fun renderSarif(
    checks: List<CheckRegistrationInfo>,
    findings: List<SarifFinding>,
    toolVersion: String = "0.0.0",
    functionName: ((Long) -> String?)? = null,
    sourceLocation: ((Long) -> SarifSourceLocation?)? = null,
): String {
    val rules = checks.sortedBy { it.id }.map { check ->
        buildJsonObject {
            put("id", check.id)
            put("defaultConfiguration", buildJsonObject {
                put("level", check.severity.toSarifLevel())
            })
            if (check.description != null) {
                put("shortDescription", buildJsonObject {
                    put("text", check.description)
                })
            }
        }
    }

    val sortedFindings = findings.sortedWith(
        compareBy(
            { it.finding.checkId },
            { anchorSortKey(it.finding.anchor) },
            { it.finding.message },
        )
    )

    val results = sortedFindings.map { sarifFinding ->
        val finding = sarifFinding.finding
        val fp = sarifFinding.fingerprintOverride ?: findingFingerprint(finding)
        val level = if (sarifFinding.baselineState == BaselineState.ABSENT) {
            "note"
        } else {
            finding.severity.toSarifLevel()
        }

        buildJsonObject {
            put("ruleId", finding.checkId)
            put("level", level)
            put("message", buildJsonObject { put("text", finding.message) })
            put("partialFingerprints", buildJsonObject {
                put("primaryLocationLineHash", fp)
            })

            val locationEntries = buildLocationEntries(finding, functionName, sourceLocation)
            if (locationEntries.isNotEmpty()) {
                put("locations", JsonArray(locationEntries))
            }

            if (sarifFinding.baselineState != null) {
                put("baselineState", sarifFinding.baselineState.toSarifString())
            }
        }
    }

    val sarif = buildJsonObject {
        put("\$schema", SARIF_SCHEMA)
        put("version", SARIF_VERSION)
        put("runs", buildJsonArray {
            add(buildJsonObject {
                put("tool", buildJsonObject {
                    put("driver", buildJsonObject {
                        put("name", "hexana")
                        put("version", toolVersion)
                        put("rules", JsonArray(rules))
                    })
                })
                put("results", JsonArray(results))
            })
        })
    }

    return kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), sarif)
}

private fun buildLocationEntries(
    finding: CheckFinding,
    functionName: ((Long) -> String?)?,
    sourceLocation: ((Long) -> SarifSourceLocation?)?,
): List<JsonElement> {
    return when (val anchor = finding.anchor) {
        is ScriptAnchor.Function -> {
            val srcLoc = sourceLocation?.invoke(anchor.funcidx)
            val name = functionName?.invoke(anchor.funcidx) ?: "func[${anchor.funcidx}]"
            listOf(buildJsonObject {
                if (srcLoc != null) {
                    put("physicalLocation", buildJsonObject {
                        put("artifactLocation", buildJsonObject {
                            put("uri", srcLoc.uri)
                        })
                        put("region", buildJsonObject {
                            put("startLine", srcLoc.startLine)
                        })
                    })
                }
                put("logicalLocations", buildJsonArray {
                    add(buildJsonObject {
                        put("name", name)
                        put("kind", "function")
                    })
                })
            })
        }
        is ScriptAnchor.ByteRange -> {
            listOf(buildJsonObject {
                put("physicalLocation", buildJsonObject {
                    put("artifactLocation", buildJsonObject {})
                    put("region", buildJsonObject {
                        put("byteOffset", anchor.offset)
                        put("byteLength", anchor.length)
                    })
                })
            })
        }
        null -> emptyList()
    }
}

private fun CheckSeverity.toSarifLevel(): String = when (this) {
    CheckSeverity.ERROR -> "error"
    CheckSeverity.WARNING -> "warning"
    CheckSeverity.INFO -> "note"
}

private fun BaselineState.toSarifString(): String = when (this) {
    BaselineState.NEW -> "new"
    BaselineState.UNCHANGED -> "unchanged"
    BaselineState.ABSENT -> "absent"
}
