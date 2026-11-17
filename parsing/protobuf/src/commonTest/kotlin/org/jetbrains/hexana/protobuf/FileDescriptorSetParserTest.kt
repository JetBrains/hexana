package org.jetbrains.hexana.protobuf

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// -------------------------------------------------------------------------
// Minimal self-contained protobuf encoder for test fixtures (no protoc needed)
// -------------------------------------------------------------------------

class ProtoBuilder {
    private val buf = mutableListOf<Byte>()

    fun varintField(fn: Int, value: Long) {
        writeTag(fn, 0)
        writeVarint(value)
    }

    fun lenField(fn: Int, bytes: ByteArray) {
        writeTag(fn, 2)
        writeVarint(bytes.size.toLong())
        bytes.forEach { buf.add(it) }
    }

    /** Writes a fixed-32 field: tag (wiretype 5) followed by exactly 4 raw LE bytes. */
    fun i32Field(fn: Int, fourBytesLE: ByteArray) {
        require(fourBytesLE.size == 4) { "i32 payload must be 4 bytes" }
        writeTag(fn, 5)
        fourBytesLE.forEach { buf.add(it) }
    }

    /** Writes a fixed-64 field: tag (wiretype 1) followed by exactly 8 raw LE bytes. */
    fun i64Field(fn: Int, eightBytesLE: ByteArray) {
        require(eightBytesLE.size == 8) { "i64 payload must be 8 bytes" }
        writeTag(fn, 1)
        eightBytesLE.forEach { buf.add(it) }
    }

    private fun writeTag(fn: Int, wt: Int) = writeVarint(((fn shl 3) or wt).toLong())

    fun writeVarint(v: Long) {
        var r = v
        do {
            var b = (r and 0x7F).toInt()
            r = r ushr 7
            if (r != 0L) b = b or 0x80
            buf.add(b.toByte())
        } while (r != 0L)
    }

    fun build() = buf.toByteArray()
}

fun buildProtoMessage(block: ProtoBuilder.() -> Unit): ByteArray {
    val b = ProtoBuilder()
    b.block()
    return b.build()
}

// -------------------------------------------------------------------------
// Tests
// -------------------------------------------------------------------------

class FileDescriptorSetParserTest {

    // ---- Fixture A: simple.proto ----------------------------------------

    /**
     * Encodes:
     *   syntax = "proto3"
     *   package = "example"
     *   message Ping { string payload = 1; }
     */
    private fun buildFixtureA(): ByteArray {
        // FieldDescriptorProto for payload: name=1, number=3, label=4(1=optional), type=5(9=string)
        val fieldPayload = buildProtoMessage {
            lenField(1, "payload".encodeToByteArray())   // name
            varintField(3, 1L)                           // number = 1
            varintField(4, 1L)                           // label = LABEL_OPTIONAL
            varintField(5, 9L)                           // type = TYPE_STRING
        }

        // DescriptorProto for Ping
        val msgPing = buildProtoMessage {
            lenField(1, "Ping".encodeToByteArray())  // name
            lenField(2, fieldPayload)                // field[0]
        }

        // FileDescriptorProto
        val fileProto = buildProtoMessage {
            lenField(1, "simple.proto".encodeToByteArray())  // name
            lenField(2, "example".encodeToByteArray())       // package
            lenField(4, msgPing)                             // message_type
            lenField(12, "proto3".encodeToByteArray())       // syntax
        }

        // FileDescriptorSet
        return buildProtoMessage {
            lenField(1, fileProto)
        }
    }

    @Test
    fun fixtureA_fileMetadata() {
        val buf = buildFixtureA().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)

        assertEquals(1, fds.files.size, "Expected 1 file")
        val file = fds.files[0]
        assertEquals("simple.proto", file.name)
        assertEquals("example", file.pkg)
        assertEquals("proto3", file.syntax)
        assertEquals(0, file.dependencies.size)
        assertEquals(0, file.enums.size)
        assertEquals(0, file.services.size)
    }

    @Test
    fun fixtureA_messages() {
        val buf = buildFixtureA().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val file = fds.files[0]

        assertEquals(1, file.messages.size, "Expected 1 message")
        val ping = file.messages[0]
        assertEquals("Ping", ping.name)
        assertEquals("Ping", ping.qualifiedName)
        assertEquals(1, ping.fields.size)
    }

    @Test
    fun fixtureA_field() {
        val buf = buildFixtureA().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val field = fds.files[0].messages[0].fields[0]

        assertEquals("payload", field.name)
        assertEquals(1, field.number)
        assertEquals("optional", field.label)
        assertEquals("string", field.type)
        assertEquals("", field.typeRef)
    }

    // ---- Fixture B: rpc.proto -------------------------------------------

    /**
     * Encodes:
     *   syntax = "proto3"
     *   package = "rpc"
     *   enum Status { UNKNOWN=0; OK=1; ERROR=2; }
     *   message Request {
     *     string id = 1;
     *     message Filter { bool active = 1; }
     *     Filter filter = 2;
     *   }
     *   message Response { Status status = 1; }
     *   service Greeter { rpc Process (Request) returns (Response); }
     */
    private fun buildFixtureB(): ByteArray {
        // enum Status
        val enumUnknown = buildProtoMessage {
            lenField(1, "UNKNOWN".encodeToByteArray())
            varintField(2, 0L)
        }
        val enumOk = buildProtoMessage {
            lenField(1, "OK".encodeToByteArray())
            varintField(2, 1L)
        }
        val enumError = buildProtoMessage {
            lenField(1, "ERROR".encodeToByteArray())
            varintField(2, 2L)
        }
        val statusEnum = buildProtoMessage {
            lenField(1, "Status".encodeToByteArray())
            lenField(2, enumUnknown)
            lenField(2, enumOk)
            lenField(2, enumError)
        }

        // Request.Filter nested message: bool active = 1
        val fieldActive = buildProtoMessage {
            lenField(1, "active".encodeToByteArray())
            varintField(3, 1L)   // number
            varintField(4, 1L)   // label = OPTIONAL
            varintField(5, 8L)   // type = TYPE_BOOL
        }
        val msgFilter = buildProtoMessage {
            lenField(1, "Filter".encodeToByteArray())
            lenField(2, fieldActive)
        }

        // Request fields
        val fieldId = buildProtoMessage {
            lenField(1, "id".encodeToByteArray())
            varintField(3, 1L)
            varintField(4, 1L)   // optional
            varintField(5, 9L)   // string
        }
        val fieldFilter = buildProtoMessage {
            lenField(1, "filter".encodeToByteArray())
            varintField(3, 2L)
            varintField(4, 1L)   // optional
            varintField(5, 11L)  // message
            lenField(6, ".rpc.Request.Filter".encodeToByteArray())  // type_name
        }
        val msgRequest = buildProtoMessage {
            lenField(1, "Request".encodeToByteArray())
            lenField(2, fieldId)
            lenField(2, fieldFilter)
            lenField(3, msgFilter)  // nested_type
        }

        // Response: Status status = 1
        val fieldStatus = buildProtoMessage {
            lenField(1, "status".encodeToByteArray())
            varintField(3, 1L)
            varintField(4, 1L)   // optional
            varintField(5, 14L)  // enum
            lenField(6, ".rpc.Status".encodeToByteArray())
        }
        val msgResponse = buildProtoMessage {
            lenField(1, "Response".encodeToByteArray())
            lenField(2, fieldStatus)
        }

        // service Greeter { rpc Process(Request) returns (Response); }
        val method = buildProtoMessage {
            lenField(1, "Process".encodeToByteArray())
            lenField(2, ".rpc.Request".encodeToByteArray())
            lenField(3, ".rpc.Response".encodeToByteArray())
            // client_streaming and server_streaming absent => false
        }
        val service = buildProtoMessage {
            lenField(1, "Greeter".encodeToByteArray())
            lenField(2, method)
        }

        // FileDescriptorProto
        val fileProto = buildProtoMessage {
            lenField(1, "rpc.proto".encodeToByteArray())
            lenField(2, "rpc".encodeToByteArray())
            lenField(4, msgRequest)
            lenField(4, msgResponse)
            lenField(5, statusEnum)
            lenField(6, service)
            lenField(12, "proto3".encodeToByteArray())
        }

        return buildProtoMessage { lenField(1, fileProto) }
    }

    @Test
    fun fixtureB_flattenedMessages() {
        val buf = buildFixtureB().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val file = fds.files[0]

        // Flattened: Request, Request.Filter, Response
        val names = file.messages.map { it.qualifiedName }.toSet()
        assertTrue("Request" in names, "Expected Request, got $names")
        assertTrue("Request.Filter" in names, "Expected Request.Filter, got $names")
        assertTrue("Response" in names, "Expected Response, got $names")
        assertEquals(3, file.messages.size, "Expected 3 flattened messages, got ${file.messages.size}")
    }

    @Test
    fun fixtureB_requestFields() {
        val buf = buildFixtureB().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val request = fds.files[0].messages.first { it.qualifiedName == "Request" }

        assertEquals(2, request.fields.size)
        val idField = request.fields.first { it.name == "id" }
        assertEquals("string", idField.type)
        assertEquals("", idField.typeRef)

        val filterField = request.fields.first { it.name == "filter" }
        assertEquals("message", filterField.type)
        assertEquals("rpc.Request.Filter", filterField.typeRef)
    }

    @Test
    fun fixtureB_nestedListOnRequest() {
        val buf = buildFixtureB().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val request = fds.files[0].messages.first { it.qualifiedName == "Request" }
        // The .nested list should contain Filter directly
        assertEquals(1, request.nested.size)
        assertEquals("Request.Filter", request.nested[0].qualifiedName)
    }

    @Test
    fun fixtureB_filterField() {
        val buf = buildFixtureB().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val filter = fds.files[0].messages.first { it.qualifiedName == "Request.Filter" }

        assertEquals(1, filter.fields.size)
        val active = filter.fields[0]
        assertEquals("active", active.name)
        assertEquals("bool", active.type)
        assertEquals("", active.typeRef)
    }

    @Test
    fun fixtureB_responseField() {
        val buf = buildFixtureB().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val response = fds.files[0].messages.first { it.qualifiedName == "Response" }

        assertEquals(1, response.fields.size)
        val status = response.fields[0]
        assertEquals("status", status.name)
        assertEquals("enum", status.type)
        assertEquals("rpc.Status", status.typeRef)
    }

    @Test
    fun fixtureB_enum() {
        val buf = buildFixtureB().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val file = fds.files[0]

        // Top-level Status enum
        val status = file.enums.first { it.qualifiedName == "Status" }
        assertEquals(3, status.values.size)
        assertEquals("UNKNOWN", status.values[0].name)
        assertEquals(0, status.values[0].number)
        assertEquals("OK", status.values[1].name)
        assertEquals(1, status.values[1].number)
        assertEquals("ERROR", status.values[2].name)
        assertEquals(2, status.values[2].number)
    }

    @Test
    fun fixtureB_service() {
        val buf = buildFixtureB().toCommonByteBuffer()
        val fds = FileDescriptorSetParser.parse(buf)
        val file = fds.files[0]

        assertEquals(1, file.services.size)
        val greeter = file.services[0]
        assertEquals("Greeter", greeter.name)
        assertEquals(1, greeter.methods.size)

        val process = greeter.methods[0]
        assertEquals("Process", process.name)
        assertEquals("rpc.Request", process.inputType)
        assertEquals("rpc.Response", process.outputType)
        assertFalse(process.clientStreaming)
        assertFalse(process.serverStreaming)
    }

    // ---- Detection tests ------------------------------------------------

    @Test
    fun detection_fixtureAIsFileDescriptorSet() {
        val buf = buildFixtureA().toCommonByteBuffer()
        assertTrue(FileDescriptorSetParser.isFileDescriptorSet(buf))
        // Cursor must be restored
        assertEquals(0, buf.position())
    }

    @Test
    fun detection_elfMagicIsNotFileDescriptorSet() {
        // ELF magic bytes
        val elf = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00)
        val buf = elf.toCommonByteBuffer()
        assertFalse(FileDescriptorSetParser.isFileDescriptorSet(buf))
    }

    @Test
    fun detection_emptyIsNotFileDescriptorSet() {
        val buf = ByteArray(0).toCommonByteBuffer()
        assertFalse(FileDescriptorSetParser.isFileDescriptorSet(buf))
    }

    // ---- G1: detection false-positive (name without .proto suffix) ------

    @Test
    fun g1_nonProtoNameIsNotFileDescriptorSet() {
        // Well-formed FileDescriptorSet wrapper, but inner file name is "hello_world".
        val fileProto = buildProtoMessage {
            lenField(1, "hello_world".encodeToByteArray())  // name (no .proto suffix)
            lenField(2, "example".encodeToByteArray())       // package
        }
        val bytes = buildProtoMessage { lenField(1, fileProto) }
        assertFalse(FileDescriptorSetParser.isFileDescriptorSet(bytes.toCommonByteBuffer()))
    }

    // ---- G2: hostile / truncated input ----------------------------------

    @Test
    fun g2a_lengthBeyondRemainingThrowsAndIsNotDetected() {
        // outer tag 0x0A (field 1, LEN), length varint = 100, then only a few garbage bytes.
        val bytes = buildProtoMessage {
            // writeTag(1,2) is 0x0A, then a large length, then leave garbage.
            writeVarint(0x0AL)   // tag: field 1, wiretype LEN
            writeVarint(100L)    // claimed length far exceeds remaining
            writeVarint(0x01L)   // garbage byte(s)
            writeVarint(0x02L)
        }
        assertFailsWith<IllegalArgumentException> {
            FileDescriptorSetParser.parse(bytes.toCommonByteBuffer())
        }
        assertFalse(FileDescriptorSetParser.isFileDescriptorSet(bytes.toCommonByteBuffer()))
    }

    @Test
    fun g2b_truncatedDescriptorThrowsCleanly() {
        // Build a valid fixture, then cut off the inner FileDescriptorProto bytes while
        // keeping the outer length header pointing past the end.
        val fileProto = buildProtoMessage {
            lenField(1, "simple.proto".encodeToByteArray())
            lenField(2, "example".encodeToByteArray())
            lenField(12, "proto3".encodeToByteArray())
        }
        val full = buildProtoMessage { lenField(1, fileProto) }
        // Drop the last third of the buffer so the inner message is truncated.
        val truncated = full.copyOf(full.size - full.size / 3)
        assertFailsWith<IllegalArgumentException> {
            FileDescriptorSetParser.parse(truncated.toCommonByteBuffer())
        }
    }

    // ---- G3: recursion depth cap ----------------------------------------

    /**
     * Builds a DescriptorProto chained `levels` deep via nested_type (field 3).
     * The innermost message has name "M<levels>"; each outer wraps it as nested_type.
     */
    private fun buildNestedMessage(levels: Int): ByteArray {
        var current = buildProtoMessage { lenField(1, "M$levels".encodeToByteArray()) }
        for (i in levels - 1 downTo 1) {
            val child = current
            current = buildProtoMessage {
                lenField(1, "M$i".encodeToByteArray())
                lenField(3, child)  // nested_type
            }
        }
        return current
    }

    private fun buildNestedFds(levels: Int): ByteArray {
        val fileProto = buildProtoMessage {
            lenField(1, "deep.proto".encodeToByteArray())
            lenField(4, buildNestedMessage(levels))  // message_type
        }
        return buildProtoMessage { lenField(1, fileProto) }
    }

    @Test
    fun g3_deepNestingThrows() {
        // 20 levels comfortably exceeds the depth-16 cap.
        val bytes = buildNestedFds(20)
        val ex = assertFailsWith<IllegalArgumentException> {
            FileDescriptorSetParser.parse(bytes.toCommonByteBuffer())
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("depth", ignoreCase = true) || msg.contains("nesting", ignoreCase = true),
            "Expected depth/nesting message, got: $msg",
        )
    }

    @Test
    fun g3_shallowNestingParsesOk() {
        // A modest chain well within the cap must parse without throwing.
        val bytes = buildNestedFds(10)
        val fds = FileDescriptorSetParser.parse(bytes.toCommonByteBuffer())
        assertTrue(fds.files[0].messages.isNotEmpty())
    }

    // ---- G5: proto2 default when syntax field absent --------------------

    @Test
    fun g5_absentSyntaxDefaultsToProto2() {
        val fileProto = buildProtoMessage {
            lenField(1, "legacy.proto".encodeToByteArray())
            lenField(2, "legacy".encodeToByteArray())
            // field 12 (syntax) intentionally omitted
        }
        val bytes = buildProtoMessage { lenField(1, fileProto) }
        val fds = FileDescriptorSetParser.parse(bytes.toCommonByteBuffer())
        assertEquals("proto2", fds.files[0].syntax)
    }

    // ---- G6: repeated field with multi-byte tag (field number 16) -------

    @Test
    fun g6_repeatedFieldWithMultiByteTag() {
        // field number 16 => tag = (16 shl 3) | 2 = 130 => varint 0x82 0x01 (2 bytes).
        val fieldItems = buildProtoMessage {
            lenField(1, "items".encodeToByteArray())  // name
            varintField(3, 16L)                        // number = 16
            varintField(4, 3L)                         // label = LABEL_REPEATED
            varintField(5, 9L)                         // type = TYPE_STRING
        }
        val msg = buildProtoMessage {
            lenField(1, "Bag".encodeToByteArray())
            lenField(2, fieldItems)
        }
        val fileProto = buildProtoMessage {
            lenField(1, "bag.proto".encodeToByteArray())
            lenField(4, msg)
            lenField(12, "proto3".encodeToByteArray())
        }
        val bytes = buildProtoMessage { lenField(1, fileProto) }
        val fds = FileDescriptorSetParser.parse(bytes.toCommonByteBuffer())
        val field = fds.files[0].messages[0].fields[0]
        assertEquals(16, field.number)
        assertEquals("repeated", field.label)
        assertEquals("string", field.type)
    }
}
