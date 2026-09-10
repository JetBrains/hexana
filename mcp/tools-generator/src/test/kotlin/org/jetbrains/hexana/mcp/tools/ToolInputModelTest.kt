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
    fun `explicit CLI input model can describe future two Wasm source tool ergonomically`() {
        val spec = futureCompareModulesSpec(twoWasmSourcesWithParamsModel())
        val model = requireNotNull(spec.inputModel)

        assertEquals(spec.inputModel, model)
        assertEquals(listOf("current", "baseline"), model.sources.map { it.name })
        assertEquals(listOf(SourceTargetSpec.Field("current"), SourceTargetSpec.Field("baseline")), model.sources.map { it.target })
        assertEquals(
            CliSourceSpec(
                positionalPathName = "current",
                stdinOption = "--stdin",
                base64Option = "--current-base64",
                autoStdin = true,
            ),
            model.sources.single { it.name == "current" }.cli,
        )
        assertEquals(
            CliSourceSpec(
                pathOption = "--baseline",
                stdinOption = "--baseline-stdin",
                base64Option = "--baseline-base64",
            ),
            model.sources.single { it.name == "baseline" }.cli,
        )
        assertEquals(
            listOf("--limit", "--query", "--operation", "--policy-json"),
            model.params.map { it.cliOption },
        )
        assertEquals(ParamBindingKind.Json, model.params.single { it.cliOption == "--policy-json" }.kind)
        assertEquals("policyJson", model.params.single { it.cliOption == "--policy-json" }.targetField)
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
    fun `CLI source spec rejects duplicate options inside one source`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CliSourceSpec(
                pathOption = "--input",
                stdinOption = "--input",
            )
        }

        assertTrue(error.message.orEmpty().contains("CLI source spec reuses option bindings"))
        assertTrue(error.message.orEmpty().contains("--input"))
    }

    @Test
    fun `input model rejects duplicate CLI param options`() {
        val error = assertFailsWith<IllegalArgumentException> {
            toolInputModel {
                param(name = "limit", cliOption = "--shared")
                param(name = "query", cliOption = "--shared")
            }
        }

        assertTrue(error.message.orEmpty().contains("Duplicate CLI param options"))
        assertTrue(error.message.orEmpty().contains("--shared"))
    }

    @Test
    fun `input model rejects CLI option collisions between sources and params`() {
        val error = assertFailsWith<IllegalArgumentException> {
            toolInputModel {
                wasmSource("current") {
                    cli {
                        pathOption("--limit")
                    }
                }
                param(name = "limit", cliOption = "--limit")
            }
        }

        assertTrue(error.message.orEmpty().contains("Duplicate CLI option bindings"))
        assertTrue(error.message.orEmpty().contains("--limit"))
    }

    @Test
    fun `input model rejects multiple auto stdin sources`() {
        val error = assertFailsWith<IllegalArgumentException> {
            toolInputModel {
                wasmSource("current") {
                    cli {
                        positional("current")
                        autoStdin()
                    }
                }
                wasmSource("baseline") {
                    cli {
                        pathOption("--baseline")
                        autoStdin()
                    }
                }
            }
        }

        assertTrue(error.message.orEmpty().contains("at most one source slot"))
        assertTrue(error.message.orEmpty().contains("current"))
        assertTrue(error.message.orEmpty().contains("baseline"))
    }

    @Test
    fun `explicit input model rejects unknown source target fields`() {
        val error = assertFailsWith<IllegalArgumentException> {
            futureCompareModulesSpec(
                toolInputModel {
                    wasmSource("current", targetField = "missingCurrent") {
                        cli {
                            positional("current")
                        }
                    }
                }
            )
        }

        assertTrue(error.message.orEmpty().contains("unknown input field `missingCurrent`"))
    }

    @Test
    fun `explicit input model rejects Wasm source targeting non Wasm field`() {
        val error = assertFailsWith<IllegalArgumentException> {
            futureCompareModulesSpec(
                toolInputModel {
                    wasmSource("current", targetField = "query") {
                        cli {
                            positional("current")
                        }
                    }
                }
            )
        }

        assertTrue(error.message.orEmpty().contains("kind Wasm"))
        assertTrue(error.message.orEmpty().contains("`query`"))
        assertTrue(error.message.orEmpty().contains("`WasmInput`"))
    }

    @Test
    fun `explicit input model rejects Binary source targeting non Binary field`() {
        val error = assertFailsWith<IllegalArgumentException> {
            futureCompareBinariesSpec(
                toolInputModel {
                    binarySource("binary", targetField = "query") {
                        cli {
                            positional("binary")
                        }
                    }
                }
            )
        }

        assertTrue(error.message.orEmpty().contains("kind Binary"))
        assertTrue(error.message.orEmpty().contains("`query`"))
        assertTrue(error.message.orEmpty().contains("`BinaryInput`"))
    }

    @Test
    fun `explicit input model accepts Binary source targeting Binary field`() {
        val spec = futureCompareBinariesSpec(
            toolInputModel {
                binarySource("binary") {
                    cli {
                        positional("binary")
                    }
                }
            }
        )

        assertEquals(SourceTargetSpec.Field("binary"), requireNotNull(spec.inputModel).sources.single().target)
    }

    @Test
    fun `explicit input model can describe future binary script tool with required script param`() {
        val spec = futureRunBinaryScriptSpec(
            toolInputModel {
                binarySource("binary") {
                    cli {
                        positional("binary")
                        stdin("--stdin")
                        base64("--binary-base64")
                        autoStdin()
                    }
                }
                param(name = "scriptPath", cliOption = "--script")
                param(name = "maxFindings", cliOption = "--max-findings")
                param(name = "dryRun", cliOption = "--dry-run")
            }
        )
        val model = requireNotNull(spec.inputModel)

        assertEquals(listOf("binary"), model.sources.map { it.name })
        assertEquals(SourceTargetSpec.Field("binary"), model.sources.single().target)
        assertEquals(
            listOf("scriptPath", "maxFindings", "dryRun"),
            model.params.map { it.targetField },
        )
        assertEquals(
            listOf("--script", "--max-findings", "--dry-run"),
            model.params.map { it.cliOption },
        )
    }

    @Test
    fun `explicit input model rejects unknown param target fields`() {
        val error = assertFailsWith<IllegalArgumentException> {
            futureCompareModulesSpec(
                toolInputModel {
                    param(name = "limit", targetField = "missingLimit", cliOption = "--limit")
                }
            )
        }

        assertTrue(error.message.orEmpty().contains("unknown input field `missingLimit`"))
    }

    @Test
    fun `explicit input model JSON params must target JSON object fields`() {
        val error = assertFailsWith<IllegalArgumentException> {
            futureCompareModulesSpec(
                toolInputModel {
                    jsonParam(name = "policy", targetField = "query", cliOption = "--policy-json")
                }
            )
        }

        assertTrue(error.message.orEmpty().contains("must target a JSON object input field"))
        assertTrue(error.message.orEmpty().contains("policy"))
    }

    @Test
    fun `Wasm executable CLI binding declares the root source model explicitly`() {
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

        assertEquals(expected, HexanaCliTools.single { it.name == "summarize_module" }.inputModel)
    }

    @Test
    fun `Binary executable CLI binding declares the root source model explicitly`() {
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

        assertEquals(expected, HexanaCliTools.single { it.name == "summarize_binary" }.inputModel)
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

    private fun twoWasmSourcesWithParamsModel(): ToolInputModel =
        toolInputModel {
            wasmSource("current") {
                cli {
                    positional("current")
                    stdin("--stdin")
                    base64("--current-base64")
                    autoStdin()
                }
            }
            wasmSource("baseline", required = false) {
                cli {
                    pathOption("--baseline")
                    stdin("--baseline-stdin")
                    base64("--baseline-base64")
                }
            }
            param(name = "limit", cliOption = "--limit")
            param(name = "query", cliOption = "--query")
            param(name = "operation", cliOption = "--operation")
            jsonParam(name = "policy", targetField = "policyJson", cliOption = "--policy-json")
        }

    private fun futureCompareModulesSpec(inputModel: ToolInputModel): CliTool {
        val contract = tools {
            tool(ToolId("compare_modules")) {
                summary = "Compare two WebAssembly modules."
                input = futureCompareModulesInput()
                output = class_("CompareModulesResult") {
                    string("status").describe("Comparison status.")
                }
            }
        }.single()
        val binding = cliToolBinding(
            ToolId("compare_modules"),
            "compare-modules",
            ToolGroup.Module,
            ToolOutputPolicy.ToolDefined,
            executionStatus = CliExecutionStatus.Executable,
            releaseProducts = setOf(CliReleaseProduct.HexanaCli),
            inputModel = inputModel,
        )
        validateCliBinding(contract, binding)
        return CliTool(contract, binding)
    }

    private fun futureCompareBinariesSpec(inputModel: ToolInputModel): CliTool {
        val contract = tools {
            tool(ToolId("compare_binaries")) {
                summary = "Compare two binaries."
                input = futureCompareBinariesInput()
                output = class_("CompareBinariesResult") {
                    string("status").describe("Comparison status.")
                }
            }
        }.single()
        val binding = cliToolBinding(
            ToolId("compare_binaries"),
            "compare-binaries",
            ToolGroup.Binary,
            ToolOutputPolicy.ToolDefined,
            executionStatus = CliExecutionStatus.Executable,
            releaseProducts = setOf(CliReleaseProduct.HexanaCli),
            inputModel = inputModel,
        )
        validateCliBinding(contract, binding)
        return CliTool(contract, binding)
    }

    private fun futureCompareModulesInput(): ClassSpec =
        WasmInputClass.with("CompareModulesInput") {
            embed("current", WasmInputClass).describe("Current WebAssembly module source.")
            nullable("baseline", of = WasmInputClass).describe("Baseline WebAssembly module source.")
            int("limit", default = 25).describe("Maximum comparison findings.")
            string("query", default = "").describe("Optional query filter.")
            string("operation", default = "diff").describe("Comparison operation.")
            jsonObject("policyJson").describe("Comparison policy JSON object.")
        }

    private fun futureCompareBinariesInput(): ClassSpec =
        BinaryInputClass.with("CompareBinariesInput") {
            embed("binary", BinaryInputClass).describe("Binary source.")
            string("query", default = "").describe("Optional query filter.")
        }

    private fun futureRunBinaryScriptSpec(inputModel: ToolInputModel): CliTool {
        val contract = tools {
            tool(RunBinaryScriptToolId) {
                summary = "Run a script over a binary."
                input = BinaryInputClass.with("RunBinaryScriptInput") {
                    embed("binary", BinaryInputClass).describe("Binary source.")
                    string("scriptPath").describe("Required script file path.")
                    int("maxFindings", default = 25).describe("Maximum finding count.")
                    boolean("dryRun", default = false).describe("Validate without running mutations.")
                }
                output = class_("RunBinaryScriptResult") {
                    string("status").describe("Script run status.")
                }
            }
        }.single()
        val binding = cliToolBinding(
            RunBinaryScriptToolId,
            "run-script",
            ToolGroup.Binary,
            ToolOutputPolicy.ToolDefined,
            executionStatus = CliExecutionStatus.Executable,
            releaseProducts = setOf(CliReleaseProduct.HexanaCliScript),
            inputModel = inputModel,
        )
        validateCliBinding(contract, binding)
        return CliTool(contract, binding)
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
