package org.jetbrains.hexana.protobuf

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parses a binary `FileDescriptorSet` (protobuf serialization of
 * `google.protobuf.FileDescriptorSet`, typically a `.desc` or `.protoset` file).
 */
object FileDescriptorSetParser {

    /**
     * Heuristic detection: returns `true` when [buf] begins with a valid-looking
     * `FileDescriptorSet` whose first `FileDescriptorProto` carries a `.proto`
     * file name.
     *
     * The cursor is always restored to its original position.
     */
    fun isFileDescriptorSet(buf: CommonByteBuffer): Boolean {
        val savedPos = buf.position()
        return try {
            if (buf.remaining() < 6) return false
            buf.position(0)
            // FileDescriptorSet.file is field 1, wiretype LEN => tag byte 0x0A.
            if ((buf.get().toInt() and 0xFF) != 0x0A) return false
            val fdsLen = buf.getULEB128()
            if (fdsLen <= 0 || fdsLen > buf.remaining()) return false
            // First field inside FileDescriptorProto: name (field 1, wiretype LEN) => 0x0A.
            if ((buf.get().toInt() and 0xFF) != 0x0A) return false
            val nameLen = buf.getULEB128()
            if (nameLen <= 6 || nameLen > buf.remaining()) return false
            val nameBytes = ByteArray(nameLen.toInt())
            buf.get(nameBytes, 0, nameLen.toInt())
            nameBytes.decodeToString().endsWith(".proto")
        } catch (_: Exception) {
            false
        } finally {
            buf.position(savedPos)
        }
    }

    /**
     * Parses the entire `FileDescriptorSet` from [buf].
     *
     * @throws IllegalArgumentException on malformed data.
     */
    fun parse(buf: CommonByteBuffer): ProtoDescriptorSet {
        val files = mutableListOf<ProtoFile>()
        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            if (fn == 1 && wt == WIRETYPE_LEN) {
                val slice = buf.readLenSlice(depth = 0)
                files.add(parseFileDescriptorProto(slice, depth = 1))
            } else {
                buf.skipField(wt)
            }
        }
        return ProtoDescriptorSet(files)
    }

    // -------------------------------------------------------------------------
    // FileDescriptorProto  (field numbers per descriptor.proto)
    // -------------------------------------------------------------------------

    private fun parseFileDescriptorProto(buf: CommonByteBuffer, depth: Int): ProtoFile {
        var name = ""
        var pkg = ""
        var syntax = ""
        val dependencies = mutableListOf<String>()
        val allMessages = mutableListOf<ProtoMessage>()
        val allEnums = mutableListOf<ProtoEnum>()
        val services = mutableListOf<ProtoService>()

        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            when {
                fn == 1 && wt == WIRETYPE_LEN -> name = buf.readProtoString()         // name
                fn == 2 && wt == WIRETYPE_LEN -> pkg = buf.readProtoString()           // package
                fn == 3 && wt == WIRETYPE_LEN -> dependencies.add(buf.readProtoString()) // dependency
                fn == 4 && wt == WIRETYPE_LEN -> {                                     // message_type
                    val sub = buf.readLenSlice(depth)
                    val msg = parseDescriptorProto(sub, parentPath = "", depth = depth + 1)
                    collectMessages(msg, allMessages)
                    collectEnums(msg, allEnums)
                }
                fn == 5 && wt == WIRETYPE_LEN -> {                                     // enum_type
                    val sub = buf.readLenSlice(depth)
                    allEnums.add(parseEnumDescriptorProto(sub, parentPath = ""))
                }
                fn == 6 && wt == WIRETYPE_LEN -> {                                     // service
                    val sub = buf.readLenSlice(depth)
                    services.add(parseServiceDescriptorProto(sub))
                }
                fn == 12 && wt == WIRETYPE_LEN -> syntax = buf.readProtoString()       // syntax
                else -> buf.skipField(wt)
            }
        }

        if (syntax.isEmpty()) syntax = "proto2"
        return ProtoFile(name, pkg, syntax, dependencies, allMessages, allEnums, services)
    }

    // -------------------------------------------------------------------------
    // DescriptorProto (message)
    // -------------------------------------------------------------------------

    private fun parseDescriptorProto(buf: CommonByteBuffer, parentPath: String, depth: Int): ProtoMessage {
        var name = ""
        val fields = mutableListOf<ProtoField>()
        val nestedMessages = mutableListOf<ProtoMessage>()
        val nestedEnums = mutableListOf<ProtoEnum>()

        // Collect raw data first so we have the name before resolving qualifiedName for children.
        // We need two passes conceptually, but name comes early (field 1) so we parse linearly;
        // children that arrive before name will be re-patched after the loop.
        val deferredMessages = mutableListOf<CommonByteBuffer>()
        val deferredEnums = mutableListOf<CommonByteBuffer>()

        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            when {
                fn == 1 && wt == WIRETYPE_LEN -> name = buf.readProtoString()          // name
                fn == 2 && wt == WIRETYPE_LEN -> {                                     // field
                    val sub = buf.readLenSlice(depth)
                    fields.add(parseFieldDescriptorProto(sub))
                }
                fn == 3 && wt == WIRETYPE_LEN -> deferredMessages.add(buf.readLenSlice(depth)) // nested_type
                fn == 4 && wt == WIRETYPE_LEN -> deferredEnums.add(buf.readLenSlice(depth))    // enum_type
                else -> buf.skipField(wt)
            }
        }

        val qualifiedName = if (parentPath.isEmpty()) name else "$parentPath.$name"

        for (sub in deferredMessages) {
            nestedMessages.add(parseDescriptorProto(sub, parentPath = qualifiedName, depth = depth + 1))
        }
        for (sub in deferredEnums) {
            nestedEnums.add(parseEnumDescriptorProto(sub, parentPath = qualifiedName))
        }

        return ProtoMessage(name, qualifiedName, fields, nestedMessages, nestedEnums)
    }

    // -------------------------------------------------------------------------
    // FieldDescriptorProto
    // -------------------------------------------------------------------------

    private fun parseFieldDescriptorProto(buf: CommonByteBuffer): ProtoField {
        var name = ""
        var number = 0
        var labelVal = 0
        var typeVal = 0
        var typeName = ""

        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            when {
                fn == 1 && wt == WIRETYPE_LEN -> name = buf.readProtoString()         // name
                fn == 3 && wt == WIRETYPE_VARINT -> number = buf.getULEB128().toInt() // number
                fn == 4 && wt == WIRETYPE_VARINT -> labelVal = buf.getULEB128().toInt() // label
                fn == 5 && wt == WIRETYPE_VARINT -> typeVal = buf.getULEB128().toInt() // type
                fn == 6 && wt == WIRETYPE_LEN -> typeName = buf.readProtoString()     // type_name
                else -> buf.skipField(wt)
            }
        }

        val label = when (labelVal) {
            1 -> "optional"
            2 -> "required"
            3 -> "repeated"
            else -> "optional"
        }
        val type = TYPE_NAMES[typeVal] ?: "unknown"
        val typeRef = stripLeadingDot(typeName)

        return ProtoField(name, number, label, type, typeRef)
    }

    // -------------------------------------------------------------------------
    // EnumDescriptorProto / EnumValueDescriptorProto
    // -------------------------------------------------------------------------

    private fun parseEnumDescriptorProto(buf: CommonByteBuffer, parentPath: String): ProtoEnum {
        var name = ""
        val values = mutableListOf<ProtoEnumValue>()

        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            when {
                fn == 1 && wt == WIRETYPE_LEN -> name = buf.readProtoString()  // name
                fn == 2 && wt == WIRETYPE_LEN -> values.add(parseEnumValueDescriptorProto(buf.readLenSlice(0))) // value
                else -> buf.skipField(wt)
            }
        }

        val qualifiedName = if (parentPath.isEmpty()) name else "$parentPath.$name"
        return ProtoEnum(name, qualifiedName, values)
    }

    private fun parseEnumValueDescriptorProto(buf: CommonByteBuffer): ProtoEnumValue {
        var name = ""
        var number = 0

        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            when {
                fn == 1 && wt == WIRETYPE_LEN -> name = buf.readProtoString()          // name
                fn == 2 && wt == WIRETYPE_VARINT -> number = buf.getULEB128().toInt()  // number
                else -> buf.skipField(wt)
            }
        }
        return ProtoEnumValue(name, number)
    }

    // -------------------------------------------------------------------------
    // ServiceDescriptorProto / MethodDescriptorProto
    // -------------------------------------------------------------------------

    private fun parseServiceDescriptorProto(buf: CommonByteBuffer): ProtoService {
        var name = ""
        val methods = mutableListOf<ProtoMethod>()

        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            when {
                fn == 1 && wt == WIRETYPE_LEN -> name = buf.readProtoString()  // name
                fn == 2 && wt == WIRETYPE_LEN -> methods.add(parseMethodDescriptorProto(buf.readLenSlice(0))) // method
                else -> buf.skipField(wt)
            }
        }
        return ProtoService(name, methods)
    }

    private fun parseMethodDescriptorProto(buf: CommonByteBuffer): ProtoMethod {
        var name = ""
        var inputType = ""
        var outputType = ""
        var clientStreaming = false
        var serverStreaming = false

        while (buf.hasRemaining()) {
            val tag = buf.getULEB128()
            val fn = fieldNumber(tag)
            val wt = wireType(tag)
            when {
                fn == 1 && wt == WIRETYPE_LEN -> name = buf.readProtoString()             // name
                fn == 2 && wt == WIRETYPE_LEN -> inputType = buf.readProtoString()        // input_type
                fn == 3 && wt == WIRETYPE_LEN -> outputType = buf.readProtoString()       // output_type
                fn == 5 && wt == WIRETYPE_VARINT -> clientStreaming = buf.getULEB128() != 0L // client_streaming
                fn == 6 && wt == WIRETYPE_VARINT -> serverStreaming = buf.getULEB128() != 0L // server_streaming
                else -> buf.skipField(wt)
            }
        }

        return ProtoMethod(
            name,
            stripLeadingDot(inputType),
            stripLeadingDot(outputType),
            clientStreaming,
            serverStreaming,
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Strips a single leading `.` from a fully-qualified type name; returns [s] unchanged otherwise. */
    private fun stripLeadingDot(s: String): String = if (s.startsWith(".")) s.substring(1) else s

    /** Appends [msg] and all its transitive nested messages to [out] (depth-first). */
    private fun collectMessages(msg: ProtoMessage, out: MutableList<ProtoMessage>) {
        out.add(msg)
        for (child in msg.nested) collectMessages(child, out)
    }

    /** Appends [msg]'s own enums and all its transitive nested enums to [out]. */
    private fun collectEnums(msg: ProtoMessage, out: MutableList<ProtoEnum>) {
        out.addAll(msg.enums)
        for (child in msg.nested) collectEnums(child, out)
    }

    /** Maps `FieldDescriptorProto.Type` numeric values to human-readable names. */
    private val TYPE_NAMES: Map<Int, String> = mapOf(
        1 to "double",
        2 to "float",
        3 to "int64",
        4 to "uint64",
        5 to "int32",
        6 to "fixed64",
        7 to "fixed32",
        8 to "bool",
        9 to "string",
        10 to "group",
        11 to "message",
        12 to "bytes",
        13 to "uint32",
        14 to "enum",
        15 to "sfixed32",
        16 to "sfixed64",
        17 to "sint32",
        18 to "sint64",
    )
}
