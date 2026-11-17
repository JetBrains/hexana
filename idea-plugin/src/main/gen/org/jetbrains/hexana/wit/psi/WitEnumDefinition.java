// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitEnumDefinition extends WitTypeDeclaration {

  @NotNull
  List<WitEnumCase> getEnumCaseList();

  @NotNull
  PsiElement getIdentifier();

}
