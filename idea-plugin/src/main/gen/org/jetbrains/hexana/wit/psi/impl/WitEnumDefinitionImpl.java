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

public class WitEnumDefinitionImpl extends WitTypeDeclarationImpl implements WitEnumDefinition {

  public WitEnumDefinitionImpl(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public void accept(@NotNull WitElementVisitor visitor) {
    visitor.visitEnumDefinition(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof WitElementVisitor) accept((WitElementVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public List<WitEnumCase> getEnumCaseList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, WitEnumCase.class);
  }

  @Override
  @NotNull
  public PsiElement getIdentifier() {
    return findNotNullChildByType(IDENTIFIER);
  }

}
