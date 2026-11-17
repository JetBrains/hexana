package org.jetbrains.hexana.shared.scripting

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Asserts the v2 additions to ScriptApiReference: hexana/console globals, slice types,
 * and the deprecated marker on print.
 */
class ScriptApiReferenceV2Test {

    private val dts: String get() = ScriptApiReference.dts()

    // hexana global

    @Test
    fun dtsDeclaresHexanaConst() {
        assertTrue(dts.contains("declare const hexana: HexanaApi;"), "missing: declare const hexana: HexanaApi;")
    }

    @Test
    fun dtsDeclaresHexanaApiInterface() {
        assertTrue(dts.contains("interface HexanaApi {"), "missing: interface HexanaApi {")
    }

    @Test
    fun dtsHexanaApiHasDefineSlice() {
        assertTrue(dts.contains("defineSlice(spec: SliceSpec): void;"), "missing defineSlice member")
    }

    @Test
    fun dtsHexanaApiHasApiProperty() {
        assertTrue(dts.contains("readonly api: number;"), "missing api property on HexanaApi")
    }

    @Test
    fun dtsHexanaApiHasHas() {
        assertTrue(dts.contains("has(feature: string): boolean;"), "missing has member on HexanaApi")
    }

    // console global

    @Test
    fun dtsDeclaresConsoleConst() {
        assertTrue(dts.contains("declare const console: HexanaConsole;"), "missing: declare const console: HexanaConsole;")
    }

    @Test
    fun dtsDeclaresHexanaConsoleInterface() {
        assertTrue(dts.contains("interface HexanaConsole {"), "missing: interface HexanaConsole {")
    }

    @Test
    fun dtsConsoleHasLog() {
        assertTrue(dts.contains("log(...values: unknown[]): void;"), "missing console.log")
    }

    @Test
    fun dtsConsoleHasError() {
        assertTrue(dts.contains("error(...values: unknown[]): void;"), "missing console.error")
    }

    @Test
    fun dtsConsoleHasDebug() {
        assertTrue(dts.contains("debug(...values: unknown[]): void;"), "missing console.debug")
    }

    // slice types

    @Test
    fun dtsDeclaresSliceColumnTypeUnion() {
        assertTrue(
            dts.contains("type SliceColumnType = \"string\" | \"int\" | \"offset\" | \"funcidx\" | \"bool\";"),
            "missing SliceColumnType union",
        )
    }

    @Test
    fun dtsDeclaresSliceColumnInterface() {
        assertTrue(dts.contains("interface SliceColumn {"), "missing interface SliceColumn")
    }

    @Test
    fun dtsDeclaresSliceParamInterface() {
        assertTrue(dts.contains("interface SliceParam {"), "missing interface SliceParam")
    }

    @Test
    fun dtsDeclaresSliceRowDataInterface() {
        assertTrue(dts.contains("interface SliceRowData {"), "missing interface SliceRowData")
    }

    @Test
    fun dtsDeclaresSliceRowsResultInterface() {
        assertTrue(dts.contains("interface SliceRowsResult {"), "missing interface SliceRowsResult")
    }

    @Test
    fun dtsDeclaresSliceSpecInterface() {
        assertTrue(dts.contains("interface SliceSpec {"), "missing interface SliceSpec")
    }

    @Test
    fun dtsSliceSpecRowsCallbackUsesBinaryType() {
        assertTrue(
            dts.contains("binary: HexanaBinary"),
            "rows() callback must reference HexanaBinary, not a generic type",
        )
    }

    // print @deprecated

    @Test
    fun dtsPrintEntryContainsDeprecatedTag() {
        val printBlock = run {
            val start = dts.indexOf("declare function print(")
            check(start >= 0) { "declare function print not found in dts" }
            val docStart = dts.lastIndexOf("/**", start)
            check(docStart >= 0) { "no /** before declare function print" }
            dts.substring(docStart, start)
        }
        assertTrue(
            printBlock.contains("@deprecated"),
            "the JSDoc block for print must contain @deprecated; actual block:\n$printBlock",
        )
    }

    @Test
    fun dtsPrintDeprecatedTextMentionsConsoleMethods() {
        val idx = dts.indexOf("@deprecated")
        assertTrue(idx >= 0, "@deprecated not found in dts")
        val deprecatedLine = dts.substring(idx, dts.indexOf('\n', idx))
        assertTrue(
            deprecatedLine.contains("console"),
            "@deprecated text must mention console; actual: $deprecatedLine",
        )
    }
}
