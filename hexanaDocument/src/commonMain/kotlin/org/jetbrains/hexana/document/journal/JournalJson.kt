package org.jetbrains.hexana.document.journal

import kotlinx.serialization.json.Json

/**
 * Shared [Json] instance for journal serialization. The discriminator field
 * for [JournalOp] is `op` per the design spec — *not* the kotlinx default
 * `type`. Both production code and tests must use this instance.
 *
 * Pretty-printing is off by default; the IDE plugin can opt in if it wants
 * the on-disk file to be human-readable. Either way, replay is byte-identical
 * because pretty-printing only affects whitespace.
 */
public val JournalJson: Json = Json {
    classDiscriminator = "op"
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = false
}
