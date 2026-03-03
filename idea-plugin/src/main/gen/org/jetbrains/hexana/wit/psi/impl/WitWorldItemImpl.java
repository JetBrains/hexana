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

public class WitWorldItemImpl extends ASTWrapperPsiElement implements WitWorldItem {

  public WitWorldItemImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitWorldItem(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public List<WitEnumItems> getEnumItemsList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitEnumItems.class);
  }

  @Override
  @NotNull
  public List<WitExportItem> getExportItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitExportItem.class);
  }

  @Override
  @NotNull
  public List<WitFlagsItems> getFlagsItemsList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitFlagsItems.class);
  }

  @Override
  @NotNull
  public List<WitGate> getGateList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitGate.class);
  }

  @Override
  @NotNull
  public List<WitImportItem> getImportItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitImportItem.class);
  }

  @Override
  @NotNull
  public List<WitIncludeItem> getIncludeItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitIncludeItem.class);
  }

  @Override
  @NotNull
  public List<WitRecordItem> getRecordItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitRecordItem.class);
  }

  @Override
  @NotNull
  public List<WitResourceItem> getResourceItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitResourceItem.class);
  }

  @Override
  @NotNull
  public List<WitTypeItem> getTypeItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitTypeItem.class);
  }

  @Override
  @NotNull
  public List<WitUseItem> getUseItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitUseItem.class);
  }

  @Override
  @NotNull
  public List<WitVariantItems> getVariantItemsList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitVariantItems.class);
  }

  @Override
  @NotNull
  public PsiElement getIdentifier() {
    return findNotNullChildByType(IDENTIFIER);
  }

}
