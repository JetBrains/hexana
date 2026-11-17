// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitWorldItem extends PsiElement {

  @NotNull
  List<WitEnumItem> getEnumItemList();

  @NotNull
  List<WitExportItem> getExportItemList();

  @NotNull
  List<WitFlagsItems> getFlagsItemsList();

  @NotNull
  List<WitGate> getGateList();

  @NotNull
  List<WitImportItem> getImportItemList();

  @NotNull
  List<WitIncludeItem> getIncludeItemList();

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

  @NotNull
  PsiElement getIdentifier();

}
