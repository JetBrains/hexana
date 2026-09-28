package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * Returns the display name for a type at [typeIdx], using the name-section
 * type-names subsection when available, falling back to `$type<idx>`.
 * The result always starts with `$`.
 */
fun WasmBinaryProvider.typeDisplayName(typeIdx: Int): String =
    nameSection?.typesSection?.namemap?.get(typeIdx)?.let { "\$$it" } ?: "\$type$typeIdx"

/**
 * Returns the display name for field [fieldIdx] of type [typeIdx] from the
 * name-section fields-name subsection, or null when no name is recorded.
 */
fun WasmBinaryProvider.fieldDisplayName(typeIdx: Int, fieldIdx: Int): String? =
    nameSection?.fieldsNameSection?.namemap?.get(typeIdx)?.get(fieldIdx)?.let { "\$$it" }
