// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitWorldItems extends PsiElement {

  @Nullable
  WitEnumItems getEnumItems();

  @Nullable
  WitExportItem getExportItem();

  @Nullable
  WitFlagsItems getFlagsItems();

  @NotNull
  WitGate getGate();

  @Nullable
  WitImportItem getImportItem();

  @Nullable
  WitIncludeItem getIncludeItem();

  @Nullable
  WitRecordItem getRecordItem();

  @Nullable
  WitResourceItem getResourceItem();

  @Nullable
  WitTypeItem getTypeItem();

  @Nullable
  WitUseItem getUseItem();

  @Nullable
  WitVariantItems getVariantItems();

}
