// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.PsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.hexana.wit.psi.impl.*;

public interface WitElementTypes {

  IElementType DEPRECATED_GATE = new WitElementType("DEPRECATED_GATE");
  IElementType ENUM_CASE = new WitElementType("ENUM_CASE");
  IElementType ENUM_CASES = new WitElementType("ENUM_CASES");
  IElementType ENUM_ITEM = new WitElementType("ENUM_ITEM");
  IElementType EXPORT_ITEM = new WitElementType("EXPORT_ITEM");
  IElementType EXTERN_TYPE = new WitElementType("EXTERN_TYPE");
  IElementType FEATURE_FIELD = new WitElementType("FEATURE_FIELD");
  IElementType FLAGS_FIELDS = new WitElementType("FLAGS_FIELDS");
  IElementType FLAGS_ITEMS = new WitElementType("FLAGS_ITEMS");
  IElementType FUNC_ITEM = new WitElementType("FUNC_ITEM");
  IElementType FUNC_TYPE = new WitElementType("FUNC_TYPE");
  IElementType FUTURE_ITEM = new WitElementType("FUTURE_ITEM");
  IElementType GATE = new WitElementType("GATE");
  IElementType HANDLE = new WitElementType("HANDLE");
  IElementType IMPORT_ITEM = new WitElementType("IMPORT_ITEM");
  IElementType INCLUDE_ITEM = new WitElementType("INCLUDE_ITEM");
  IElementType INCLUDE_NAMES_ITEM = new WitElementType("INCLUDE_NAMES_ITEM");
  IElementType INCLUDE_NAMES_LIST = new WitElementType("INCLUDE_NAMES_LIST");
  IElementType INTERFACE_ITEM = new WitElementType("INTERFACE_ITEM");
  IElementType LIST_DEFINITION = new WitElementType("LIST_DEFINITION");
  IElementType NAMED_TYPE = new WitElementType("NAMED_TYPE");
  IElementType NAMED_TYPE_LIST = new WitElementType("NAMED_TYPE_LIST");
  IElementType NESTED_PACKAGE_DEFINITION = new WitElementType("NESTED_PACKAGE_DEFINITION");
  IElementType OPTION_ITEM = new WitElementType("OPTION_ITEM");
  IElementType PACKAGE_DEFINITION = new WitElementType("PACKAGE_DEFINITION");
  IElementType PACKAGE_NAME = new WitElementType("PACKAGE_NAME");
  IElementType PARAM_LIST = new WitElementType("PARAM_LIST");
  IElementType RECORD_FIELD = new WitElementType("RECORD_FIELD");
  IElementType RECORD_FIELDS = new WitElementType("RECORD_FIELDS");
  IElementType RECORD_ITEM = new WitElementType("RECORD_ITEM");
  IElementType RESOURCE_ITEM = new WitElementType("RESOURCE_ITEM");
  IElementType RESOURCE_METHOD = new WitElementType("RESOURCE_METHOD");
  IElementType RESULT_ITEM = new WitElementType("RESULT_ITEM");
  IElementType RESULT_LIST = new WitElementType("RESULT_LIST");
  IElementType SEMVER = new WitElementType("SEMVER");
  IElementType SINCE_GATE = new WitElementType("SINCE_GATE");
  IElementType STREAM_ITEM = new WitElementType("STREAM_ITEM");
  IElementType TOPLEVEL_USE_ITEM = new WitElementType("TOPLEVEL_USE_ITEM");
  IElementType TUPLE_DEFINITION = new WitElementType("TUPLE_DEFINITION");
  IElementType TUPLE_LIST = new WitElementType("TUPLE_LIST");
  IElementType TY = new WitElementType("TY");
  IElementType TYPE_ITEM = new WitElementType("TYPE_ITEM");
  IElementType UINT = new WitElementType("UINT");
  IElementType UNSTABLE_GATE = new WitElementType("UNSTABLE_GATE");
  IElementType USE_ITEM = new WitElementType("USE_ITEM");
  IElementType USE_NAMES_ITEM = new WitElementType("USE_NAMES_ITEM");
  IElementType USE_NAMES_LIST = new WitElementType("USE_NAMES_LIST");
  IElementType USE_PATH = new WitElementType("USE_PATH");
  IElementType VARIANT_CASE = new WitElementType("VARIANT_CASE");
  IElementType VARIANT_CASES = new WitElementType("VARIANT_CASES");
  IElementType VARIANT_ITEMS = new WitElementType("VARIANT_ITEMS");
  IElementType VERSION_FIELD = new WitElementType("VERSION_FIELD");
  IElementType WORLD_ITEM = new WitElementType("WORLD_ITEM");

  IElementType ARROW = new WitToken("->");
  IElementType ASYNC = new WitToken("async");
  IElementType AT = new WitToken("@");
  IElementType BLOB = new WitToken("blob");
  IElementType BLOCK_COMMENT = new WitToken("BLOCK_COMMENT");
  IElementType BORROW = new WitToken("borrow");
  IElementType CLOSE_BRACE = new WitToken("}");
  IElementType CLOSE_PAREN = new WitToken(")");
  IElementType COLON = new WitToken(":");
  IElementType COMMA = new WitToken(",");
  IElementType CONSTRUCTOR = new WitToken("constructor");
  IElementType DOT = new WitToken(".");
  IElementType ENUM = new WitToken("enum");
  IElementType EQUALS = new WitToken("=");
  IElementType EXPORT = new WitToken("export");
  IElementType FLAGS = new WitToken("flags");
  IElementType FUNC = new WitToken("func");
  IElementType FUTURE = new WitToken("future");
  IElementType GREATER_THAN = new WitToken(">");
  IElementType IDENTIFIER = new WitToken("IDENTIFIER");
  IElementType IMPORT = new WitToken("import");
  IElementType INCLUDE = new WitToken("include");
  IElementType INTERFACE = new WitToken("interface");
  IElementType LESS_THAN = new WitToken("<");
  IElementType LINE_COMMENT = new WitToken("LINE_COMMENT");
  IElementType LIST = new WitToken("list");
  IElementType NUMBER = new WitToken("NUMBER");
  IElementType OPEN_BRACE = new WitToken("{");
  IElementType OPEN_PAREN = new WitToken("(");
  IElementType OPTION = new WitToken("option");
  IElementType PACKAGE = new WitToken("package");
  IElementType RECORD = new WitToken("record");
  IElementType RESOURCE = new WitToken("resource");
  IElementType RESTRICTED = new WitToken("restricted");
  IElementType RESULT = new WitToken("result");
  IElementType SEMI = new WitToken(";");
  IElementType SLASH = new WitToken("/");
  IElementType STATIC = new WitToken("static");
  IElementType STREAM = new WitToken("stream");
  IElementType TUPLE = new WitToken("tuple");
  IElementType TYPE = new WitToken("type");
  IElementType UNDERSCORE = new WitToken("_");
  IElementType USE = new WitToken("use");
  IElementType VARIANT = new WitToken("variant");
  IElementType WORLD = new WitToken("world");
  IElementType Ε = new WitToken("ϵ");

  class Factory {
    public static PsiElement createElement(ASTNode node) {
      IElementType type = node.getElementType();
      if (type == DEPRECATED_GATE) {
        return new WitDeprecatedGateImpl(node);
      }
      else if (type == ENUM_CASE) {
        return new WitEnumCaseImpl(node);
      }
      else if (type == ENUM_CASES) {
        return new WitEnumCasesImpl(node);
      }
      else if (type == ENUM_ITEM) {
        return new WitEnumItemImpl(node);
      }
      else if (type == EXPORT_ITEM) {
        return new WitExportItemImpl(node);
      }
      else if (type == EXTERN_TYPE) {
        return new WitExternTypeImpl(node);
      }
      else if (type == FEATURE_FIELD) {
        return new WitFeatureFieldImpl(node);
      }
      else if (type == FLAGS_FIELDS) {
        return new WitFlagsFieldsImpl(node);
      }
      else if (type == FLAGS_ITEMS) {
        return new WitFlagsItemsImpl(node);
      }
      else if (type == FUNC_ITEM) {
        return new WitFuncItemImpl(node);
      }
      else if (type == FUNC_TYPE) {
        return new WitFuncTypeImpl(node);
      }
      else if (type == FUTURE_ITEM) {
        return new WitFutureItemImpl(node);
      }
      else if (type == GATE) {
        return new WitGateImpl(node);
      }
      else if (type == HANDLE) {
        return new WitHandleImpl(node);
      }
      else if (type == IMPORT_ITEM) {
        return new WitImportItemImpl(node);
      }
      else if (type == INCLUDE_ITEM) {
        return new WitIncludeItemImpl(node);
      }
      else if (type == INCLUDE_NAMES_ITEM) {
        return new WitIncludeNamesItemImpl(node);
      }
      else if (type == INCLUDE_NAMES_LIST) {
        return new WitIncludeNamesListImpl(node);
      }
      else if (type == INTERFACE_ITEM) {
        return new WitInterfaceItemImpl(node);
      }
      else if (type == LIST_DEFINITION) {
        return new WitListDefinitionImpl(node);
      }
      else if (type == NAMED_TYPE) {
        return new WitNamedTypeImpl(node);
      }
      else if (type == NAMED_TYPE_LIST) {
        return new WitNamedTypeListImpl(node);
      }
      else if (type == NESTED_PACKAGE_DEFINITION) {
        return new WitNestedPackageDefinitionImpl(node);
      }
      else if (type == OPTION_ITEM) {
        return new WitOptionItemImpl(node);
      }
      else if (type == PACKAGE_DEFINITION) {
        return new WitPackageDefinitionImpl(node);
      }
      else if (type == PACKAGE_NAME) {
        return new WitPackageNameImpl(node);
      }
      else if (type == PARAM_LIST) {
        return new WitParamListImpl(node);
      }
      else if (type == RECORD_FIELD) {
        return new WitRecordFieldImpl(node);
      }
      else if (type == RECORD_FIELDS) {
        return new WitRecordFieldsImpl(node);
      }
      else if (type == RECORD_ITEM) {
        return new WitRecordItemImpl(node);
      }
      else if (type == RESOURCE_ITEM) {
        return new WitResourceItemImpl(node);
      }
      else if (type == RESOURCE_METHOD) {
        return new WitResourceMethodImpl(node);
      }
      else if (type == RESULT_ITEM) {
        return new WitResultItemImpl(node);
      }
      else if (type == RESULT_LIST) {
        return new WitResultListImpl(node);
      }
      else if (type == SEMVER) {
        return new WitSemverImpl(node);
      }
      else if (type == SINCE_GATE) {
        return new WitSinceGateImpl(node);
      }
      else if (type == STREAM_ITEM) {
        return new WitStreamItemImpl(node);
      }
      else if (type == TOPLEVEL_USE_ITEM) {
        return new WitToplevelUseItemImpl(node);
      }
      else if (type == TUPLE_DEFINITION) {
        return new WitTupleDefinitionImpl(node);
      }
      else if (type == TUPLE_LIST) {
        return new WitTupleListImpl(node);
      }
      else if (type == TY) {
        return new WitTyImpl(node);
      }
      else if (type == TYPE_ITEM) {
        return new WitTypeItemImpl(node);
      }
      else if (type == UINT) {
        return new WitUintImpl(node);
      }
      else if (type == UNSTABLE_GATE) {
        return new WitUnstableGateImpl(node);
      }
      else if (type == USE_ITEM) {
        return new WitUseItemImpl(node);
      }
      else if (type == USE_NAMES_ITEM) {
        return new WitUseNamesItemImpl(node);
      }
      else if (type == USE_NAMES_LIST) {
        return new WitUseNamesListImpl(node);
      }
      else if (type == USE_PATH) {
        return new WitUsePathImpl(node);
      }
      else if (type == VARIANT_CASE) {
        return new WitVariantCaseImpl(node);
      }
      else if (type == VARIANT_CASES) {
        return new WitVariantCasesImpl(node);
      }
      else if (type == VARIANT_ITEMS) {
        return new WitVariantItemsImpl(node);
      }
      else if (type == VERSION_FIELD) {
        return new WitVersionFieldImpl(node);
      }
      else if (type == WORLD_ITEM) {
        return new WitWorldItemImpl(node);
      }
      throw new AssertionError("Unknown element type: " + type);
    }
  }
}
