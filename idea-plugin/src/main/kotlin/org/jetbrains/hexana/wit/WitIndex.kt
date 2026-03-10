package org.jetbrains.hexana.wit

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.*
import com.intellij.util.indexing.impl.CollectionDataExternalizer
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import org.jetbrains.hexana.wit.psi.WitPackageDefinition
import java.io.DataInput
import java.io.DataOutput

val WitIndexId: ID<String, Collection<WitDeclaration>> = ID.create("WitFileIndex")

enum class WitDeclarationType {
    PACKAGE
}

data class WitDeclaration(val name: String, val offset: Int, val type: WitDeclarationType)

private object WitDeclarationExternalizer : DataExternalizer<WitDeclaration> {
    override fun save(out: DataOutput, value: WitDeclaration) {
        out.writeUTF(value.name)
        out.writeInt(value.offset)
        out.writeInt(value.type.ordinal)
    }

    override fun read(`in`: DataInput) = WitDeclaration(`in`.readUTF(), `in`.readInt(),
        WitDeclarationType.entries[`in`.readInt()])
}

class WitComponentIndex: FileBasedIndexExtension<String, Collection<WitDeclaration>>() {
    override fun getName() = WitIndexId
    override fun getIndexer() = DataIndexer<String, Collection<WitDeclaration>, FileContent> { content ->
        buildMap<String, MutableCollection<WitDeclaration>> {
            content.psiFile.children.filterIsInstance<WitPackageDefinition>()
                .map { WitDeclaration(it.packageName.text, it.textOffset, WitDeclarationType.PACKAGE) }
                .singleOrNull()?.let { put(it.name, mutableListOf(it)) }
        }
    }

    companion object {
        fun processPackageDeclarations(
            packageName: String,
            scope: GlobalSearchScope,
            processor: FileBasedIndex.ValueProcessor<Collection<WitDeclaration>>
        ): Boolean =
            FileBasedIndex.getInstance().processValues(WitIndexId, packageName, null, processor, scope)
    }
    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == WitFileType.INSTANCE }

    override fun dependsOnFileContent() = true

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer() = CollectionDataExternalizer(WitDeclarationExternalizer)

    override fun getVersion(): Int = 1
}