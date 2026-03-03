// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitWorldDefinition extends PsiElement {

  @Nullable
  WitExportItem getExportItem();

  @Nullable
  WitImportItem getImportItem();

  @Nullable
  WitIncludeItem getIncludeItem();

  @Nullable
  WitTypedefItem getTypedefItem();

  @Nullable
  WitUseItem getUseItem();

}
