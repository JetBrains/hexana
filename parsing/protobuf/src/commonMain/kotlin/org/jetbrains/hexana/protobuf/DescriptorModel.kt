package org.jetbrains.hexana.protobuf

/**
 * Root of a parsed `FileDescriptorSet` binary (`.desc` / `.protoset`).
 *
 * @property files one entry per `FileDescriptorProto` in the set.
 */
data class ProtoDescriptorSet(val files: List<ProtoFile>)

/**
 * Corresponds to a single `.proto` source file as encoded in a `FileDescriptorProto`.
 *
 * @property name the file path as declared in the proto (e.g. `"google/protobuf/any.proto"`).
 * @property pkg the `package` declaration (may be empty).
 * @property syntax `"proto2"`, `"proto3"`, or `"editions"` (defaults to `"proto2"` when absent).
 * @property dependencies paths of imported files (`import` statements).
 * @property messages top-level and flattened nested messages (all messages reachable in this file).
 * @property enums top-level and flattened nested enums.
 * @property services all services declared in this file.
 */
data class ProtoFile(
    val name: String,
    val pkg: String,
    val syntax: String,
    val dependencies: List<String>,
    val messages: List<ProtoMessage>,
    val enums: List<ProtoEnum>,
    val services: List<ProtoService>,
)

/**
 * A message type (top-level or nested).
 *
 * @property name simple name as declared in the `.proto`.
 * @property qualifiedName dot-separated path within the file (e.g. `"Outer.Inner"`).
 * @property fields field declarations in declaration order.
 * @property nested direct nested message types (one level only).
 * @property enums direct nested enum types (one level only).
 */
data class ProtoMessage(
    val name: String,
    val qualifiedName: String,
    val fields: List<ProtoField>,
    val nested: List<ProtoMessage>,
    val enums: List<ProtoEnum>,
)

/**
 * A field inside a message.
 *
 * @property name field name.
 * @property number field number.
 * @property label one of `"optional"`, `"required"`, or `"repeated"`.
 * @property type human-readable type name (e.g. `"string"`, `"int32"`, `"message"`, `"enum"`).
 * @property typeRef for `message` and `enum` fields: the fully-qualified type name with the
 *   leading `.` stripped; empty string for scalar fields.
 */
data class ProtoField(
    val name: String,
    val number: Int,
    val label: String,
    val type: String,
    val typeRef: String,
)

/**
 * An enum type.
 *
 * @property name simple name.
 * @property qualifiedName dot-separated path within the file.
 * @property values enum value declarations in declaration order.
 */
data class ProtoEnum(
    val name: String,
    val qualifiedName: String,
    val values: List<ProtoEnumValue>,
)

/**
 * A single value inside an enum.
 *
 * @property name value name.
 * @property number numeric value.
 */
data class ProtoEnumValue(val name: String, val number: Int)

/**
 * A service declaration.
 *
 * @property name service name.
 * @property methods RPC method declarations.
 */
data class ProtoService(val name: String, val methods: List<ProtoMethod>)

/**
 * An RPC method inside a service.
 *
 * @property name method name.
 * @property inputType fully-qualified request type with the leading `.` stripped.
 * @property outputType fully-qualified response type with the leading `.` stripped.
 * @property clientStreaming true if the client sends a stream of requests.
 * @property serverStreaming true if the server sends a stream of responses.
 */
data class ProtoMethod(
    val name: String,
    val inputType: String,
    val outputType: String,
    val clientStreaming: Boolean,
    val serverStreaming: Boolean,
)
