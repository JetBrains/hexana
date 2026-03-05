// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface WitNestedPackageDefinition extends PsiElement {

  @NotNull
  List<WitInterfaceDefinition> getInterfaceDefinitionList();

  @NotNull
  WitPackageName getPackageName();

  @NotNull
  List<WitToplevelUseDeclaration> getToplevelUseDeclarationList();

  @NotNull
  List<WitWorldDefinition> getWorldDefinitionList();

}
