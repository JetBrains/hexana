package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryStringRecord
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode

internal class BinaryProviderProxy(
    private val provider: BinaryProvider,
) : ReadOnlyProxyObject(
    linkedMapOf(
        "bytes" to { BytesProxy(provider.bytes) },
        "strings" to { ProxyArray.fromList(provider.strings.map(::BinaryStringRecordProxy)) },
        "sizeAnalysis" to { provider.sizeAnalysis?.let(::BinarySizeNodeProxy) },
    )
)

private class BytesProxy(
    private val bytes: CommonByteBuffer,
) : ReadOnlyProxyObject(
    linkedMapOf(
        "size" to { bytes.size },
        "lastIndex" to { bytes.lastIndex },
        "indices" to { IndicesProxy(bytes.size) },
        "get" to {
            ProxyExecutable { args ->
                when (args.size) {
                    0 -> bytes.get().toUnsignedInt()
                    1 -> bytes[args[0].asInt()].toUnsignedInt()
                    else -> throw IllegalArgumentException("binary.bytes.get expects zero or one argument")
                }
            }
        },
    )
)

private class IndicesProxy(size: Int) : ReadOnlyProxyObject(
    linkedMapOf(
        "first" to { 0 },
        "last" to { size - 1 },
        "start" to { 0 },
        "endInclusive" to { size - 1 },
        "endExclusive" to { size },
        "step" to { 1 },
    )
)

private class BinaryStringRecordProxy(record: BinaryStringRecord) : ReadOnlyProxyObject(
    linkedMapOf(
        "value" to { record.value },
        "offset" to { record.offset },
        "size" to { record.size },
        "index" to { record.index },
        "tag" to { record.tag },
    )
)

private class BinarySizeNodeProxy(node: BinarySizeNode) : ReadOnlyProxyObject(
    linkedMapOf(
        "name" to { node.name },
        "size" to { node.size },
        "offset" to { node.offset },
        "children" to { ProxyArray.fromList(node.children.map(::BinarySizeNodeProxy)) },
        "percentage" to {
            ProxyExecutable { args ->
                require(args.size == 1) { "binary.sizeAnalysis.percentage expects one argument" }
                node.percentage(args[0].asInt())
            }
        },
    )
)

internal open class ReadOnlyProxyObject(
    private val members: LinkedHashMap<String, () -> Any?>,
) : ProxyObject {
    override fun getMember(key: String): Any? = members[key]?.invoke()

    override fun getMemberKeys(): Any = ProxyArray.fromList(members.keys.toList())

    override fun hasMember(key: String): Boolean = key in members

    override fun putMember(key: String, value: Value) {
    }
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF
