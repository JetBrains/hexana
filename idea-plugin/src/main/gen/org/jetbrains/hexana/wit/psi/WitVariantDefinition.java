// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitVariantDefinition extends WitTypeDeclaration {

  @NotNull
  List<WitVariantCase> getVariantCaseList();

  @NotNull
  PsiElement getIdentifier();

}
