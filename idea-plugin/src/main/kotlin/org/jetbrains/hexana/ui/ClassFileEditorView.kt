package org.jetbrains.hexana.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.javabytecode.AttributeRecord
import org.jetbrains.hexana.javabytecode.JavaBytecodeInstruction
import org.jetbrains.hexana.javabytecode.JavaBytecodeOperands
import org.jetbrains.hexana.javabytecode.JavaClassAccess
import org.jetbrains.hexana.javabytecode.JavaClassFile
import org.jetbrains.hexana.javabytecode.JavaCodeAttributeParser
import org.jetbrains.hexana.javabytecode.JavaConstantPool
import org.jetbrains.hexana.javabytecode.JavaConstantPoolEntry
import org.jetbrains.hexana.javabytecode.JavaFieldAccess
import org.jetbrains.hexana.javabytecode.JavaMethodAccess
import org.jetbrains.hexana.javabytecode.MethodInfo
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Three-tab editor view for a parsed JVM `.class` file:
 *   1. Header — class name + access flags + super class + interfaces.
 *   2. Methods — list of methods with their decoded bytecode listing.
 *   3. Constant pool — all `cp_info` entries by index.
 */
@Composable
internal fun ClassFileEditorView(classFile: JavaClassFile) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val labels = listOf("Header", "Methods", "Constant pool")
    val tabs by remember(selectedTab) {
        derivedStateOf {
            labels.mapIndexed { index, label ->
                TabData.Default(
                    selected = index == selectedTab,
                    content = { Text(label) },
                    closable = false,
                    onClick = { selectedTab = index },
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(tabs = tabs, style = JewelTheme.defaultTabStyle)
        Divider(orientation = Orientation.Horizontal)
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            when (selectedTab) {
                0 -> ClassHeaderTab(classFile)
                1 -> ClassMethodsTab(classFile)
                else -> ClassConstantPoolTab(classFile)
            }
        }
    }
}

@Composable
private fun ClassHeaderTab(cf: JavaClassFile) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "class ${cf.thisClassName}",
            style = TextStyle(fontWeight = FontWeight.Bold),
        )
        Text("extends ${cf.superClassName ?: "<none>"}")
        Text("class file version: ${cf.majorVersion}.${cf.minorVersion}")
        Text(
            "access flags: ${cf.accessFlags} ${renderClassAccessFlags(cf.accessFlags.raw)}",
            style = MonoStyle,
        )

        if (cf.interfaceIndices.isEmpty()) {
            Text("implements: (none)")
        } else {
            Text("implements:")
            for (idx in cf.interfaceIndices) {
                val ref = cf.constantPool[idx]
                val name = (ref as? JavaConstantPoolEntry.ClassRef)
                    ?.let { runCatching { cf.constantPool.utf8(it.nameIndex) }.getOrNull() }
                    ?: "<#$idx ???>"
                Text("  $name", style = MonoStyle)
            }
        }

        Text("fields: ${cf.fields.size}")
        Text("methods: ${cf.methods.size}")
        Text("class attributes: ${cf.attributes.size}")
    }
}

@Composable
private fun ClassMethodsTab(cf: JavaClassFile) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (cf.methods.isEmpty()) {
            item { Text("(no methods)") }
            return@LazyColumn
        }
        for (method in cf.methods) {
            item {
                MethodBlock(cf, method)
            }
        }
    }
}

@Composable
private fun MethodBlock(cf: JavaClassFile, method: MethodInfo) {
    val name = runCatching { cf.constantPool.utf8(method.nameIndex) }.getOrDefault("<#${method.nameIndex}>")
    val descriptor = runCatching { cf.constantPool.utf8(method.descriptorIndex) }.getOrDefault("<#${method.descriptorIndex}>")
    val code = runCatching { JavaCodeAttributeParser.parse(method, cf.constantPool) }.getOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "$name $descriptor",
            style = TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
        )
        Text(
            "  flags: 0x${method.accessFlags.raw.toString(16).padStart(4, '0')} " +
                renderMethodAccessFlags(method.accessFlags.raw),
            style = MonoStyle,
        )
        if (code == null) {
            Text("  (no Code attribute — abstract or native)", style = MonoStyle)
            return@Column
        }
        Text(
            "  max_stack=${code.maxStack} max_locals=${code.maxLocals} " +
                "code_length=${code.code.size} handlers=${code.exceptionTable.size}",
            style = MonoStyle,
        )
        for (insn in code.instructions) {
            Text(
                "  ${formatOffset(insn.bytecodeOffset)}: ${formatInstruction(insn, cf.constantPool)}",
                style = MonoStyle,
            )
        }
        if (code.exceptionTable.isNotEmpty()) {
            Text("  exception_table:", style = MonoStyle)
            for (handler in code.exceptionTable) {
                val catchName = if (handler.catchTypeCpIndex == 0) {
                    "any"
                } else {
                    val ref = cf.constantPool[handler.catchTypeCpIndex] as? JavaConstantPoolEntry.ClassRef
                    ref?.let { runCatching { cf.constantPool.utf8(it.nameIndex) }.getOrNull() }
                        ?: "<#${handler.catchTypeCpIndex} ???>"
                }
                Text(
                    "    [${formatOffset(handler.startPc)}-${formatOffset(handler.endPc)}) " +
                        "-> ${formatOffset(handler.handlerPc)}  catch $catchName",
                    style = MonoStyle,
                )
            }
        }
    }
}

@Composable
private fun ClassConstantPoolTab(cf: JavaClassFile) {
    val rows = remember(cf) { cf.constantPool.asSequence().toList() }
    val listState = rememberLazyListState()
    LazyColumn(state = listState) {
        items(rows.size) { idx ->
            val (index, entry) = rows[idx]
            Text("#${index.toString().padStart(4, ' ')}  ${renderEntry(entry, cf.constantPool)}", style = MonoStyle)
        }
    }
}

// -----------------------------------------------------------------------------
// Rendering helpers
// -----------------------------------------------------------------------------

private val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)

private fun formatOffset(offset: Int): String = offset.toString().padStart(4, ' ')

private fun formatInstruction(insn: JavaBytecodeInstruction, cp: JavaConstantPool): String {
    val mnemonic = insn.opcode.mnemonic
    return when (val ops = insn.operands) {
        JavaBytecodeOperands.None -> mnemonic
        is JavaBytecodeOperands.Byte1 -> "$mnemonic ${ops.value}"
        is JavaBytecodeOperands.Short1 -> {
            val resolved = resolveCpReference(ops.value, cp, insn.opcode.mnemonic)
            if (resolved != null) "$mnemonic #${ops.value}  // $resolved" else "$mnemonic ${ops.value}"
        }
        is JavaBytecodeOperands.Wide1 -> "$mnemonic ${ops.value}"
        is JavaBytecodeOperands.IInc -> "$mnemonic ${ops.varIndex}, ${ops.delta}"
        is JavaBytecodeOperands.InvokeInterface ->
            "$mnemonic #${ops.cpIndex}, ${ops.count}  // ${resolveCpReference(ops.cpIndex, cp, mnemonic) ?: "?"}"
        is JavaBytecodeOperands.InvokeDynamic ->
            "$mnemonic #${ops.cpIndex}  // ${resolveCpReference(ops.cpIndex, cp, mnemonic) ?: "?"}"
        is JavaBytecodeOperands.MultiANewArray ->
            "$mnemonic #${ops.cpIndex}, ${ops.dimensions}  // ${resolveCpReference(ops.cpIndex, cp, mnemonic) ?: "?"}"
        is JavaBytecodeOperands.TableSwitch ->
            "$mnemonic default=${ops.defaultOffset} ${ops.low}..${ops.high} -> ${ops.jumpOffsets}"
        is JavaBytecodeOperands.LookupSwitch ->
            "$mnemonic default=${ops.defaultOffset} pairs=${ops.pairs}"
        is JavaBytecodeOperands.Wide ->
            "$mnemonic 0x${ops.innerOpcode.toString(16).padStart(2, '0')} var=${ops.varIndex}" +
                (ops.constant?.let { ", const=$it" } ?: "")
        is JavaBytecodeOperands.Raw -> "$mnemonic <raw ${ops.bytes.size} bytes>"
    }
}

private fun resolveCpReference(index: Int, cp: JavaConstantPool, mnemonic: String): String? {
    if (index <= 0) return null
    val entry = runCatching { cp[index] }.getOrNull() ?: return null
    return when (entry) {
        is JavaConstantPoolEntry.ClassRef ->
            runCatching { cp.utf8(entry.nameIndex) }.getOrNull()
        is JavaConstantPoolEntry.StringRef ->
            "\"${runCatching { cp.utf8(entry.stringIndex) }.getOrDefault("?")}\""
        is JavaConstantPoolEntry.Methodref,
        is JavaConstantPoolEntry.InterfaceMethodref,
        is JavaConstantPoolEntry.Fieldref,
        -> {
            val (classIdx, natIdx) = when (entry) {
                is JavaConstantPoolEntry.Methodref -> entry.classIndex to entry.nameAndTypeIndex
                is JavaConstantPoolEntry.InterfaceMethodref -> entry.classIndex to entry.nameAndTypeIndex
                is JavaConstantPoolEntry.Fieldref -> entry.classIndex to entry.nameAndTypeIndex
                else -> return null
            }
            val owner = (cp[classIdx] as? JavaConstantPoolEntry.ClassRef)
                ?.let { runCatching { cp.utf8(it.nameIndex) }.getOrNull() } ?: "?"
            val nat = cp[natIdx] as? JavaConstantPoolEntry.NameAndType ?: return owner
            val name = runCatching { cp.utf8(nat.nameIndex) }.getOrDefault("?")
            val desc = runCatching { cp.utf8(nat.descriptorIndex) }.getOrDefault("?")
            "$owner.$name:$desc"
        }
        is JavaConstantPoolEntry.Integer -> entry.value.toString()
        is JavaConstantPoolEntry.Float -> entry.value.toString() + "f"
        is JavaConstantPoolEntry.Long -> entry.value.toString() + "L"
        is JavaConstantPoolEntry.Double -> entry.value.toString()
        is JavaConstantPoolEntry.Utf8 -> "\"${entry.value}\""
        else -> null
    }
}

private fun renderEntry(entry: JavaConstantPoolEntry, cp: JavaConstantPool): String = when (entry) {
    is JavaConstantPoolEntry.Utf8 -> "Utf8                \"${entry.value}\""
    is JavaConstantPoolEntry.Integer -> "Integer             ${entry.value}"
    is JavaConstantPoolEntry.Float -> "Float               ${entry.value}"
    is JavaConstantPoolEntry.Long -> "Long                ${entry.value}"
    is JavaConstantPoolEntry.Double -> "Double              ${entry.value}"
    is JavaConstantPoolEntry.ClassRef -> "Class               #${entry.nameIndex}  // ${runCatching { cp.utf8(entry.nameIndex) }.getOrDefault("?")}"
    is JavaConstantPoolEntry.StringRef -> "String              #${entry.stringIndex}  // \"${runCatching { cp.utf8(entry.stringIndex) }.getOrDefault("?")}\""
    is JavaConstantPoolEntry.Fieldref -> "Fieldref            #${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.Methodref -> "Methodref           #${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.InterfaceMethodref -> "InterfaceMethodref  #${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.NameAndType -> "NameAndType         #${entry.nameIndex}:#${entry.descriptorIndex}"
    is JavaConstantPoolEntry.MethodHandle -> "MethodHandle        kind=${entry.referenceKind} ref=#${entry.referenceIndex}"
    is JavaConstantPoolEntry.MethodType -> "MethodType          #${entry.descriptorIndex}"
    is JavaConstantPoolEntry.Dynamic -> "Dynamic             bsm=${entry.bootstrapMethodAttrIndex} nat=#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.InvokeDynamic -> "InvokeDynamic       bsm=${entry.bootstrapMethodAttrIndex} nat=#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.ModuleRef -> "Module              #${entry.nameIndex}"
    is JavaConstantPoolEntry.PackageRef -> "Package             #${entry.nameIndex}"
    JavaConstantPoolEntry.Unusable -> "(unusable)"
}

private fun renderClassAccessFlags(raw: Int): String = buildString {
    if (raw and JavaClassAccess.PUBLIC != 0) append("public ")
    if (raw and JavaClassAccess.FINAL != 0) append("final ")
    if (raw and JavaClassAccess.SUPER != 0) append("super ")
    if (raw and JavaClassAccess.INTERFACE != 0) append("interface ")
    if (raw and JavaClassAccess.ABSTRACT != 0) append("abstract ")
    if (raw and JavaClassAccess.SYNTHETIC != 0) append("synthetic ")
    if (raw and JavaClassAccess.ANNOTATION != 0) append("annotation ")
    if (raw and JavaClassAccess.ENUM != 0) append("enum ")
    if (raw and JavaClassAccess.MODULE != 0) append("module ")
}.trim()

private fun renderMethodAccessFlags(raw: Int): String = buildString {
    if (raw and JavaMethodAccess.PUBLIC != 0) append("public ")
    if (raw and JavaMethodAccess.PRIVATE != 0) append("private ")
    if (raw and JavaMethodAccess.PROTECTED != 0) append("protected ")
    if (raw and JavaMethodAccess.STATIC != 0) append("static ")
    if (raw and JavaMethodAccess.FINAL != 0) append("final ")
    if (raw and JavaMethodAccess.SYNCHRONIZED != 0) append("synchronized ")
    if (raw and JavaMethodAccess.BRIDGE != 0) append("bridge ")
    if (raw and JavaMethodAccess.VARARGS != 0) append("varargs ")
    if (raw and JavaMethodAccess.NATIVE != 0) append("native ")
    if (raw and JavaMethodAccess.ABSTRACT != 0) append("abstract ")
    if (raw and JavaMethodAccess.STRICT != 0) append("strict ")
    if (raw and JavaMethodAccess.SYNTHETIC != 0) append("synthetic ")
}.trim()

/** Unused for now — kept for symmetry; field rendering follows in a later commit. */
@Suppress("unused")
private fun renderFieldAccessFlags(raw: Int): String = buildString {
    if (raw and JavaFieldAccess.PUBLIC != 0) append("public ")
    if (raw and JavaFieldAccess.PRIVATE != 0) append("private ")
    if (raw and JavaFieldAccess.PROTECTED != 0) append("protected ")
    if (raw and JavaFieldAccess.STATIC != 0) append("static ")
    if (raw and JavaFieldAccess.FINAL != 0) append("final ")
    if (raw and JavaFieldAccess.VOLATILE != 0) append("volatile ")
    if (raw and JavaFieldAccess.TRANSIENT != 0) append("transient ")
    if (raw and JavaFieldAccess.SYNTHETIC != 0) append("synthetic ")
    if (raw and JavaFieldAccess.ENUM != 0) append("enum ")
}.trim()

/** Unused for now — kept for compile-side import warnings. */
@Suppress("unused")
private fun unusedAttribute(): AttributeRecord = AttributeRecord(0, ByteArray(0))
