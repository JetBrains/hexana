// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitWorldDefinition extends PsiElement {

  @NotNull
  List<WitDeprecatedGate> getDeprecatedGateList();

  @NotNull
  List<WitEnumDefinition> getEnumDefinitionList();

  @NotNull
  List<WitExportDefinition> getExportDefinitionList();

  @NotNull
  List<WitFlagsDefinition> getFlagsDefinitionList();

  @NotNull
  List<WitImportDefinition> getImportDefinitionList();

  @NotNull
  List<WitIncludeDeclaration> getIncludeDeclarationList();

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

  @NotNull
  PsiElement getIdentifier();

}
