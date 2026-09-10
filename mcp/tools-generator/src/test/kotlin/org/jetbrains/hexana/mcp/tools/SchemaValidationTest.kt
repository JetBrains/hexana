package org.jetbrains.hexana.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaValidationTest {
    @Test
    fun `ClassSpec snapshots caller fields while preserving data class operations`() {
        val expected = listOf(FieldSpec("value", int()), FieldSpec("other", string()))
        val source = expected.toMutableList()
        val spec = ClassSpec("Snapshot", source)
        source += FieldSpec("duplicate", int(), serialName = "value")
        assertEquals(expected, spec.fields)
        assertFailsWith<UnsupportedOperationException> {
            (spec.fields as MutableList<FieldSpec>) += FieldSpec("invalid", int(), serialName = "value")
        }
        assertEquals(expected, spec.fields)

        val copiedExpected = listOf(FieldSpec("copyValue", int()), FieldSpec("copyOther", string()))
        val copiedSource = copiedExpected.toMutableList()
        val copied = spec.copy(name = "Copied", fields = copiedSource)
        copiedSource += FieldSpec("duplicate", int(), serialName = "copyValue")
        assertEquals(copiedExpected, copied.fields)
        assertFailsWith<UnsupportedOperationException> {
            (copied.fields as MutableList<FieldSpec>) += FieldSpec("invalid", int(), serialName = "copyValue")
        }
        assertEquals(copiedExpected, copied.fields)

        val equivalent = ClassSpec("Snapshot", expected)
        assertEquals(spec, equivalent)
        assertEquals(spec.hashCode(), equivalent.hashCode())
        assertTrue(spec.toString().startsWith("ClassSpec(name=Snapshot, fields=[FieldSpec(name=value,"))
        val (name, fields, oneOf, kotlinFqn, inputCapabilities, outputCapabilities) = spec
        assertEquals("Snapshot", name)
        assertEquals(spec.fields, fields)
        assertEquals(null, oneOf)
        assertEquals(null, kotlinFqn)
        assertEquals(spec.inputCapabilities, inputCapabilities)
        assertEquals(spec.outputCapabilities, outputCapabilities)
    }

    @Test
    fun `nested schemas retain snapshots after source mutation`() {
        val expected = listOf(FieldSpec("value", int()), FieldSpec("other", string()))
        val source = expected.toMutableList()
        val nested = ClassSpec("Nested", source)
        val outer = class_("Outer") {
            embed("embedded", nested)
            list("items", nested)
            map("mapping", nested)
        }
        source += FieldSpec("duplicate", int(), serialName = "value")
        assertFailsWith<UnsupportedOperationException> {
            (nested.fields as MutableList<FieldSpec>) += FieldSpec("invalid", int(), serialName = "value")
        }
        outer.fields.forEach { field ->
            val nestedType = when (val type = field.type) {
                is FieldType.ClassRef -> type
                is FieldType.ListOf -> type.element as FieldType.ClassRef
                is FieldType.MapOf -> type.value as FieldType.ClassRef
                else -> error("Expected nested class field")
            }
            assertEquals(expected, nestedType.clazz.fields)
        }
    }

    @Test
    fun `wire keys reject aliases plain fields inherited fields and direct construction`() {
        assertInvalid("Alias", "shared", "first", "second") {
            class_("Alias") {
                int("first").serialName("shared")
                int("second").serialName("shared")
            }
        }
        assertInvalid("AliasPlain", "shared", "first", "shared") {
            class_("AliasPlain") {
                int("first").serialName("shared")
                int("shared")
            }
        }
        val base = class_("Base") { int("first").serialName("shared") }
        assertInvalid("Derived", "shared", "first", "second") {
            base.with("Derived") { int("second").serialName("shared") }
        }
        assertInvalid("Direct", "shared", "first", "second") {
            ClassSpec("Direct", listOf(
                FieldSpec("first", int(), serialName = "shared"),
                FieldSpec("second", int(), serialName = "shared"),
            ))
        }
        val valid = ClassSpec("Direct", listOf(FieldSpec("first", int())))
        assertInvalid("Direct", "shared", "first", "second") {
            valid.copy(fields = listOf(
                FieldSpec("first", int(), serialName = "shared"),
                FieldSpec("second", int(), serialName = "shared"),
            ))
        }
        assertInvalid("Direct", "maxItems must not be negative") {
            valid.copy(fields = listOf(FieldSpec("items", list(int()), maxItems = SchemaIntValue.Literal(-1))))
        }
    }

    @Test
    fun `invalid nested specifications fail before an outer schema can use them`() {
        assertInvalidNested { nested -> class_("Outer") { embed("embedded", nested) } }
        assertInvalidNested { nested -> class_("Outer") { list("items", nested) } }
        assertInvalidNested { nested -> class_("Outer") { map("items", nested) } }
        val nested = ClassSpec("Nested", listOf(FieldSpec("value", int())))
        class_("Outer") {
            embed("embedded", nested)
            list("items", nested)
            map("mapping", nested)
        }
    }

    @Test
    fun `validation observes final fluent values only`() {
        class_("Corrected") {
            int("first").serialName("shared")
            int("second").serialName("shared").serialName("other")
            int("range").minimum(10).maximum(1).maximum(10)
        }
    }

    @Test
    fun `size constraints reject negative literals but retain symbolic values`() {
        assertInvalid("Broken", "maxItems must not be negative") {
            class_("Broken") { list("items", of = int()).maxItems(-1) }
        }
        assertInvalid("Broken", "maxLength must not be negative") {
            class_("Broken") { string("text").maxLength(-1) }
        }
        class_("Symbolic") {
            list("items", of = int()).maxItems(SchemaIntValue.MaxInlineWasmBytes)
            string("text").maxLength(SchemaIntValue.MaxInlineWasmBase64Length)
        }
    }

    @Test
    fun `ranges reject inversion and enforce unsigned byte and signed domains`() {
        assertInvalid("Broken", "minimum 2 exceeds maximum 1") {
            class_("Broken") { int("value").minimum(2).maximum(1) }
        }
        assertInvalid("Broken", "item minimum 2 exceeds item maximum 1") {
            class_("Broken") { list("values", of = int()).itemsMinimum(2).itemsMaximum(1) }
        }
        assertInvalid("Broken", "nonnegative for UInt32") {
            ClassSpec("Broken", listOf(FieldSpec("value", uint(), minimum = -1)))
        }
        assertInvalid("Broken", "0..255 for Byte") {
            ClassSpec("Broken", listOf(FieldSpec("value", byte(), maximum = 256)))
        }
        class_("Signed") {
            int("integer").minimum(-1).maximum(1)
            long("longValue").minimum(-1).maximum(1)
            double("decimal").minimum(-1).maximum(1)
            list("integers", of = int()).itemsMinimum(-1).itemsMaximum(1)
        }
    }

    @Test
    fun `scalar defaults obey type domain bounds and JSON Schema string length`() {
        assertInvalid("Broken", "below minimum") {
            class_("Broken") { int("value", default = 1).minimum(2) }
        }
        assertInvalid("Broken", "exceeds maximum") {
            class_("Broken") { long("value", default = 3).maximum(2) }
        }
        assertInvalid("Broken", "Float64 default must be finite") {
            class_("Broken") { double("value", default = Double.NaN) }
        }
        assertInvalid("Broken", "default exceeds maxLength") {
            class_("Broken") { string("value", default = "😀").maxLength(0) }
        }
        assertInvalid("Broken", "nonnegative for UInt32") {
            ClassSpec("Broken", listOf(FieldSpec("value", uint(), default = -1)))
        }
        assertInvalid("Broken", "0..255 for Byte") {
            ClassSpec("Broken", listOf(FieldSpec("value", byte(), default = 256)))
        }
        class_("ValidDefaults") {
            int("integer", default = -1).minimum(-1)
            long("longValue", default = -1).minimum(-1)
            double("decimal", default = -1.0).minimum(-1)
            boolean("flag", default = true)
            string("emoji", default = "😀").maxLength(1)
        }
    }

    private fun assertInvalidNested(useNested: (ClassSpec) -> Unit) {
        var outerReached = false
        assertInvalid("Nested", "shared", "first", "second") {
            val nested = ClassSpec("Nested", listOf(
                FieldSpec("first", int(), serialName = "shared"),
                FieldSpec("second", int(), serialName = "shared"),
            ))
            outerReached = true
            useNested(nested)
        }
        assertFalse(outerReached, "An invalid nested ClassSpec must fail before the outer schema is built.")
    }

    private fun assertInvalid(className: String, vararg expected: String, block: () -> Unit) {
        val error = assertFailsWith<IllegalArgumentException>(block = block)
        assertTrue(error.message.orEmpty().contains("Class '$className'"), error.message)
        expected.forEach { assertTrue(error.message.orEmpty().contains(it), error.message) }
    }
}
