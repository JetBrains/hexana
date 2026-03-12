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

public class WitResourceMethodImpl extends WitPsiElement implements WitResourceMethod {

  public WitResourceMethodImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitResourceMethod(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public WitFuncItem getFuncItem() {
    return findChildByClass(WitFuncItem.class);
  }

  @Override
  @NotNull
  public List<WitGateItem> getGateItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitGateItem.class);
  }

  @Override
  @Nullable
  public WitParamList getParamList() {
    return findChildByClass(WitParamList.class);
  }

  @Override
  @Nullable
  public WitStaticFuncItem getStaticFuncItem() {
    return findChildByClass(WitStaticFuncItem.class);
  }

}
