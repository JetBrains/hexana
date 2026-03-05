// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitWorldDefinition extends PsiElement {

  @NotNull
  List<WitExportDefinition> getExportDefinitionList();

  @NotNull
  List<WitGateItem> getGateItemList();

  @NotNull
  List<WitImportDefinition> getImportDefinitionList();

  @NotNull
  List<WitIncludeDeclaration> getIncludeDeclarationList();

  @NotNull
  List<WitTypeDeclaration> getTypeDeclarationList();

  @NotNull
  List<WitUseDeclaration> getUseDeclarationList();

  @NotNull
  PsiElement getIdentifier();

}
