package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/** `binary.raw` members a script sees in the VS Code / web host, against what the raw .d.ts declares. */
class ScriptRawMembersTest {
    /** One string resource, `Greeting` = `Hi`, as `ResourceWriter` lays it out. */
    private fun minimalResources(): ByteArray {
        val out = ArrayList<Byte>()
        fun u32(value: Int) = repeat(4) { out += (value ushr (8 * it)).toByte() }
        fun string(value: String) = value.encodeToByteArray().let { out += it.size.toByte(); out += it.toList() }
        u32(0xBEEFCACE.toInt()); u32(1)
        u32(2); string(""); string("")
        u32(2); u32(1); u32(0)
        while (out.size % 8 != 0) out += 'P'.code.toByte()
        u32(0); u32(0)
        u32(out.size + 4 + 1 + 16 + 4)
        out += 16.toByte(); "Greeting".forEach { out += it.code.toByte(); out += 0 }; u32(0)
        out += 1; string("Hi")
        return out.toByteArray()
    }

    @Test
    fun resourceSetsExposeTheirParsedResourcesToScripts(): kotlin.js.Promise<Unit> {
        val provider = org.jetbrains.hexana.providers.createBinaryProvider(minimalResources().toCommonByteBuffer(), "Strings.resources")
        val lines = mutableListOf<String>()
        return executeScriptAsync(
            scriptContent = "print(binary.raw.parsedResources.resources[0].name + '=' + binary.raw.parsedResources.resources[0].value);",
            provider = provider,
            fileName = "Strings.resources",
            filePath = null,
            readBinaryFile = null,
            onLine = { lines += it },
        ).then { error ->
            assertEquals(null, error)
            assertEquals(listOf("Greeting=Hi"), lines)
        }
    }
}
