// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.openapi.util.NlsSafe;

public interface WitIncludeNamesItem extends NavigatablePsiElement, PsiNamedElement, PsiNameIdentifierOwner {

  @Nullable String getName();

  @Nullable PsiElement getNameIdentifier();

  @Nullable PsiElement setName(@NlsSafe @NotNull String name);

  int getTextOffset();

}
