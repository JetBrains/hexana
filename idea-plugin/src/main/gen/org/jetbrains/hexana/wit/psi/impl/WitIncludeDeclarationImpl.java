// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static org.jetbrains.hexana.wit.psi.WitElementTypes.*;
import org.jetbrains.hexana.wit.psi.WitPsiElement;
import org.jetbrains.hexana.wit.psi.*;

public class WitIncludeDeclarationImpl extends WitPsiElement implements WitIncludeDeclaration {

  public WitIncludeDeclarationImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitIncludeDeclaration(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public WitIncludeNamesList getIncludeNamesList() {
    return findChildByClass(WitIncludeNamesList.class);
  }

  @Override
  @NotNull
  public WitUsePath getUsePath() {
    return findNotNullChildByClass(WitUsePath.class);
  }

}
