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

public class WitWorldDefinitionImpl extends ASTWrapperPsiElement implements WitWorldDefinition {

  public WitWorldDefinitionImpl(@NotNull ASTNode node) {
    super(node);
  }

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
  public List<WitDeprecatedGate> getDeprecatedGateList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitDeprecatedGate.class);
  }

  @Override
  @NotNull
  public List<WitEnumDefinition> getEnumDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitEnumDefinition.class);
  }

  @Override
  @NotNull
  public List<WitExportDefinition> getExportDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitExportDefinition.class);
  }

  @Override
  @NotNull
  public List<WitFlagsDefinition> getFlagsDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitFlagsDefinition.class);
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
  public List<WitRecordDefinition> getRecordDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitRecordDefinition.class);
  }

  @Override
  @NotNull
  public List<WitResourceDefinition> getResourceDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitResourceDefinition.class);
  }

  @Override
  @NotNull
  public List<WitSinceGate> getSinceGateList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitSinceGate.class);
  }

  @Override
  @NotNull
  public List<WitTypeDefinition> getTypeDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitTypeDefinition.class);
  }

  @Override
  @NotNull
  public List<WitUnstableGate> getUnstableGateList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitUnstableGate.class);
  }

  @Override
  @NotNull
  public List<WitUseDeclaration> getUseDeclarationList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitUseDeclaration.class);
  }

  @Override
  @NotNull
  public List<WitVariantDefinition> getVariantDefinitionList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitVariantDefinition.class);
  }

  @Override
  @NotNull
  public PsiElement getIdentifier() {
    return findNotNullChildByType(IDENTIFIER);
  }

}
