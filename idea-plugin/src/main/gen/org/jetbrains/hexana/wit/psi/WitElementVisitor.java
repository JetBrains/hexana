// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiNameIdentifierOwner;

public class WitElementVisitor extends PsiElementVisitor {

  public void visitDeprecatedGate(@NotNull WitDeprecatedGate o) {
    visitGateItem(o);
  }

  public void visitEnumCase(@NotNull WitEnumCase o) {
    visitPsiElement(o);
  }

  public void visitEnumDefinition(@NotNull WitEnumDefinition o) {
    visitTypeDeclaration(o);
  }

  public void visitExportDefinition(@NotNull WitExportDefinition o) {
    visitPsiElement(o);
  }

  public void visitExternType(@NotNull WitExternType o) {
    visitPsiElement(o);
  }

  public void visitFeatureField(@NotNull WitFeatureField o) {
    visitPsiElement(o);
  }

  public void visitFlagsDefinition(@NotNull WitFlagsDefinition o) {
    visitTypeDeclaration(o);
  }

  public void visitFuncItem(@NotNull WitFuncItem o) {
    visitNavigatablePsiElement(o);
    // visitPsiNamedElement(o);
    // visitPsiNameIdentifierOwner(o);
  }

  public void visitFuncType(@NotNull WitFuncType o) {
    visitPsiElement(o);
  }

  public void visitFutureItem(@NotNull WitFutureItem o) {
    visitPsiElement(o);
  }

  public void visitGateItem(@NotNull WitGateItem o) {
    visitPsiElement(o);
  }

  public void visitHandle(@NotNull WitHandle o) {
    visitPsiElement(o);
  }

  public void visitImportDefinition(@NotNull WitImportDefinition o) {
    visitPsiElement(o);
  }

  public void visitIncludeDeclaration(@NotNull WitIncludeDeclaration o) {
    visitPsiElement(o);
  }

  public void visitIncludeNamesItem(@NotNull WitIncludeNamesItem o) {
    visitNavigatablePsiElement(o);
    // visitPsiNamedElement(o);
    // visitPsiNameIdentifierOwner(o);
  }

  public void visitIncludeNamesList(@NotNull WitIncludeNamesList o) {
    visitPsiElement(o);
  }

  public void visitInterfaceDefinition(@NotNull WitInterfaceDefinition o) {
    visitPackageItem(o);
  }

  public void visitListDefinition(@NotNull WitListDefinition o) {
    visitPsiElement(o);
  }

  public void visitNamedType(@NotNull WitNamedType o) {
    visitPsiElement(o);
  }

  public void visitNamedTypeList(@NotNull WitNamedTypeList o) {
    visitPsiElement(o);
  }

  public void visitNestedPackageDefinition(@NotNull WitNestedPackageDefinition o) {
    visitPsiElement(o);
  }

  public void visitOptionItem(@NotNull WitOptionItem o) {
    visitPsiElement(o);
  }

  public void visitPackageDefinition(@NotNull WitPackageDefinition o) {
    visitPsiElement(o);
  }

  public void visitPackageItem(@NotNull WitPackageItem o) {
    visitNavigatablePsiElement(o);
    // visitPsiNamedElement(o);
    // visitPsiNameIdentifierOwner(o);
  }

  public void visitPackageName(@NotNull WitPackageName o) {
    visitPsiElement(o);
  }

  public void visitParamList(@NotNull WitParamList o) {
    visitPsiElement(o);
  }

  public void visitRecordDefinition(@NotNull WitRecordDefinition o) {
    visitTypeDeclaration(o);
  }

  public void visitRecordField(@NotNull WitRecordField o) {
    visitPsiElement(o);
  }

  public void visitResourceDefinition(@NotNull WitResourceDefinition o) {
    visitTypeDeclaration(o);
  }

  public void visitResourceMethod(@NotNull WitResourceMethod o) {
    visitPsiElement(o);
  }

  public void visitResultItem(@NotNull WitResultItem o) {
    visitPsiElement(o);
  }

  public void visitResultList(@NotNull WitResultList o) {
    visitPsiElement(o);
  }

  public void visitSemver(@NotNull WitSemver o) {
    visitPsiElement(o);
  }

  public void visitSinceGate(@NotNull WitSinceGate o) {
    visitGateItem(o);
  }

  public void visitStaticFuncItem(@NotNull WitStaticFuncItem o) {
    visitNavigatablePsiElement(o);
    // visitPsiNamedElement(o);
    // visitPsiNameIdentifierOwner(o);
  }

  public void visitStreamItem(@NotNull WitStreamItem o) {
    visitPsiElement(o);
  }

  public void visitToplevelUseDeclaration(@NotNull WitToplevelUseDeclaration o) {
    visitPackageItem(o);
  }

  public void visitTupleDefinition(@NotNull WitTupleDefinition o) {
    visitPsiElement(o);
  }

  public void visitTupleList(@NotNull WitTupleList o) {
    visitPsiElement(o);
  }

  public void visitTypeDeclaration(@NotNull WitTypeDeclaration o) {
    visitNavigatablePsiElement(o);
    // visitPsiNamedElement(o);
    // visitPsiNameIdentifierOwner(o);
  }

  public void visitTypeDefinition(@NotNull WitTypeDefinition o) {
    visitTypeDeclaration(o);
  }

  public void visitTypeUsage(@NotNull WitTypeUsage o) {
    visitPsiElement(o);
  }

  public void visitUint(@NotNull WitUint o) {
    visitPsiElement(o);
  }

  public void visitUnstableGate(@NotNull WitUnstableGate o) {
    visitGateItem(o);
  }

  public void visitUseDeclaration(@NotNull WitUseDeclaration o) {
    visitPsiElement(o);
  }

  public void visitUseNamesItem(@NotNull WitUseNamesItem o) {
    visitNavigatablePsiElement(o);
    // visitPsiNamedElement(o);
    // visitPsiNameIdentifierOwner(o);
  }

  public void visitUseNamesList(@NotNull WitUseNamesList o) {
    visitPsiElement(o);
  }

  public void visitUsePath(@NotNull WitUsePath o) {
    visitPsiElement(o);
  }

  public void visitVariantCase(@NotNull WitVariantCase o) {
    visitPsiElement(o);
  }

  public void visitVariantDefinition(@NotNull WitVariantDefinition o) {
    visitTypeDeclaration(o);
  }

  public void visitVersionField(@NotNull WitVersionField o) {
    visitPsiElement(o);
  }

  public void visitWorldDefinition(@NotNull WitWorldDefinition o) {
    visitPackageItem(o);
  }

  public void visitNavigatablePsiElement(@NotNull NavigatablePsiElement o) {
    visitElement(o);
  }

  public void visitPsiElement(@NotNull PsiElement o) {
    visitElement(o);
  }

}
