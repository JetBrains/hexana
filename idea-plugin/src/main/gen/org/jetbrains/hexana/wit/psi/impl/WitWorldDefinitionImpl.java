// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static org.jetbrains.hexana.wit.psi.WitElementTypes.*;
import org.jetbrains.hexana.wit.psi.*;

public class WitWorldDefinitionImpl extends WitPackageItemImpl implements WitWorldDefinition {

  public WitWorldDefinitionImpl(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitWorldDefinition(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public List<WitExportDefinition> getExportDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitExportDefinition.class);
  }

  @Override
  @NotNull
  public List<WitGateItem> getGateItemList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitGateItem.class);
  }

  @Override
  @NotNull
  public List<WitImportDefinition> getImportDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitImportDefinition.class);
  }

  @Override
  @NotNull
  public List<WitIncludeDeclaration> getIncludeDeclarationList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitIncludeDeclaration.class);
  }

  @Override
  @NotNull
  public List<WitTypeDeclaration> getTypeDeclarationList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitTypeDeclaration.class);
  }

  @Override
  @NotNull
  public List<WitUseDeclaration> getUseDeclarationList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitUseDeclaration.class);
  }

  @Override
  @NotNull
  public PsiElement getIdentifier() {
    return findNotNullChildByType(IDENTIFIER);
  }

}
