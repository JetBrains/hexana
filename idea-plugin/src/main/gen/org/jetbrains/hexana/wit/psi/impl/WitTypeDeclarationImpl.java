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

public abstract class WitTypeDeclarationImpl extends WitPsiElement implements WitTypeDeclaration {

  public WitTypeDeclarationImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitTypeDeclaration(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  public @NotNull String getName() {
    return WitPsiImplUtil.getName(this);
  }

  @Override
  public @Nullable PsiElement getNameIdentifier() {
    return WitPsiImplUtil.getNameIdentifier(this);
  }

  @Override
  public @NotNull WitTypeDeclaration setName(@NotNull String newName) {
    return WitPsiImplUtil.setName(this, newName);
  }

  @Override
  public int getTextOffset() {
    return WitPsiImplUtil.getTextOffset(this);
  }

}
