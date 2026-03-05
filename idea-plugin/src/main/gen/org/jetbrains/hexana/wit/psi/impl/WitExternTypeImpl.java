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

public class WitExternTypeImpl extends ASTWrapperPsiElement implements WitExternType {

  public WitExternTypeImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitExternType(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public List<WitFuncItem> getFuncItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitFuncItem.class);
  }

  @Override
  @Nullable
  public WitFuncType getFuncType() {
    return findChildByClass(WitFuncType.class);
  }

  @Override
  @NotNull
  public List<WitGate> getGateList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitGate.class);
  }

  @Override
  @NotNull
  public List<WitTypeDefinition> getTypeDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitTypeDefinition.class);
  }

  @Override
  @NotNull
  public List<WitUseDeclaration> getUseDeclarationList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitUseDeclaration.class);
  }

}
