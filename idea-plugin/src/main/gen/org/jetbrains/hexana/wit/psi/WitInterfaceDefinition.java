// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitInterfaceDefinition extends PsiElement {

  @NotNull
  List<WitFuncItem> getFuncItemList();

  @NotNull
  List<WitGate> getGateList();

  @NotNull
  List<WitTypeDefinition> getTypeDefinitionList();

  @NotNull
  List<WitUseDeclaration> getUseDeclarationList();

  @NotNull
  PsiElement getIdentifier();

}
