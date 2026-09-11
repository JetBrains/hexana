package org.jetbrains.hexana.mcp.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemaPlanTest {
    @Test
    fun `plan snapshots canonical field meaning and reuses nested class identity`() {
        val nested = class_("Nested") {
            int("count", default = 7).serialName("total")
            nullable("unset", string())
        }
        val root = class_("Root") {
            embed("first", nested)
            list("more", nested).maxItems(SchemaIntValue.MaxInlineWasmBytes)
        }

        val plan = compileSchemaPlan(root)
        val first = plan.root.fields.first()
        val more = plan.root.fields.last()
        val firstNested = (first.type as SchemaTypePlan.ClassRef).clazz
        val moreNested = ((more.type as SchemaTypePlan.ListOf).element as SchemaTypePlan.ClassRef).clazz

        assertEquals("Root.first", first.identity.value)
        assertSame(plan, compileSchemaPlan(root))
        assertEquals("first", first.wireName)
        assertEquals(SchemaClassIdentity("Nested"), firstNested.identity)
        assertSame(firstNested, moreNested)
        assertEquals(FieldDefaultValue.IntValue(7), firstNested.fields.first().defaultValue)
        assertEquals(FieldPresence.Optional, firstNested.fields.last().presence)
        assertEquals(SchemaIntValue.MaxInlineWasmBytes, more.constraints.maxItems)
        assertFailsWith<UnsupportedOperationException> {
            (plan.root.fields as MutableList<SchemaFieldPlan>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (plan.classes as MutableList<SchemaClassPlan>).clear()
        }
    }

    @Test
    fun `eager validation aggregates independent diagnostics at completed class paths`() {
        val error = assertFailsWith<SchemaPlanValidationException> {
            ClassSpec("Nested", listOf(
                FieldSpec("badRange", uint(), minimum = -1, maximum = -2),
                FieldSpec("badLength", int(), maxLength = SchemaIntValue.Literal(-1)),
            ))
        }

        assertEquals(
            listOf("Nested.badRange", "Nested.badRange", "Nested.badRange", "Nested.badLength", "Nested.badLength"),
            error.diagnostics.map { it.path.toString() },
        )
        assertTrue(error.message.orEmpty().contains("Nested.badRange: minimum -1 exceeds maximum -2."))
        assertTrue(error.message.orEmpty().contains("Nested.badRange: minimum must be nonnegative for UInt32"))
        assertTrue(error.message.orEmpty().contains("Nested.badLength: maxLength requires a string-like field type"))
        assertTrue(error.message.orEmpty().contains("Nested.badLength: maxLength must not be negative"))
    }

    @Test
    fun `plan preserves explicit null defaults without treating them as absent`() {
        val plan = compileSchemaPlan(ClassSpec("NullableDefaults", listOf(
            FieldSpec(
                name = "value",
                type = string(),
                nullable = true,
                presence = FieldPresence.Defaulted(FieldDefaultValue.Null),
            ),
        )))

        assertEquals(FieldDefaultValue.Null, plan.root.fields.single().defaultValue)
        assertEquals(FieldPresence.Defaulted(FieldDefaultValue.Null), plan.root.fields.single().presence)
    }

    @Test
    fun `identities use ordinals for duplicate raw names and colliding display names`() {
        val firstNested = class_("Nested") { int("value") }
        val secondNested = class_("Nested") { int("value") }
        val namedLikeAnOrdinal = class_("Nested#2") { int("value") }
        val plan = compileSchemaPlan(ClassSpec("Root", listOf(
            FieldSpec("same", int(), serialName = "firstWire"),
            FieldSpec("same", int(), serialName = "secondWire"),
            FieldSpec("first", FieldType.ClassRef(firstNested)),
            FieldSpec("again", FieldType.ClassRef(firstNested)),
            FieldSpec("second", FieldType.ClassRef(secondNested)),
            FieldSpec("named", FieldType.ClassRef(namedLikeAnOrdinal)),
        )))

        val fields = plan.root.fields
        val first = (fields[2].type as SchemaTypePlan.ClassRef).clazz
        val again = (fields[3].type as SchemaTypePlan.ClassRef).clazz
        val second = (fields[4].type as SchemaTypePlan.ClassRef).clazz
        val named = (fields[5].type as SchemaTypePlan.ClassRef).clazz

        assertNotEquals(fields[0].identity, fields[1].identity)
        assertSame(first, again)
        assertNotEquals(first.identity, second.identity)
        assertNotEquals(second.identity, named.identity)
        assertEquals(SchemaClassIdentity("Nested", 2), second.identity)
        assertEquals(SchemaClassIdentity("Nested#2", 1), named.identity)
    }
}
