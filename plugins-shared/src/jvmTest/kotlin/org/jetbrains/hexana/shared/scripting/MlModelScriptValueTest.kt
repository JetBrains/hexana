package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mlmodel.ModelByteRange
import org.jetbrains.hexana.mlmodel.ModelDiagnostic
import org.jetbrains.hexana.mlmodel.gguf.GgufFile
import org.jetbrains.hexana.mlmodel.gguf.GgufMetadataEntry
import org.jetbrains.hexana.mlmodel.gguf.GgufParser
import org.jetbrains.hexana.mlmodel.gguf.GgufTensorInfo
import org.jetbrains.hexana.mlmodel.gguf.GgufValue
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsFile
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsMetadataEntry
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsParser
import org.jetbrains.hexana.mlmodel.safetensors.SafetensorsTensor
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The browser hosts expose these maps where the JVM host exposes the Kotlin objects, and the
 * raw `.d.ts` is generated from the objects' public properties. So every map must carry exactly
 * those property names.
 */
class MlModelScriptValueTest {
    private fun bytes(name: String) = File("../parsing/mlmodel/test-data/$name").readBytes()

    /** Public, argument-less `getX` / `isX` accessors: the properties scripts and the `.d.ts` see. */
    private fun propertyNames(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && !it.isSynthetic }
        .mapNotNull { method ->
            when {
                method.name.startsWith("get") && method.name.length > 3 -> method.name.substring(3)
                else -> null
            }
        }
        .map { it.replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap() = this as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asList() = this as List<Any?>

    @Test
    fun `GGUF value has every member the raw declarations promise`() {
        val file = assertNotNull(GgufParser.parse(bytes("tiny.gguf").toCommonByteBuffer()))
        val value = ggufScriptValue(file)
        assertEquals(propertyNames(GgufFile::class.java), value.keys)
        val entry = value.getValue("metadata").asList().first().asMap()
        assertEquals(propertyNames(GgufMetadataEntry::class.java), entry.keys)
        assertEquals(propertyNames(GgufValue::class.java), entry.getValue("value").asMap().keys)
        assertEquals(propertyNames(GgufTensorInfo::class.java), value.getValue("tensors").asList().first().asMap().keys)
        assertEquals(propertyNames(ModelByteRange::class.java), entry.getValue("range").asMap().keys)

        val u64 = value.getValue("metadata").asList().map { it.asMap() }.single { it["key"] == "test.u64" }
        assertEquals(9.223372036854775813E18, u64.getValue("value").asMap()["integer"])
        assertEquals(4544.0, value["parameterCount"])
    }

    @Test
    fun `safetensors value has every member the raw declarations promise`() {
        val file = assertNotNull(SafetensorsParser.parse(bytes("tiny.safetensors").toCommonByteBuffer()))
        val value = safetensorsScriptValue(file)
        assertEquals(propertyNames(SafetensorsFile::class.java), value.keys)
        assertEquals(propertyNames(SafetensorsTensor::class.java), value.getValue("tensors").asList().first().asMap().keys)
        assertEquals(propertyNames(SafetensorsMetadataEntry::class.java), value.getValue("metadata").asList().first().asMap().keys)
        assertEquals(propertyNames(ModelDiagnostic::class.java), setOf("code", "message", "range"))
    }
}
