// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitExternType extends PsiElement {

  @NotNull
  List<WitDeprecatedGate> getDeprecatedGateList();

  @NotNull
  List<WitEnumDefinition> getEnumDefinitionList();

  @NotNull
  List<WitFlagsDefinition> getFlagsDefinitionList();

  @NotNull
  List<WitFuncItem> getFuncItemList();

  @Nullable
  WitFuncType getFuncType();

  @NotNull
  List<WitRecordDefinition> getRecordDefinitionList();

  @NotNull
  List<WitResourceDefinition> getResourceDefinitionList();

  @NotNull
  List<WitSinceGate> getSinceGateList();

  @NotNull
  List<WitTypeDefinition> getTypeDefinitionList();

  @NotNull
  List<WitUnstableGate> getUnstableGateList();

  @NotNull
  List<WitUseDeclaration> getUseDeclarationList();

  @NotNull
  List<WitVariantDefinition> getVariantDefinitionList();

}
