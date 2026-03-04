// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitExternType extends PsiElement {

  @NotNull
  List<WitEnumItem> getEnumItemList();

  @NotNull
  List<WitFlagsItems> getFlagsItemsList();

  @NotNull
  List<WitFuncItem> getFuncItemList();

  @Nullable
  WitFuncType getFuncType();

  @NotNull
  List<WitGate> getGateList();

  @NotNull
  List<WitRecordItem> getRecordItemList();

  @NotNull
  List<WitResourceItem> getResourceItemList();

  @NotNull
  List<WitTypeItem> getTypeItemList();

  @NotNull
  List<WitUseItem> getUseItemList();

  @NotNull
  List<WitVariantItems> getVariantItemsList();

}
