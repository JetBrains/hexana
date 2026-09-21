package org.jetbrains.hexana.cli.scripting.sarif

import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import java.security.MessageDigest

fun canonicalAnchorString(anchor: ScriptAnchor?): String = when (anchor) {
    is ScriptAnchor.Function -> "function:${anchor.funcidx}"
    is ScriptAnchor.ByteRange -> "bytes:${anchor.offset}:${anchor.length}"
    null -> "file"
}

/** Sort key for results ordering: null anchor sorts after all named anchors. */
internal fun anchorSortKey(anchor: ScriptAnchor?): String = when (anchor) {
    null -> "~"
    else -> canonicalAnchorString(anchor)
}

fun findingFingerprint(finding: CheckFinding): String {
    val input = "${finding.checkId}|${canonicalAnchorString(finding.anchor)}"
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
