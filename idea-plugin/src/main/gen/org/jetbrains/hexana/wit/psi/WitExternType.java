// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitExternType extends PsiElement {

  @NotNull
  List<WitFuncItem> getFuncItemList();

  @Nullable
  WitFuncType getFuncType();

  @NotNull
  List<WitGateItem> getGateItemList();

  @NotNull
  List<WitTypeDeclaration> getTypeDeclarationList();

  @NotNull
  List<WitUseDeclaration> getUseDeclarationList();

}
