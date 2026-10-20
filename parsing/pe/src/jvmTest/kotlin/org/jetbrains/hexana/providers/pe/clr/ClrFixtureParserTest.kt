package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import org.jetbrains.hexana.providers.pe.PeFile
import org.jetbrains.hexana.providers.pe.PeImageFile
import org.jetbrains.hexana.providers.pe.PeParser
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expectations cross-checked against `monodis` output for
 * `parsing/native-fixtures/test-data/pe/sample-clr.exe` (built from `src/clr/Sample.cs`).
 */
class ClrFixtureParserTest {
    private val bytes = Path.of(System.getProperty("user.dir"))
        .resolve("../native-fixtures/test-data/pe/sample-clr.exe")
        .normalize()
        .readBytes()

    private val parsed: Pair<PeImageFile, List<*>> = run {
        val result = assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(bytes.toCommonByteBuffer()))
        assertIs<PeImageFile>(result.value) to result.diagnostics
    }
    private val file = parsed.first
    private val clr = assertNotNull(file.clr)

    @Test
    fun `parses CLI header and metadata root`() {
        assertEquals(emptyList<Any?>(), parsed.second)
        assertEquals(2, clr.header.majorRuntimeVersion)
        assertEquals(5, clr.header.minorRuntimeVersion)
        assertTrue(clr.header.isIlOnly)
        assertEquals("v4.0.30319", clr.root?.version)
        assertEquals(listOf("#~", "#Strings", "#US", "#GUID", "#Blob"), clr.streams.map { it.name })
        assertTrue(clr.streams.all { it.contentRange != null })
        assertEquals("#~", clr.tablesHeader?.streamName)
        assertEquals("Hexana.Sample.Program::Main", clr.entryPoint)
        assertEquals(".NETFramework,Version=v4.8", clr.targetFramework)
        assertEquals(false, clr.readyToRun)
    }

    @Test
    fun `decodes module and assembly identity`() {
        assertEquals("sample-clr.exe", clr.module?.name)
        assertNotNull(clr.module?.mvid)
        val assembly = assertNotNull(clr.assembly)
        assertEquals("sample-clr", assembly.name)
        assertEquals(ClrVersion(1, 2, 3, 4), assembly.version)
        assertEquals(0x8004u, assembly.hashAlgorithm)
        assertEquals(0, assembly.publicKey.size)

        val mscorlib = clr.assemblyRefs.single()
        assertEquals("mscorlib", mscorlib.name)
        assertEquals(ClrVersion(4, 0, 0, 0), mscorlib.version)
        assertEquals("B77A5C561934E089", mscorlib.publicKeyOrToken.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun `decodes type definitions with nesting, kinds and generics`() {
        assertEquals(
            listOf(
                "<Module>",
                "Hexana.Sample.IGreeter",
                "Hexana.Sample.Greeter",
                "Hexana.Sample.Box`1",
                "Hexana.Sample.Color",
                "Hexana.Sample.Program",
                "Hexana.Sample.Greeter+Nested",
                "Hexana.Sample.Program+<>c",
            ),
            clr.typeDefs.map { it.fullName },
        )
        assertEquals(
            listOf(ClrTypeKind.Module, ClrTypeKind.Interface, ClrTypeKind.Class, ClrTypeKind.Class, ClrTypeKind.Enum, ClrTypeKind.Class, ClrTypeKind.Class, ClrTypeKind.Class),
            clr.typeDefs.map { it.kind },
        )
        val nested = clr.typeDefs[6]
        assertEquals(3, nested.enclosingTypeRow)
        assertEquals(listOf("T"), clr.typeDefs[3].genericParameters)
        assertEquals("System.Enum", clr.typeDefs[4].extends)
        assertNull(clr.typeDefs[1].extends)
    }

    @Test
    fun `assigns members to declaring types and decodes signatures`() {
        assertEquals(14, clr.methods.size)
        assertEquals(listOf(2, 3, 3, 3, 3, 3, 4, 4, 6, 6, 7, 8, 8, 8), clr.methods.map { it.declaringTypeRow })
        assertEquals("string Greet(string name)", clr.methods[0].signature)
        assertEquals("void .ctor(string prefix)", clr.methods[1].signature)
        assertEquals("void add_Greeted(EventHandler value)", clr.methods[4].signature)
        assertEquals("U Map<U>(Func<T, U> map)", clr.methods[6].signature)
        assertEquals("int Main(string[] args)", clr.methods[8].signature)

        // Abstract interface methods have no body; every other method here has IL.
        assertNull(clr.methods[0].body)
        assertTrue(clr.methods.drop(1).all { it.body != null && it.body!!.codeSize > 0 })

        val greeterFields = clr.fieldsByType.getValue(3).map { "${it.type} ${it.name}" }
        assertEquals(listOf("string _prefix", "int Counter", "EventHandler Greeted"), greeterFields)
        assertEquals(listOf("T Value"), clr.fieldsByType.getValue(4).map { "${it.type} ${it.name}" })
        assertEquals(listOf("int value__", "Color Red", "Color Green", "Color Blue"), clr.fieldsByType.getValue(5).map { "${it.type} ${it.name}" })
    }

    @Test
    fun `decodes references, resources and user strings`() {
        val typeRefs = clr.typeRefs.map { "${it.scope}:${it.fullName}" }
        assertTrue("mscorlib:System.Runtime.Versioning.TargetFrameworkAttribute" in typeRefs)
        assertTrue("mscorlib:System.Diagnostics.DebuggableAttribute+DebuggingModes" in typeRefs, typeRefs.toString())

        assertEquals(22, clr.memberRefs.size)
        val concat = clr.memberRefs.single { it.name == "Concat" }
        assertEquals("System.String", concat.parent)
        assertEquals("string Concat(string, string, string, string)", concat.signature)

        assertEquals(listOf("Hexana.Sample.hello.txt", "Hexana.Sample.Strings.resources"), clr.manifestResources.map { it.name })
        val resource = clr.manifestResources.first()
        assertEquals("Hexana.Sample.hello.txt", resource.name)
        assertEquals("embedded", resource.implementation)
        assertTrue(resource.isPublic)
        val data = assertNotNull(resource.dataRange)
        assertEquals("Hello from Hexana\n", bytes.decodeToString(data.offset, data.offset + data.size))

        val userStrings = clr.userStrings.map { it.value }
        assertTrue(userStrings.containsAll(listOf("Hello", ", ", "!", "a", "b")), userStrings.toString())
    }

    @Test
    fun `reports table layout consistent with the tables stream`() {
        val tablesStream = assertNotNull(clr.streams.single { it.name == "#~" }.contentRange)
        val laidOut = clr.tables.filter { it.rowCount > 0 }
        assertTrue(laidOut.all { it.range != null })
        val end = laidOut.maxOf { it.range!!.offset + it.range!!.size }
        assertTrue(end <= tablesStream.offset + tablesStream.size)
        assertEquals(14, clr.tables.single { it.name == "MethodDef" }.rowCount)
        assertEquals(8, clr.tables.single { it.name == "TypeDef" }.rowCount)
    }
}

class ClrMalformedInputTest {
    private val sample = Path.of(System.getProperty("user.dir"))
        .resolve("../native-fixtures/test-data/pe/sample-clr.exe")
        .normalize()
        .readBytes()

    private fun parse(bytes: ByteArray): ClrMetadata =
        assertNotNull(assertIs<PeImageFile>(assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(bytes.toCommonByteBuffer())).value).clr)

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    @Test
    fun `methods whose ImplFlags are not IL get no IL body`() {
        val original = parse(sample)
        val main = original.methods.single { it.name == "Main" }
        assertNotNull(main.body)
        // MethodDef row: RVA (4 bytes), then ImplFlags; code type 1 is native (C++/CLI), whose RVA is machine code.
        val patched = sample.copyOf().apply { putU16(main.recordRange.offset + 4, 0x0001) }
        val clr = parse(patched)
        assertNull(clr.methods.single { it.name == "Main" }.body)
        assertEquals(original.methods.count { it.body != null } - 1, clr.methods.count { it.body != null })
    }

    @Test
    fun `unordered member lists cannot take members from earlier types`() {
        val original = parse(sample)
        val color = original.typeDefs.single { it.name == "Color" }
        // TypeDef row with 2-byte indexes: Flags (4), Name (2), Namespace (2), Extends (2), FieldList (2), MethodList (2).
        assertEquals(14, original.tables.single { it.name == "TypeDef" }.rowSize)
        val patched = sample.copyOf().apply { putU16(color.recordRange.offset + 10, 1) }
        val clr = parse(patched)
        fun fieldsOf(name: String, metadata: ClrMetadata) =
            metadata.fieldsByType[metadata.typeDefs.single { it.name == name }.row].orEmpty().map { it.name }
        // Color now claims to start at field 1. Its run is clamped to what no earlier type owns, so
        // Greeter keeps its fields. Box's run ends where Color's list declares to start (II.22.37),
        // which is now before Box's own start, so Box ends up with none, as the format defines.
        assertEquals(fieldsOf("Greeter", original), fieldsOf("Greeter", clr))
        assertTrue(fieldsOf("Greeter", original).none { it in fieldsOf("Color", clr) })
        assertEquals(emptyList(), fieldsOf("Box`1", clr))
        assertEquals(fieldsOf("Box`1", original) + fieldsOf("Color", original), fieldsOf("Color", clr))
        assertTrue(clr.diagnostics.any { it.code == "clr.unordered_member_lists" })
        assertTrue(original.diagnostics.none { it.code == "clr.unordered_member_lists" })
    }

    @Test
    fun `survives random corruption of ReadyToRun images`() {
        for (name in listOf("sample-r2r-win-x64", "sample-r2r", "sample-r2r-win-x86", "sample-r2r-linux-arm")) {
            val original = Path.of(System.getProperty("user.dir"))
                .resolve("../native-fixtures/test-data/pe/$name.dll").normalize().readBytes()
            val random = kotlin.random.Random(name.hashCode())
            repeat(1_500) {
                val corrupted = original.copyOf()
                repeat(1 + random.nextInt(12)) { corrupted[random.nextInt(0x200, corrupted.size)] = random.nextInt(256).toByte() }
                val buffer = corrupted.toCommonByteBuffer()
                val pe = (PeParser.parse(buffer) as? NativeParseSuccess<PeFile>)?.value as? PeImageFile ?: return@repeat
                val clr = pe.clr ?: return@repeat
                ClrReadyToRunParser.parse(buffer, clr, pe.coffHeader.machine, pe.optionalHeader, pe.sections)
            }
        }
    }

    private val bytes = Path.of(System.getProperty("user.dir"))
        .resolve("../native-fixtures/test-data/pe/sample-clr.exe")
        .normalize()
        .readBytes()

    @Test
    fun `survives truncation anywhere in the image`() {
        for (length in bytes.size downTo 0 step 7) {
            PeParser.parse(bytes.copyOf(length).toCommonByteBuffer())
        }
    }

    @Test
    fun `survives random corruption of metadata bytes`() {
        val metadata = assertNotNull(assertIs<PeImageFile>(assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(bytes.toCommonByteBuffer())).value).clr)
        val start = metadata.header.headerRange.offset
        val end = metadata.streams.maxOf { (it.contentRange?.let { range -> range.offset + range.size }) ?: 0 }
        val random = kotlin.random.Random(335)
        repeat(2_000) {
            val corrupted = bytes.copyOf()
            repeat(1 + random.nextInt(8)) {
                corrupted[random.nextInt(start, end)] = random.nextInt(256).toByte()
            }
            val buffer = corrupted.toCommonByteBuffer()
            val clr = (PeParser.parse(buffer) as? NativeParseSuccess<PeFile>)?.value?.let { it as? PeImageFile }?.clr ?: return@repeat
            // Everything the IL view does with a method: decode its body, resolve every token, read its locals.
            for (method in clr.methods) {
                val body = method.body ?: continue
                for (instruction in ClrIlDecoder.decode(buffer, body.codeRange).instructions) {
                    (instruction.operand as? ClrIlOperand.Token)?.let { clr.tokens.describe(it.token, method) }
                }
                clr.tokens.localVariableTypes(method)
            }
        }
    }
}
