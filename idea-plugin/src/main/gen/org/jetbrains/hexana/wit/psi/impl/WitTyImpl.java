// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static org.jetbrains.hexana.wit.psi.WitElementTypes.*;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import org.jetbrains.hexana.wit.psi.*;

public class WitTyImpl extends ASTWrapperPsiElement implements WitTy {

  public WitTyImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitTy(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public WitFutureItem getFutureItem() {
    return findChildByClass(WitFutureItem.class);
  }

  @Override
  @Nullable
  public WitHandle getHandle() {
    return findChildByClass(WitHandle.class);
  }

  @Override
  @Nullable
  public WitListDefinition getListDefinition() {
    return findChildByClass(WitListDefinition.class);
  }

  @Override
  @Nullable
  public WitOptionItem getOptionItem() {
    return findChildByClass(WitOptionItem.class);
  }

  @Override
  @Nullable
  public WitResultItem getResultItem() {
    return findChildByClass(WitResultItem.class);
  }

  @Override
  @Nullable
  public WitStreamItem getStreamItem() {
    return findChildByClass(WitStreamItem.class);
  }

  @Override
  @Nullable
  public WitTupleDefinition getTupleDefinition() {
    return findChildByClass(WitTupleDefinition.class);
  }

  @Override
  @Nullable
  public PsiElement getIdentifier() {
    return findChildByType(IDENTIFIER);
  }

}
