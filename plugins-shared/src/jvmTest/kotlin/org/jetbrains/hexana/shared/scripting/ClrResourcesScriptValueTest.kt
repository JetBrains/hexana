package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.pe.clr.ClrResource
import org.jetbrains.hexana.providers.pe.clr.ClrResourcesFile
import org.jetbrains.hexana.providers.pe.clr.ClrResourcesParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClrResourcesScriptValueTest {
    private val file = assertNotNull(
        ClrResourcesParser.parse(File("../parsing/native-fixtures/test-data/pe/sample.resources").readBytes().toCommonByteBuffer()),
    )

    /** The raw .d.ts is generated from these classes' properties; the browser value must carry the same names. */
    private fun propertyNames(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    @Test
    fun `browser value has every member the raw declarations promise`() {
        val value = clrResourcesScriptValue(file)
        assertEquals(propertyNames(ClrResourcesFile::class.java), value.keys)
        @Suppress("UNCHECKED_CAST")
        val resources = value.getValue("resources") as List<Map<String, Any?>>
        assertEquals(propertyNames(ClrResource::class.java), resources.first().keys)
        val greeting = resources.single { it["name"] == "Greeting" }
        assertEquals("String", greeting["kind"])
        assertEquals("Hello from Hexana", greeting["stringValue"])
    }
}
