// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitTypeUsage extends PsiElement {

  @Nullable
  WitFutureItem getFutureItem();

  @Nullable
  WitHandle getHandle();

  @Nullable
  WitListDefinition getListDefinition();

  @Nullable
  WitOptionItem getOptionItem();

  @Nullable
  WitResultItem getResultItem();

  @Nullable
  WitStreamItem getStreamItem();

  @Nullable
  WitTupleDefinition getTupleDefinition();

  @Nullable
  PsiElement getIdentifier();

}
