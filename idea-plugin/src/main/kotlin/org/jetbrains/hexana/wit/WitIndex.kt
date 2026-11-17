package org.jetbrains.hexana.wit

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.endOffset
import com.intellij.util.indexing.*
import com.intellij.util.indexing.impl.CollectionDataExternalizer
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import org.jetbrains.hexana.wit.psi.WitPackageDefinition
import org.jetbrains.hexana.wit.psi.WitResolver
import org.jetbrains.hexana.wit.psi.WitWorldDefinition
import java.io.DataInput
import java.io.DataOutput

val WitIndexId: ID<String, Collection<WitDeclaration>> = ID.create("WitFileIndex")

enum class WitDeclarationType {
    PACKAGE,
    EXPORT
}

data class WitDeclaration(
    val name: String,
    val offset: Int,
    val type: WitDeclarationType,
    val endOffset: Int = offset,
    val version: String? = null
)
object WitDeclarationExternalizer : DataExternalizer<WitDeclaration> {
    override fun save(out: DataOutput, value: WitDeclaration) {
        out.writeUTF(value.name)
        out.writeInt(value.offset)
        out.writeInt(value.type.ordinal)
        out.writeInt(value.endOffset)
        out.writeUTF(value.version ?: "")
    }

    override fun read(`in`: DataInput) = WitDeclaration(
        `in`.readUTF(),
        `in`.readInt(),
        WitDeclarationType.entries[`in`.readInt()],
        `in`.readInt(),
        `in`.readUTF()
    )
}

class WitComponentIndex: FileBasedIndexExtension<String, Collection<WitDeclaration>>() {
    override fun getName() = WitIndexId
    override fun getIndexer() = DataIndexer<String, Collection<WitDeclaration>, FileContent> { content ->
        buildMap<String, MutableCollection<WitDeclaration>> {
            content.psiFile.children.filter { it is WitPackageDefinition || it is WitWorldDefinition }
                .flatMap {
                    if (it is WitWorldDefinition) {
                        it.exportDefinitionList.mapNotNull {
                            val usePath = it.usePath ?: return@mapNotNull null
                            val text = usePath.text
                            val packageName = WitResolver.adjustPackageName(text, usePath) ?: return@mapNotNull null
                            val (pkgName, pkgVer) = WitResolver.splitNameAndVersion(packageName)
                            WitDeclaration(
                                "$pkgName/$text", it.textOffset, WitDeclarationType.EXPORT,
                                it.textOffset + it.textLength,
                                pkgVer
                            )
                        }
                    }
                    else if (it is WitPackageDefinition) {
                        val (pkgName, pkgVer) = WitResolver.splitNameAndVersion(it.packageName.text)
                        listOf(WitDeclaration(
                            pkgName, it.textOffset, WitDeclarationType.PACKAGE,
                            it.textOffset + it.textLength,
                            pkgVer
                        ))
                    }
                    else emptyList()
                }.forEach { put(it.name, mutableListOf(it)) }
        }
    }

    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == WitFileType.INSTANCE }

    override fun dependsOnFileContent() = true

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer() = CollectionDataExternalizer(WitDeclarationExternalizer)

    override fun getVersion(): Int = 4
}

fun processIndexEntries(
    indexId: ID<String, Collection<WitDeclaration>>,
    nameToSearchFor: String,
    scope: GlobalSearchScope,
    processor: FileBasedIndex.ValueProcessor<Collection<WitDeclaration>>
): Boolean =
    FileBasedIndex.getInstance().processValues(indexId, nameToSearchFor, null, processor, scope)