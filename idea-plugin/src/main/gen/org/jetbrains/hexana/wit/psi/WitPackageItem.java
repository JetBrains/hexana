// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiNameIdentifierOwner;

public interface WitPackageItem extends NavigatablePsiElement, PsiNamedElement, PsiNameIdentifierOwner {

  @NotNull String getName();

  @Nullable PsiElement getNameIdentifier();

  @NotNull WitPackageItem setName(@NotNull String newName);

  int getTextOffset();

}
