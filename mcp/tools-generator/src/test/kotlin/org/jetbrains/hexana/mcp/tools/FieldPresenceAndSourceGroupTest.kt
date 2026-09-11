package org.jetbrains.hexana.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FieldPresenceAndSourceGroupTest {
    @Test
    fun `presence distinguishes required optional defaulted and explicit null`() {
        val schema = class_("Presence") {
            string("required")
            nullable("optional", string())
        }
        val explicitNull = FieldSpec(
            name = "nullableDefault",
            type = string(),
            nullable = true,
            presence = FieldPresence.Defaulted(FieldDefaultValue.Null),
        )

        assertEquals(FieldPresence.Required, schema.fieldRef("required").presence)
        assertEquals(FieldPresence.Optional, schema.fieldRef("optional").presence)
        assertEquals(FieldPresence.Defaulted(FieldDefaultValue.Null), explicitNull.presence)
        assertEquals(null, explicitNull.default, "legacy bridge must not confuse null default with no default")
        assertEquals(FieldPresence.Defaulted(FieldDefaultValue.IntValue(7)), FieldSpec("count", int(), default = 7).presence)

        assertFailsWith<IllegalArgumentException> {
            ClassSpec("NullableRequired", listOf(
                FieldSpec("value", string(), nullable = true, presence = FieldPresence.Required),
            ))
        }.also { assertTrue(it.message.orEmpty().contains("Required presence requires a non-nullable field")) }
        assertFailsWith<IllegalArgumentException> {
            ClassSpec("NonNullableOptional", listOf(
                FieldSpec("value", string(), presence = FieldPresence.Optional),
            ))
        }.also { assertTrue(it.message.orEmpty().contains("Optional presence requires a nullable field")) }

        listOf(int(), long(), double(), uint(), byte(), boolean(), string()).forEachIndexed { index, type ->
            ClassSpec("NullableDefault$index", listOf(
                FieldSpec("value", type, nullable = true, presence = FieldPresence.Defaulted(FieldDefaultValue.Null)),
            ))
            assertFailsWith<IllegalArgumentException> {
                ClassSpec("RequiredDefault$index", listOf(
                    FieldSpec("value", type, presence = FieldPresence.Defaulted(FieldDefaultValue.Null)),
                ))
            }.also { assertTrue(it.message.orEmpty().contains("null default requires a nullable field")) }
        }
    }

    @Test
    fun `exact one source group carries typed complete selections`() {
        val group = WasmInputClass.exactOneSourceGroups.single()

        assertTrue(group.required)
        assertEquals(
            listOf(SourceSelectionKind.Path, SourceSelectionKind.Base64, SourceSelectionKind.Bytes),
            group.selections.map { it.kind },
        )
        assertEquals("WasmInput", group.selections.first().field.ownerName)
        assertEquals(listOf("path", "bytesBase64", "bytes"), group.selections.map { it.field.name })
        assertEquals(listOf("path", "bytesBase64", "bytes"), WasmInputClass.oneOf, "legacy schema bridge")
    }

    @Test
    fun `exact one source groups reject missing duplicate wrong and foreign selections`() {
        val source = sourceShape("Source")
        val foreign = sourceShape("Foreign")

        assertFailsWith<IllegalArgumentException> {
            source.withExactOneSourceGroup("incomplete", pathSource(source.fieldRef("path")))
        }.also { assertTrue(it.message.orEmpty().contains("Path, Base64, and Bytes")) }
        assertFailsWith<IllegalArgumentException> {
            source.withExactOneSourceGroup(
                "duplicate",
                pathSource(source.fieldRef("path")),
                base64Source(source.fieldRef("path")),
                bytesSource(source.fieldRef("bytes")),
            )
        }.also { assertTrue(it.message.orEmpty().contains("duplicate fields")) }
        assertFailsWith<IllegalArgumentException> {
            source.withExactOneSourceGroup(
                "wrong",
                pathSource(source.fieldRef("bytes")),
                base64Source(source.fieldRef("bytesBase64")),
                bytesSource(source.fieldRef("bytesAlt")),
            )
        }.also { assertTrue(it.message.orEmpty().contains("Path field `bytes`")) }
        assertFailsWith<IllegalArgumentException> {
            source.withExactOneSourceGroup(
                "foreign",
                pathSource(foreign.fieldRef("path")),
                base64Source(source.fieldRef("bytesBase64")),
                bytesSource(source.fieldRef("bytes")),
            )
        }.also { assertTrue(it.message.orEmpty().contains("belongs to `Foreign`")) }
    }

    @Test
    fun `source metadata snapshots lists and rejects same shaped foreign owners while rebinding copies`() {
        val originalOneOf = mutableListOf("path", "bytesBase64", "bytes")
        val legacy = ClassSpec("Legacy", sourceShape("LegacyFields").fields, oneOf = originalOneOf)
        originalOneOf.clear()
        assertEquals(listOf("path", "bytesBase64", "bytes"), legacy.oneOf)
        assertFailsWith<UnsupportedOperationException> { (legacy.oneOf as MutableList<String>).clear() }

        val source = sourceShape("Owner")
        val originalSelections = mutableListOf(
            pathSource(source.fieldRef("path")),
            base64Source(source.fieldRef("bytesBase64")),
            bytesSource(source.fieldRef("bytes")),
        )
        val detached = ExactOneSourceGroup("detached", originalSelections)
        originalSelections.clear()
        assertEquals(3, detached.selections.size)
        assertFailsWith<UnsupportedOperationException> { (detached.selections as MutableList<SourceSelection>).clear() }
        val attached = source.withExactOneSourceGroup("attached", *detached.selections.toTypedArray())
        assertFailsWith<UnsupportedOperationException> { (attached.exactOneSourceGroups as MutableList<ExactOneSourceGroup>).clear() }

        val sameNamedForeign = sourceShape("Owner")
        assertFailsWith<IllegalArgumentException> {
            sameNamedForeign.withExactOneSourceGroup("foreign", *detached.selections.toTypedArray())
        }.also { assertTrue(it.message.orEmpty().contains("belongs to")) }

        val copied = attached.copy()
        val renamed = attached.with("RenamedOwner") { }
        assertEquals(copied.fieldRef("path"), copied.exactOneSourceGroups.single().selections.first().field)
        assertEquals(renamed.fieldRef("path"), renamed.exactOneSourceGroups.single().selections.first().field)
        assertTrue(copied.fieldRef("path") != attached.fieldRef("path"))
    }

    @Test
    fun `source-group rebinding preserves class value semantics`() {
        val wasmCopy = WasmInputClass.copy()
        val wasmDerivedWithoutChanges = WasmInputClass.with { }
        val embeddedSource = class_("EmbeddedSource") { embed("wasm", WasmInputClass) }

        assertEquals(WasmInputClass, wasmCopy)
        assertEquals(WasmInputClass.hashCode(), wasmCopy.hashCode())
        assertEquals(WasmInputClass, wasmDerivedWithoutChanges)
        assertEquals(WasmInputClass.hashCode(), wasmDerivedWithoutChanges.hashCode())
        assertEquals(embeddedSource, embeddedSource.copy())
        assertEquals(embeddedSource.hashCode(), embeddedSource.copy().hashCode())
    }

    @Test
    fun `typed CLI targets validate owner converter and stdin bytes selection`() {
        val input = BinaryInputClass.with("TypedCliInput") {
            embed("binary", BinaryInputClass)
            jsonObject("filters")
        }
        val contract = ToolSpec(ToolId("typed_cli"), "Typed CLI.", null, input, resultShape())

        val valid = toolInputModel {
            binarySource("binary", input.fieldRef("binary")) { cli { positional("binary"); stdin("--stdin") } }
            jsonParam("filters", input.fieldRef("filters"), "--filters")
        }
        validateCliBinding(contract, executableBinding(valid))

        val foreignInput = BinaryInputClass.with("ForeignTypedCliInput") { embed("binary", BinaryInputClass) }
        val foreign = toolInputModel {
            binarySource("binary", foreignInput.fieldRef("binary")) { cli { positional("binary") } }
        }
        assertFailsWith<IllegalArgumentException> { validateCliBinding(contract, executableBinding(foreign)) }
            .also { assertTrue(it.message.orEmpty().contains("foreign or stale")) }

        val wrongConverter = toolInputModel {
            jsonParam("filters", input.fieldRef("binary"), "--filters")
        }
        assertFailsWith<IllegalArgumentException> { validateCliBinding(contract, executableBinding(wrongConverter)) }
            .also { assertTrue(it.message.orEmpty().contains("JSON object")) }

        val stdinAsBase64 = valid.copy(sources = valid.sources.map {
            it.copy(stdinSelection = SourceSelectionKind.Base64)
        })
        assertFailsWith<IllegalArgumentException> { validateCliBinding(contract, executableBinding(stdinAsBase64)) }
            .also { assertTrue(it.message.orEmpty().contains("Bytes selection")) }

        val withoutStdin = toolInputModel {
            binarySource("binary", input.fieldRef("binary")) { cli { positional("binary") } }
        }
        validateCliBinding(contract, executableBinding(withoutStdin))
        val impossibleStdinSelection = withoutStdin.copy(sources = withoutStdin.sources.map {
            it.copy(stdinSelection = SourceSelectionKind.Bytes)
        })
        assertFailsWith<IllegalArgumentException> { validateCliBinding(contract, executableBinding(impossibleStdinSelection)) }
            .also { assertTrue(it.message.orEmpty().contains("must be absent without stdin metadata")) }
    }

    @Test
    fun `typed CLI sources reject duplicate targets presence mismatches and wrong root kind`() {
        val input = BinaryInputClass.with("TypedSourceInput") {
            embed("requiredBinary", BinaryInputClass)
            nullable("optionalBinary", BinaryInputClass)
        }
        val contract = ToolSpec(ToolId("typed_sources"), "Typed sources.", null, input, resultShape())

        assertFailsWith<IllegalArgumentException> {
            toolInputModel {
                binarySource("first", input.fieldRef("requiredBinary")) { cli { positional("first") } }
                binarySource("second", input.fieldRef("requiredBinary")) { cli { pathOption("--second") } }
            }
        }.also { assertTrue(it.message.orEmpty().contains("Duplicate input source target fields")) }

        val optionalAsRequired = toolInputModel {
            binarySource("optional", input.fieldRef("optionalBinary")) { cli { positional("optional") } }
        }
        assertFailsWith<IllegalArgumentException> { validateCliBinding(contract, executableBinding(optionalAsRequired, "typed-sources")) }
            .also { assertTrue(it.message.orEmpty().contains("required=true")) }
        val requiredAsOptional = toolInputModel {
            binarySource("required", input.fieldRef("requiredBinary"), required = false) { cli { positional("required") } }
        }
        assertFailsWith<IllegalArgumentException> { validateCliBinding(contract, executableBinding(requiredAsOptional, "typed-sources")) }
            .also { assertTrue(it.message.orEmpty().contains("required=false")) }

        val binaryRoot = ToolSpec(ToolId("binary_root"), "Binary root.", null, BinaryInputClass, resultShape())
        val wasmRoot = toolInputModel {
            source("wasm", SourceKind.Wasm, cli = CliSourceSpec(positionalPathName = "wasm"))
        }
        assertFailsWith<IllegalArgumentException> { validateCliBinding(binaryRoot, executableBinding(wasmRoot, "binary-root")) }
            .also { assertTrue(it.message.orEmpty().contains("requires")) }

        val optionalBinaryRoot = toolInputModel {
            source("binary", SourceKind.Binary, required = false, cli = CliSourceSpec(positionalPathName = "binary"))
        }
        assertFailsWith<IllegalArgumentException> {
            validateCliBinding(binaryRoot, executableBinding(optionalBinaryRoot, "binary-root"))
        }.also { assertTrue(it.message.orEmpty().contains("does not match exact-one source group")) }

        val wasmRootContract = ToolSpec(ToolId("wasm_root"), "Wasm root.", null, WasmInputClass, resultShape())
        val optionalWasmRoot = toolInputModel {
            source("wasm", SourceKind.Wasm, required = false, cli = CliSourceSpec(positionalPathName = "wasm"))
        }
        assertFailsWith<IllegalArgumentException> {
            validateCliBinding(wasmRootContract, executableBinding(optionalWasmRoot, "wasm-root"))
        }.also { assertTrue(it.message.orEmpty().contains("does not match exact-one source group")) }
    }

    private fun sourceShape(name: String) = class_(name) {
        nullable("path", string())
        nullable("bytesBase64", string())
        nullable("bytes", list(int()))
        nullable("bytesAlt", list(int()))
    }

    private fun resultShape() = class_("TypedCliResult") { string("status") }

    private fun executableBinding(model: ToolInputModel, command: String = "typed-cli") = cliToolBinding(
        ToolId(command.replace('-', '_')),
        command,
        ToolGroup.Binary,
        ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        products = setOf(HexanaCliProductSpec.id),
        inputModel = model,
    )
}
