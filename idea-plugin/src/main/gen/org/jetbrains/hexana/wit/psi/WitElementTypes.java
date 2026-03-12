// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.PsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.hexana.wit.psi.impl.*;

public interface WitElementTypes {

  IElementType DEPRECATED_GATE = new WitElementType("DEPRECATED_GATE");
  IElementType ENUM_CASE = new WitElementType("ENUM_CASE");
  IElementType ENUM_DEFINITION = new WitElementType("ENUM_DEFINITION");
  IElementType EXPORT_DEFINITION = new WitElementType("EXPORT_DEFINITION");
  IElementType EXTERN_TYPE = new WitElementType("EXTERN_TYPE");
  IElementType FEATURE_FIELD = new WitElementType("FEATURE_FIELD");
  IElementType FLAGS_DEFINITION = new WitElementType("FLAGS_DEFINITION");
  IElementType FUNC_ITEM = new WitElementType("FUNC_ITEM");
  IElementType FUNC_TYPE = new WitElementType("FUNC_TYPE");
  IElementType FUTURE_ITEM = new WitElementType("FUTURE_ITEM");
  IElementType GATE_ITEM = new WitElementType("GATE_ITEM");
  IElementType HANDLE = new WitElementType("HANDLE");
  IElementType IMPORT_DEFINITION = new WitElementType("IMPORT_DEFINITION");
  IElementType INCLUDE_DECLARATION = new WitElementType("INCLUDE_DECLARATION");
  IElementType INCLUDE_NAMES_ITEM = new WitElementType("INCLUDE_NAMES_ITEM");
  IElementType INCLUDE_NAMES_LIST = new WitElementType("INCLUDE_NAMES_LIST");
  IElementType INTERFACE_DEFINITION = new WitElementType("INTERFACE_DEFINITION");
  IElementType LIST_DEFINITION = new WitElementType("LIST_DEFINITION");
  IElementType NAMED_TYPE = new WitElementType("NAMED_TYPE");
  IElementType NAMED_TYPE_LIST = new WitElementType("NAMED_TYPE_LIST");
  IElementType NESTED_PACKAGE_DEFINITION = new WitElementType("NESTED_PACKAGE_DEFINITION");
  IElementType OPTION_ITEM = new WitElementType("OPTION_ITEM");
  IElementType PACKAGE_DEFINITION = new WitElementType("PACKAGE_DEFINITION");
  IElementType PACKAGE_ITEM = new WitElementType("PACKAGE_ITEM");
  IElementType PACKAGE_NAME = new WitElementType("PACKAGE_NAME");
  IElementType PARAM_LIST = new WitElementType("PARAM_LIST");
  IElementType RECORD_DEFINITION = new WitElementType("RECORD_DEFINITION");
  IElementType RECORD_FIELD = new WitElementType("RECORD_FIELD");
  IElementType RESOURCE_DEFINITION = new WitElementType("RESOURCE_DEFINITION");
  IElementType RESOURCE_METHOD = new WitElementType("RESOURCE_METHOD");
  IElementType RESULT_ITEM = new WitElementType("RESULT_ITEM");
  IElementType RESULT_LIST = new WitElementType("RESULT_LIST");
  IElementType SEMVER = new WitElementType("SEMVER");
  IElementType SINCE_GATE = new WitElementType("SINCE_GATE");
  IElementType STATIC_FUNC_ITEM = new WitElementType("STATIC_FUNC_ITEM");
  IElementType STREAM_ITEM = new WitElementType("STREAM_ITEM");
  IElementType TOPLEVEL_USE_DECLARATION = new WitElementType("TOPLEVEL_USE_DECLARATION");
  IElementType TUPLE_DEFINITION = new WitElementType("TUPLE_DEFINITION");
  IElementType TUPLE_LIST = new WitElementType("TUPLE_LIST");
  IElementType TYPE_DECLARATION = new WitElementType("TYPE_DECLARATION");
  IElementType TYPE_DEFINITION = new WitElementType("TYPE_DEFINITION");
  IElementType TYPE_USAGE = new WitElementType("TYPE_USAGE");
  IElementType UINT = new WitElementType("UINT");
  IElementType UNSTABLE_GATE = new WitElementType("UNSTABLE_GATE");
  IElementType USE_DECLARATION = new WitElementType("USE_DECLARATION");
  IElementType USE_NAMES_ITEM = new WitElementType("USE_NAMES_ITEM");
  IElementType USE_NAMES_LIST = new WitElementType("USE_NAMES_LIST");
  IElementType USE_PATH = new WitElementType("USE_PATH");
  IElementType VARIANT_CASE = new WitElementType("VARIANT_CASE");
  IElementType VARIANT_DEFINITION = new WitElementType("VARIANT_DEFINITION");
  IElementType VERSION_FIELD = new WitElementType("VERSION_FIELD");
  IElementType WORLD_DEFINITION = new WitElementType("WORLD_DEFINITION");

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

  class Factory {
    public static PsiElement createElement(ASTNode node) {
      IElementType type = node.getElementType();
      if (type == DEPRECATED_GATE) {
        return new WitDeprecatedGateImpl(node);
      }
      else if (type == ENUM_CASE) {
        return new WitEnumCaseImpl(node);
      }
      else if (type == ENUM_DEFINITION) {
        return new WitEnumDefinitionImpl(node);
      }
      else if (type == EXPORT_DEFINITION) {
        return new WitExportDefinitionImpl(node);
      }
      else if (type == EXTERN_TYPE) {
        return new WitExternTypeImpl(node);
      }
      else if (type == FEATURE_FIELD) {
        return new WitFeatureFieldImpl(node);
      }
      else if (type == FLAGS_DEFINITION) {
        return new WitFlagsDefinitionImpl(node);
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
      else if (type == HANDLE) {
        return new WitHandleImpl(node);
      }
      else if (type == IMPORT_DEFINITION) {
        return new WitImportDefinitionImpl(node);
      }
      else if (type == INCLUDE_DECLARATION) {
        return new WitIncludeDeclarationImpl(node);
      }
      else if (type == INCLUDE_NAMES_ITEM) {
        return new WitIncludeNamesItemImpl(node);
      }
      else if (type == INCLUDE_NAMES_LIST) {
        return new WitIncludeNamesListImpl(node);
      }
      else if (type == INTERFACE_DEFINITION) {
        return new WitInterfaceDefinitionImpl(node);
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
      else if (type == RECORD_DEFINITION) {
        return new WitRecordDefinitionImpl(node);
      }
      else if (type == RECORD_FIELD) {
        return new WitRecordFieldImpl(node);
      }
      else if (type == RESOURCE_DEFINITION) {
        return new WitResourceDefinitionImpl(node);
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
      else if (type == STATIC_FUNC_ITEM) {
        return new WitStaticFuncItemImpl(node);
      }
      else if (type == STREAM_ITEM) {
        return new WitStreamItemImpl(node);
      }
      else if (type == TOPLEVEL_USE_DECLARATION) {
        return new WitToplevelUseDeclarationImpl(node);
      }
      else if (type == TUPLE_DEFINITION) {
        return new WitTupleDefinitionImpl(node);
      }
      else if (type == TUPLE_LIST) {
        return new WitTupleListImpl(node);
      }
      else if (type == TYPE_DEFINITION) {
        return new WitTypeDefinitionImpl(node);
      }
      else if (type == TYPE_USAGE) {
        return new WitTypeUsageImpl(node);
      }
      else if (type == UINT) {
        return new WitUintImpl(node);
      }
      else if (type == UNSTABLE_GATE) {
        return new WitUnstableGateImpl(node);
      }
      else if (type == USE_DECLARATION) {
        return new WitUseDeclarationImpl(node);
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
      else if (type == VARIANT_DEFINITION) {
        return new WitVariantDefinitionImpl(node);
      }
      else if (type == VERSION_FIELD) {
        return new WitVersionFieldImpl(node);
      }
      else if (type == WORLD_DEFINITION) {
        return new WitWorldDefinitionImpl(node);
      }
      throw new AssertionError("Unknown element type: " + type);
    }
  }
}
