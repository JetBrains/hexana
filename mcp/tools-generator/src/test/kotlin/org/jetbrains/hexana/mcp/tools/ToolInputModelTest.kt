package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolInputModelTest {

    @Test
    fun `single root Wasm source can be declared`() {
        val model = toolInputModel {
            source(
                name = "wasm",
                kind = SourceKind.Wasm,
                cli = rootWasmCli(),
            )
        }

        assertEquals(
            ToolInputModel(
                sources = listOf(
                    SourceSlotSpec(
                        name = "wasm",
                        kind = SourceKind.Wasm,
                        target = SourceTargetSpec.RootInput,
                        required = true,
                        cli = rootWasmCli(),
                    )
                ),
            ),
            model,
        )
    }

    @Test
    fun `single root Binary source can be declared`() {
        val model = toolInputModel {
            source(
                name = "binary",
                kind = SourceKind.Binary,
                cli = rootBinaryCli(),
            )
        }

        assertEquals(
            ToolInputModel(
                sources = listOf(
                    SourceSlotSpec(
                        name = "binary",
                        kind = SourceKind.Binary,
                        target = SourceTargetSpec.RootInput,
                        required = true,
                        cli = rootBinaryCli(),
                    )
                ),
            ),
            model,
        )
    }

    @Test
    fun `two Wasm source slots can use distinct CLI options`() {
        val model = toolInputModel {
            source(
                name = "currentWasm",
                kind = SourceKind.Wasm,
                target = SourceTargetSpec.Field("current"),
                cli = CliSourceSpec(
                    pathOption = "--current-wasm",
                    stdinOption = "--current-stdin",
                    base64Option = "--current-wasm-base64",
                    bytesOption = "--current-wasm-bytes",
                ),
            )
            source(
                name = "baselineWasm",
                kind = SourceKind.Wasm,
                target = SourceTargetSpec.Field("baseline"),
                required = false,
                cli = CliSourceSpec(
                    pathOption = "--baseline-wasm",
                    stdinOption = "--baseline-stdin",
                    base64Option = "--baseline-wasm-base64",
                    bytesOption = "--baseline-wasm-bytes",
                ),
            )
        }

        assertEquals(listOf("currentWasm", "baselineWasm"), model.sources.map { it.name })
        assertEquals(listOf(SourceTargetSpec.Field("current"), SourceTargetSpec.Field("baseline")), model.sources.map { it.target })
        assertEquals(listOf(true, false), model.sources.map { it.required })
    }

    @Test
    fun `input model rejects duplicate source names`() {
        val error = assertFailsWith<IllegalArgumentException> {
            toolInputModel {
                source(
                    name = "wasm",
                    kind = SourceKind.Wasm,
                    target = SourceTargetSpec.Field("current"),
                    cli = CliSourceSpec(pathOption = "--current-wasm"),
                )
                source(
                    name = "wasm",
                    kind = SourceKind.Wasm,
                    target = SourceTargetSpec.Field("baseline"),
                    cli = CliSourceSpec(pathOption = "--baseline-wasm"),
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("Duplicate input source names"))
        assertTrue(error.message.orEmpty().contains("wasm"))
    }

    @Test
    fun `input model rejects ambiguous duplicate CLI source options`() {
        val error = assertFailsWith<IllegalArgumentException> {
            toolInputModel {
                source(
                    name = "currentWasm",
                    kind = SourceKind.Wasm,
                    target = SourceTargetSpec.Field("current"),
                    cli = CliSourceSpec(pathOption = "--input-wasm"),
                )
                source(
                    name = "baselineWasm",
                    kind = SourceKind.Wasm,
                    target = SourceTargetSpec.Field("baseline"),
                    cli = CliSourceSpec(pathOption = "--input-wasm"),
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("Duplicate CLI source bindings"))
        assertTrue(error.message.orEmpty().contains("--input-wasm"))
    }

    @Test
    fun `legacy WasmOnly converts to an equivalent root source model`() {
        val expected = ToolInputModel(
            sources = listOf(
                SourceSlotSpec(
                    name = "wasm",
                    kind = SourceKind.Wasm,
                    target = SourceTargetSpec.RootInput,
                    required = true,
                    cli = rootWasmCli(),
                )
            ),
        )

        assertEquals(expected, ToolInputPattern.WasmOnly.inputModelForCli())
        assertEquals(expected, HexanaTools.single { it.name == "summarize_module" }.inputModelForCli())
    }

    @Test
    fun `legacy BinaryOnly converts to an equivalent root source model`() {
        val expected = ToolInputModel(
            sources = listOf(
                SourceSlotSpec(
                    name = "binary",
                    kind = SourceKind.Binary,
                    target = SourceTargetSpec.RootInput,
                    required = true,
                    cli = rootBinaryCli(),
                )
            ),
        )

        assertEquals(expected, ToolInputPattern.BinaryOnly.inputModelForCli())
        assertEquals(expected, HexanaTools.single { it.name == "summarize_binary" }.inputModelForCli())
    }

    @Test
    fun `ordinary and JSON params can be reserved`() {
        val model = toolInputModel {
            param(name = "limit", cliOption = "--limit")
            jsonParam(name = "filters", targetField = "filterJson", cliOption = "--filters-json")
        }

        assertEquals(
            listOf(
                ParamBindingSpec(
                    name = "limit",
                    kind = ParamBindingKind.Value,
                    targetField = "limit",
                    cliOption = "--limit",
                ),
                ParamBindingSpec(
                    name = "filters",
                    kind = ParamBindingKind.Json,
                    targetField = "filterJson",
                    cliOption = "--filters-json",
                ),
            ),
            model.params,
        )
    }

    private fun rootWasmCli(): CliSourceSpec =
        CliSourceSpec(
            positionalPathName = "wasm",
            stdinOption = "--stdin",
            base64Option = "--bytes-base64",
            autoStdin = true,
        )

    private fun rootBinaryCli(): CliSourceSpec =
        CliSourceSpec(
            positionalPathName = "binary",
            stdinOption = "--stdin",
            base64Option = "--bytes-base64",
            autoStdin = true,
        )
}
