// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitResourceMethod extends PsiElement {

  @Nullable
  WitFuncItem getFuncItem();

  @NotNull
  List<WitGateItem> getGateItemList();

  @Nullable
  WitParamList getParamList();

  @Nullable
  WitStaticFuncItem getStaticFuncItem();

}
