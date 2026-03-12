// This is a generated file. Not intended for manual editing.
package org.jetbrains.hexana.wit.psi;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiBuilder.Marker;
import static org.jetbrains.hexana.wit.psi.WitElementTypes.*;
import static org.jetbrains.hexana.wit.psi.WitParserUtil.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.lang.ASTNode;
import com.intellij.psi.tree.TokenSet;
import com.intellij.lang.PsiParser;
import com.intellij.lang.LightPsiParser;

@SuppressWarnings({"SimplifiableIfStatement", "UnusedAssignment"})
public class WitParser implements PsiParser, LightPsiParser {

  public ASTNode parse(IElementType t, PsiBuilder b) {
    parseLight(t, b);
    return b.getTreeBuilt();
  }

  public void parseLight(IElementType t, PsiBuilder b) {
    boolean r;
    b = adapt_builder_(t, b, this, EXTENDS_SETS_);
    Marker m = enter_section_(b, 0, _COLLAPSE_, null);
    r = parse_root_(t, b);
    exit_section_(b, 0, m, t, r, true, TRUE_CONDITION);
  }

  protected boolean parse_root_(IElementType t, PsiBuilder b) {
    return parse_root_(t, b, 0);
  }

  static boolean parse_root_(IElementType t, PsiBuilder b, int l) {
    return wit(b, l + 1);
  }

  public static final TokenSet[] EXTENDS_SETS_ = new TokenSet[] {
    create_token_set_(DEPRECATED_GATE, GATE_ITEM, SINCE_GATE, UNSTABLE_GATE),
    create_token_set_(INTERFACE_DEFINITION, PACKAGE_ITEM, TOPLEVEL_USE_DECLARATION, WORLD_DEFINITION),
    create_token_set_(ENUM_DEFINITION, FLAGS_DEFINITION, RECORD_DEFINITION, RESOURCE_DEFINITION,
      TYPE_DECLARATION, TYPE_DEFINITION, VARIANT_DEFINITION),
  };

  /* ********************************************************** */
  // '@deprecated' '(' version-field ')'
  public static boolean deprecated_gate(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "deprecated_gate")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, DEPRECATED_GATE, "<deprecated gate>");
    r = consumeToken(b, "@deprecated");
    r = r && consumeToken(b, OPEN_PAREN);
    r = r && version_field(b, l + 1);
    r = r && consumeToken(b, CLOSE_PAREN);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER
  public static boolean enum_case(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "enum_case")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IDENTIFIER);
    exit_section_(b, m, ENUM_CASE, r);
    return r;
  }

  /* ********************************************************** */
  // enum-case (',' enum-case)* ','?
  static boolean enum_cases(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "enum_cases")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = enum_case(b, l + 1);
    r = r && enum_cases_1(b, l + 1);
    r = r && enum_cases_2(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (',' enum-case)*
  private static boolean enum_cases_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "enum_cases_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!enum_cases_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "enum_cases_1", c)) break;
    }
    return true;
  }

  // ',' enum-case
  private static boolean enum_cases_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "enum_cases_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, COMMA);
    r = r && enum_case(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // ','?
  private static boolean enum_cases_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "enum_cases_2")) return false;
    consumeToken(b, COMMA);
    return true;
  }

  /* ********************************************************** */
  // ENUM IDENTIFIER '{' enum-cases '}'
  public static boolean enum_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "enum_definition")) return false;
    if (!nextTokenIs(b, ENUM)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, ENUM, IDENTIFIER, OPEN_BRACE);
    r = r && enum_cases(b, l + 1);
    r = r && consumeToken(b, CLOSE_BRACE);
    exit_section_(b, m, ENUM_DEFINITION, r);
    return r;
  }

  /* ********************************************************** */
  // EXPORT IDENTIFIER ':' extern-type
  //               | EXPORT use-path ';'
  public static boolean export_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "export_definition")) return false;
    if (!nextTokenIs(b, EXPORT)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = export_definition_0(b, l + 1);
    if (!r) r = export_definition_1(b, l + 1);
    exit_section_(b, m, EXPORT_DEFINITION, r);
    return r;
  }

  // EXPORT IDENTIFIER ':' extern-type
  private static boolean export_definition_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "export_definition_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, EXPORT, IDENTIFIER, COLON);
    r = r && extern_type(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // EXPORT use-path ';'
  private static boolean export_definition_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "export_definition_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, EXPORT);
    r = r && use_path(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // INTERFACE '{' interface-items* '}'
  static boolean extern_interface(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "extern_interface")) return false;
    if (!nextTokenIs(b, INTERFACE)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_);
    r = consumeTokens(b, 1, INTERFACE, OPEN_BRACE);
    p = r; // pin = 1
    r = r && report_error_(b, extern_interface_2(b, l + 1));
    r = p && consumeToken(b, CLOSE_BRACE) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // interface-items*
  private static boolean extern_interface_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "extern_interface_2")) return false;
    while (true) {
      int c = current_position_(b);
      if (!interface_items(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "extern_interface_2", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // func-type ';' | extern-interface
  public static boolean extern_type(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "extern_type")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, EXTERN_TYPE, "<extern type>");
    r = extern_type_0(b, l + 1);
    if (!r) r = extern_interface(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // func-type ';'
  private static boolean extern_type_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "extern_type_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = func_type(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // 'feature' '=' IDENTIFIER
  public static boolean feature_field(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "feature_field")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, FEATURE_FIELD, "<feature field>");
    r = consumeToken(b, "feature");
    r = r && consumeTokens(b, 0, EQUALS, IDENTIFIER);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // FLAGS IDENTIFIER '{' flags-fields '}'
  public static boolean flags_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "flags_definition")) return false;
    if (!nextTokenIs(b, FLAGS)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, FLAGS, IDENTIFIER, OPEN_BRACE);
    r = r && flags_fields(b, l + 1);
    r = r && consumeToken(b, CLOSE_BRACE);
    exit_section_(b, m, FLAGS_DEFINITION, r);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER (',' IDENTIFIER)* ','?
  static boolean flags_fields(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "flags_fields")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IDENTIFIER);
    r = r && flags_fields_1(b, l + 1);
    r = r && flags_fields_2(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (',' IDENTIFIER)*
  private static boolean flags_fields_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "flags_fields_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!flags_fields_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "flags_fields_1", c)) break;
    }
    return true;
  }

  // ',' IDENTIFIER
  private static boolean flags_fields_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "flags_fields_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, COMMA, IDENTIFIER);
    exit_section_(b, m, null, r);
    return r;
  }

  // ','?
  private static boolean flags_fields_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "flags_fields_2")) return false;
    consumeToken(b, COMMA);
    return true;
  }

  /* ********************************************************** */
  // IDENTIFIER ':' func-type ';'
  public static boolean func_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "func_item")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, IDENTIFIER, COLON);
    r = r && func_type(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, FUNC_ITEM, r);
    return r;
  }

  /* ********************************************************** */
  // ASYNC? FUNC param-list result-list?
  public static boolean func_type(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "func_type")) return false;
    if (!nextTokenIs(b, "<func type>", ASYNC, FUNC)) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, FUNC_TYPE, "<func type>");
    r = func_type_0(b, l + 1);
    r = r && consumeToken(b, FUNC);
    r = r && param_list(b, l + 1);
    r = r && func_type_3(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // ASYNC?
  private static boolean func_type_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "func_type_0")) return false;
    consumeToken(b, ASYNC);
    return true;
  }

  // result-list?
  private static boolean func_type_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "func_type_3")) return false;
    result_list(b, l + 1);
    return true;
  }

  /* ********************************************************** */
  // FUTURE '<' type-usage '>' | FUTURE
  public static boolean future_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "future_item")) return false;
    if (!nextTokenIs(b, FUTURE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = future_item_0(b, l + 1);
    if (!r) r = consumeToken(b, FUTURE);
    exit_section_(b, m, FUTURE_ITEM, r);
    return r;
  }

  // FUTURE '<' type-usage '>'
  private static boolean future_item_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "future_item_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, FUTURE, LESS_THAN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // gate-item+
  static boolean gate(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "gate")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = gate_item(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!gate_item(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "gate", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // unstable-gate
  //             | since-gate
  //             | deprecated-gate
  public static boolean gate_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "gate_item")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _COLLAPSE_, GATE_ITEM, "<gate item>");
    r = unstable_gate(b, l + 1);
    if (!r) r = since_gate(b, l + 1);
    if (!r) r = deprecated_gate(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER | BORROW '<' IDENTIFIER '>'
  public static boolean handle(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "handle")) return false;
    if (!nextTokenIs(b, "<handle>", BORROW, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, HANDLE, "<handle>");
    r = consumeToken(b, IDENTIFIER);
    if (!r) r = parseTokens(b, 0, BORROW, LESS_THAN, IDENTIFIER, GREATER_THAN);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // IMPORT IDENTIFIER ':' extern-type
  //               | IMPORT use-path ';'
  public static boolean import_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "import_definition")) return false;
    if (!nextTokenIs(b, IMPORT)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = import_definition_0(b, l + 1);
    if (!r) r = import_definition_1(b, l + 1);
    exit_section_(b, m, IMPORT_DEFINITION, r);
    return r;
  }

  // IMPORT IDENTIFIER ':' extern-type
  private static boolean import_definition_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "import_definition_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, IMPORT, IDENTIFIER, COLON);
    r = r && extern_type(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // IMPORT use-path ';'
  private static boolean import_definition_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "import_definition_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IMPORT);
    r = r && use_path(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // INCLUDE use-path ';'
  //                | INCLUDE use-path 'with' '{' include-names-list '}'
  public static boolean include_declaration(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "include_declaration")) return false;
    if (!nextTokenIs(b, INCLUDE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = include_declaration_0(b, l + 1);
    if (!r) r = include_declaration_1(b, l + 1);
    exit_section_(b, m, INCLUDE_DECLARATION, r);
    return r;
  }

  // INCLUDE use-path ';'
  private static boolean include_declaration_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "include_declaration_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, INCLUDE);
    r = r && use_path(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, null, r);
    return r;
  }

  // INCLUDE use-path 'with' '{' include-names-list '}'
  private static boolean include_declaration_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "include_declaration_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, INCLUDE);
    r = r && use_path(b, l + 1);
    r = r && consumeToken(b, "with");
    r = r && consumeToken(b, OPEN_BRACE);
    r = r && include_names_list(b, l + 1);
    r = r && consumeToken(b, CLOSE_BRACE);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER 'as' IDENTIFIER
  public static boolean include_names_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "include_names_item")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IDENTIFIER);
    r = r && consumeToken(b, "as");
    r = r && consumeToken(b, IDENTIFIER);
    exit_section_(b, m, INCLUDE_NAMES_ITEM, r);
    return r;
  }

  /* ********************************************************** */
  // include-names-item (',' include-names-item)*
  public static boolean include_names_list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "include_names_list")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = include_names_item(b, l + 1);
    r = r && include_names_list_1(b, l + 1);
    exit_section_(b, m, INCLUDE_NAMES_LIST, r);
    return r;
  }

  // (',' include-names-item)*
  private static boolean include_names_list_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "include_names_list_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!include_names_list_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "include_names_list_1", c)) break;
    }
    return true;
  }

  // ',' include-names-item
  private static boolean include_names_list_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "include_names_list_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, COMMA);
    r = r && include_names_item(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // gate? INTERFACE IDENTIFIER '{' interface-items* '}'
  public static boolean interface_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_definition")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, INTERFACE_DEFINITION, "<interface definition>");
    r = interface_definition_0(b, l + 1);
    r = r && consumeTokens(b, 1, INTERFACE, IDENTIFIER, OPEN_BRACE);
    p = r; // pin = 2
    r = r && report_error_(b, interface_definition_4(b, l + 1));
    r = p && consumeToken(b, CLOSE_BRACE) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // gate?
  private static boolean interface_definition_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_definition_0")) return false;
    gate(b, l + 1);
    return true;
  }

  // interface-items*
  private static boolean interface_definition_4(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_definition_4")) return false;
    while (true) {
      int c = current_position_(b);
      if (!interface_items(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "interface_definition_4", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // type-declaration
  //                        | use-declaration
  //                        | func-item
  static boolean interface_item_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_item_definition")) return false;
    boolean r;
    r = type_declaration(b, l + 1);
    if (!r) r = use_declaration(b, l + 1);
    if (!r) r = func_item(b, l + 1);
    return r;
  }

  /* ********************************************************** */
  // gate? interface-item-definition
  static boolean interface_items(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_items")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_);
    r = interface_items_0(b, l + 1);
    r = r && interface_item_definition(b, l + 1);
    exit_section_(b, l, m, r, false, WitParser::interface_recovery);
    return r;
  }

  // gate?
  private static boolean interface_items_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_items_0")) return false;
    gate(b, l + 1);
    return true;
  }

  /* ********************************************************** */
  // !( '}' | '@' | ';' | ENUM | FLAGS | RECORD | RESOURCE | TYPE | VARIANT | USE | (IDENTIFIER ':') )
  static boolean interface_recovery(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_recovery")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !interface_recovery_0(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // '}' | '@' | ';' | ENUM | FLAGS | RECORD | RESOURCE | TYPE | VARIANT | USE | (IDENTIFIER ':')
  private static boolean interface_recovery_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_recovery_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, CLOSE_BRACE);
    if (!r) r = consumeToken(b, AT);
    if (!r) r = consumeToken(b, SEMI);
    if (!r) r = consumeToken(b, ENUM);
    if (!r) r = consumeToken(b, FLAGS);
    if (!r) r = consumeToken(b, RECORD);
    if (!r) r = consumeToken(b, RESOURCE);
    if (!r) r = consumeToken(b, TYPE);
    if (!r) r = consumeToken(b, VARIANT);
    if (!r) r = consumeToken(b, USE);
    if (!r) r = interface_recovery_0_10(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // IDENTIFIER ':'
  private static boolean interface_recovery_0_10(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "interface_recovery_0_10")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, IDENTIFIER, COLON);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // LIST '<' type-usage '>'
  //        | LIST '<' type-usage ',' uint '>'
  public static boolean list_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "list_definition")) return false;
    if (!nextTokenIs(b, LIST)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = list_definition_0(b, l + 1);
    if (!r) r = list_definition_1(b, l + 1);
    exit_section_(b, m, LIST_DEFINITION, r);
    return r;
  }

  // LIST '<' type-usage '>'
  private static boolean list_definition_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "list_definition_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, LIST, LESS_THAN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, null, r);
    return r;
  }

  // LIST '<' type-usage ',' uint '>'
  private static boolean list_definition_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "list_definition_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, LIST, LESS_THAN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, COMMA);
    r = r && uint(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER ':' type-usage
  public static boolean named_type(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "named_type")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, IDENTIFIER, COLON);
    r = r && type_usage(b, l + 1);
    exit_section_(b, m, NAMED_TYPE, r);
    return r;
  }

  /* ********************************************************** */
  // named-type ( ',' named-type )* ','?
  public static boolean named_type_list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "named_type_list")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = named_type(b, l + 1);
    r = r && named_type_list_1(b, l + 1);
    r = r && named_type_list_2(b, l + 1);
    exit_section_(b, m, NAMED_TYPE_LIST, r);
    return r;
  }

  // ( ',' named-type )*
  private static boolean named_type_list_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "named_type_list_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!named_type_list_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "named_type_list_1", c)) break;
    }
    return true;
  }

  // ',' named-type
  private static boolean named_type_list_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "named_type_list_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, COMMA);
    r = r && named_type(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // ','?
  private static boolean named_type_list_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "named_type_list_2")) return false;
    consumeToken(b, COMMA);
    return true;
  }

  /* ********************************************************** */
  // package-decl '{' package-item* '}'
  public static boolean nested_package_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "nested_package_definition")) return false;
    if (!nextTokenIs(b, PACKAGE)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, NESTED_PACKAGE_DEFINITION, null);
    r = package_decl(b, l + 1);
    r = r && consumeToken(b, OPEN_BRACE);
    p = r; // pin = 2
    r = r && report_error_(b, nested_package_definition_2(b, l + 1));
    r = p && consumeToken(b, CLOSE_BRACE) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // package-item*
  private static boolean nested_package_definition_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "nested_package_definition_2")) return false;
    while (true) {
      int c = current_position_(b);
      if (!package_item(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "nested_package_definition_2", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // OPTION '<' type-usage '>'
  public static boolean option_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "option_item")) return false;
    if (!nextTokenIs(b, OPTION)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, OPTION, LESS_THAN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, OPTION_ITEM, r);
    return r;
  }

  /* ********************************************************** */
  // PACKAGE package-name
  static boolean package_decl(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_decl")) return false;
    if (!nextTokenIs(b, PACKAGE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, PACKAGE);
    r = r && package_name(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // package-decl ';'
  public static boolean package_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_definition")) return false;
    if (!nextTokenIs(b, PACKAGE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = package_decl(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, PACKAGE_DEFINITION, r);
    return r;
  }

  /* ********************************************************** */
  // toplevel-use-declaration | interface-definition | world-definition
  public static boolean package_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_item")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _COLLAPSE_, PACKAGE_ITEM, "<package item>");
    r = toplevel_use_declaration(b, l + 1);
    if (!r) r = interface_definition(b, l + 1);
    if (!r) r = world_definition(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // (IDENTIFIER !<<white_space>> ':' !<<white_space>>)+ IDENTIFIER ( !<<white_space>> '/' !<<white_space>> IDENTIFIER )* (!<<white_space>> '@' !<<white_space>> semver)?
  public static boolean package_name(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, PACKAGE_NAME, null);
    r = package_name_0(b, l + 1);
    r = r && consumeToken(b, IDENTIFIER);
    p = r; // pin = 2
    r = r && report_error_(b, package_name_2(b, l + 1));
    r = p && package_name_3(b, l + 1) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // (IDENTIFIER !<<white_space>> ':' !<<white_space>>)+
  private static boolean package_name_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = package_name_0_0(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!package_name_0_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "package_name_0", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  // IDENTIFIER !<<white_space>> ':' !<<white_space>>
  private static boolean package_name_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IDENTIFIER);
    r = r && package_name_0_0_1(b, l + 1);
    r = r && consumeToken(b, COLON);
    r = r && package_name_0_0_3(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // !<<white_space>>
  private static boolean package_name_0_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_0_0_1")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean package_name_0_0_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_0_0_3")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // ( !<<white_space>> '/' !<<white_space>> IDENTIFIER )*
  private static boolean package_name_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_2")) return false;
    while (true) {
      int c = current_position_(b);
      if (!package_name_2_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "package_name_2", c)) break;
    }
    return true;
  }

  // !<<white_space>> '/' !<<white_space>> IDENTIFIER
  private static boolean package_name_2_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_2_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = package_name_2_0_0(b, l + 1);
    r = r && consumeToken(b, SLASH);
    r = r && package_name_2_0_2(b, l + 1);
    r = r && consumeToken(b, IDENTIFIER);
    exit_section_(b, m, null, r);
    return r;
  }

  // !<<white_space>>
  private static boolean package_name_2_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_2_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean package_name_2_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_2_0_2")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // (!<<white_space>> '@' !<<white_space>> semver)?
  private static boolean package_name_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_3")) return false;
    package_name_3_0(b, l + 1);
    return true;
  }

  // !<<white_space>> '@' !<<white_space>> semver
  private static boolean package_name_3_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_3_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = package_name_3_0_0(b, l + 1);
    r = r && consumeToken(b, AT);
    r = r && package_name_3_0_2(b, l + 1);
    r = r && semver(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // !<<white_space>>
  private static boolean package_name_3_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_3_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean package_name_3_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "package_name_3_0_2")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // '(' named-type-list? ')'
  public static boolean param_list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "param_list")) return false;
    if (!nextTokenIs(b, OPEN_PAREN)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, OPEN_PAREN);
    r = r && param_list_1(b, l + 1);
    r = r && consumeToken(b, CLOSE_PAREN);
    exit_section_(b, m, PARAM_LIST, r);
    return r;
  }

  // named-type-list?
  private static boolean param_list_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "param_list_1")) return false;
    named_type_list(b, l + 1);
    return true;
  }

  /* ********************************************************** */
  // RECORD IDENTIFIER '{' record-fields '}'
  public static boolean record_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "record_definition")) return false;
    if (!nextTokenIs(b, RECORD)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, RECORD, IDENTIFIER, OPEN_BRACE);
    r = r && record_fields(b, l + 1);
    r = r && consumeToken(b, CLOSE_BRACE);
    exit_section_(b, m, RECORD_DEFINITION, r);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER ':' type-usage
  public static boolean record_field(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "record_field")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, IDENTIFIER, COLON);
    r = r && type_usage(b, l + 1);
    exit_section_(b, m, RECORD_FIELD, r);
    return r;
  }

  /* ********************************************************** */
  // record-field (',' record-field)* ','?
  static boolean record_fields(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "record_fields")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = record_field(b, l + 1);
    r = r && record_fields_1(b, l + 1);
    r = r && record_fields_2(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (',' record-field)*
  private static boolean record_fields_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "record_fields_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!record_fields_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "record_fields_1", c)) break;
    }
    return true;
  }

  // ',' record-field
  private static boolean record_fields_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "record_fields_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, COMMA);
    r = r && record_field(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // ','?
  private static boolean record_fields_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "record_fields_2")) return false;
    consumeToken(b, COMMA);
    return true;
  }

  /* ********************************************************** */
  // RESOURCE IDENTIFIER ';'
  //                 | RESOURCE IDENTIFIER '{' resource-method* '}'
  public static boolean resource_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "resource_definition")) return false;
    if (!nextTokenIs(b, RESOURCE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = parseTokens(b, 0, RESOURCE, IDENTIFIER, SEMI);
    if (!r) r = resource_definition_1(b, l + 1);
    exit_section_(b, m, RESOURCE_DEFINITION, r);
    return r;
  }

  // RESOURCE IDENTIFIER '{' resource-method* '}'
  private static boolean resource_definition_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "resource_definition_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, RESOURCE, IDENTIFIER, OPEN_BRACE);
    r = r && resource_definition_1_3(b, l + 1);
    r = r && consumeToken(b, CLOSE_BRACE);
    exit_section_(b, m, null, r);
    return r;
  }

  // resource-method*
  private static boolean resource_definition_1_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "resource_definition_1_3")) return false;
    while (true) {
      int c = current_position_(b);
      if (!resource_method(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "resource_definition_1_3", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // gate? (func-item
  //                   | static-func-item
  //                   | CONSTRUCTOR param-list ';')
  public static boolean resource_method(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "resource_method")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, RESOURCE_METHOD, "<resource method>");
    r = resource_method_0(b, l + 1);
    r = r && resource_method_1(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // gate?
  private static boolean resource_method_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "resource_method_0")) return false;
    gate(b, l + 1);
    return true;
  }

  // func-item
  //                   | static-func-item
  //                   | CONSTRUCTOR param-list ';'
  private static boolean resource_method_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "resource_method_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = func_item(b, l + 1);
    if (!r) r = static_func_item(b, l + 1);
    if (!r) r = resource_method_1_2(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // CONSTRUCTOR param-list ';'
  private static boolean resource_method_1_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "resource_method_1_2")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, CONSTRUCTOR);
    r = r && param_list(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // RESULT '<' type-usage ',' type-usage '>'
  //          | RESULT '<' '_' ',' type-usage '>'
  //          | RESULT '<' type-usage '>'
  //          | RESULT
  public static boolean result_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "result_item")) return false;
    if (!nextTokenIs(b, RESULT)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = result_item_0(b, l + 1);
    if (!r) r = result_item_1(b, l + 1);
    if (!r) r = result_item_2(b, l + 1);
    if (!r) r = consumeToken(b, RESULT);
    exit_section_(b, m, RESULT_ITEM, r);
    return r;
  }

  // RESULT '<' type-usage ',' type-usage '>'
  private static boolean result_item_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "result_item_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, RESULT, LESS_THAN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, COMMA);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, null, r);
    return r;
  }

  // RESULT '<' '_' ',' type-usage '>'
  private static boolean result_item_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "result_item_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, RESULT, LESS_THAN, UNDERSCORE, COMMA);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, null, r);
    return r;
  }

  // RESULT '<' type-usage '>'
  private static boolean result_item_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "result_item_2")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, RESULT, LESS_THAN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // '->' type-usage
  public static boolean result_list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "result_list")) return false;
    if (!nextTokenIs(b, ARROW)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, ARROW);
    r = r && type_usage(b, l + 1);
    exit_section_(b, m, RESULT_LIST, r);
    return r;
  }

  /* ********************************************************** */
  // NUMBER !<<white_space>> DOT !<<white_space>> NUMBER
  //      DOT !<<white_space>> NUMBER
  //      ('-' (NUMBER | IDENTIFIER)+ (DOT (NUMBER | IDENTIFIER)+)*)?
  //      ('+' (NUMBER | IDENTIFIER)+ (DOT (NUMBER | IDENTIFIER)+)*)?
  public static boolean semver(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver")) return false;
    if (!nextTokenIs(b, NUMBER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, NUMBER);
    r = r && semver_1(b, l + 1);
    r = r && consumeToken(b, DOT);
    r = r && semver_3(b, l + 1);
    r = r && consumeTokens(b, 0, NUMBER, DOT);
    r = r && semver_6(b, l + 1);
    r = r && consumeToken(b, NUMBER);
    r = r && semver_8(b, l + 1);
    r = r && semver_9(b, l + 1);
    exit_section_(b, m, SEMVER, r);
    return r;
  }

  // !<<white_space>>
  private static boolean semver_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_1")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean semver_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_3")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean semver_6(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_6")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // ('-' (NUMBER | IDENTIFIER)+ (DOT (NUMBER | IDENTIFIER)+)*)?
  private static boolean semver_8(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8")) return false;
    semver_8_0(b, l + 1);
    return true;
  }

  // '-' (NUMBER | IDENTIFIER)+ (DOT (NUMBER | IDENTIFIER)+)*
  private static boolean semver_8_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, "-");
    r = r && semver_8_0_1(b, l + 1);
    r = r && semver_8_0_2(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (NUMBER | IDENTIFIER)+
  private static boolean semver_8_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8_0_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = semver_8_0_1_0(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!semver_8_0_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "semver_8_0_1", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  // NUMBER | IDENTIFIER
  private static boolean semver_8_0_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8_0_1_0")) return false;
    boolean r;
    r = consumeToken(b, NUMBER);
    if (!r) r = consumeToken(b, IDENTIFIER);
    return r;
  }

  // (DOT (NUMBER | IDENTIFIER)+)*
  private static boolean semver_8_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8_0_2")) return false;
    while (true) {
      int c = current_position_(b);
      if (!semver_8_0_2_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "semver_8_0_2", c)) break;
    }
    return true;
  }

  // DOT (NUMBER | IDENTIFIER)+
  private static boolean semver_8_0_2_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8_0_2_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, DOT);
    r = r && semver_8_0_2_0_1(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (NUMBER | IDENTIFIER)+
  private static boolean semver_8_0_2_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8_0_2_0_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = semver_8_0_2_0_1_0(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!semver_8_0_2_0_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "semver_8_0_2_0_1", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  // NUMBER | IDENTIFIER
  private static boolean semver_8_0_2_0_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_8_0_2_0_1_0")) return false;
    boolean r;
    r = consumeToken(b, NUMBER);
    if (!r) r = consumeToken(b, IDENTIFIER);
    return r;
  }

  // ('+' (NUMBER | IDENTIFIER)+ (DOT (NUMBER | IDENTIFIER)+)*)?
  private static boolean semver_9(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9")) return false;
    semver_9_0(b, l + 1);
    return true;
  }

  // '+' (NUMBER | IDENTIFIER)+ (DOT (NUMBER | IDENTIFIER)+)*
  private static boolean semver_9_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, "+");
    r = r && semver_9_0_1(b, l + 1);
    r = r && semver_9_0_2(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (NUMBER | IDENTIFIER)+
  private static boolean semver_9_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9_0_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = semver_9_0_1_0(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!semver_9_0_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "semver_9_0_1", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  // NUMBER | IDENTIFIER
  private static boolean semver_9_0_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9_0_1_0")) return false;
    boolean r;
    r = consumeToken(b, NUMBER);
    if (!r) r = consumeToken(b, IDENTIFIER);
    return r;
  }

  // (DOT (NUMBER | IDENTIFIER)+)*
  private static boolean semver_9_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9_0_2")) return false;
    while (true) {
      int c = current_position_(b);
      if (!semver_9_0_2_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "semver_9_0_2", c)) break;
    }
    return true;
  }

  // DOT (NUMBER | IDENTIFIER)+
  private static boolean semver_9_0_2_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9_0_2_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, DOT);
    r = r && semver_9_0_2_0_1(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (NUMBER | IDENTIFIER)+
  private static boolean semver_9_0_2_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9_0_2_0_1")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = semver_9_0_2_0_1_0(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!semver_9_0_2_0_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "semver_9_0_2_0_1", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  // NUMBER | IDENTIFIER
  private static boolean semver_9_0_2_0_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "semver_9_0_2_0_1_0")) return false;
    boolean r;
    r = consumeToken(b, NUMBER);
    if (!r) r = consumeToken(b, IDENTIFIER);
    return r;
  }

  /* ********************************************************** */
  // '@since' '(' version-field ')'
  public static boolean since_gate(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "since_gate")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, SINCE_GATE, "<since gate>");
    r = consumeToken(b, "@since");
    r = r && consumeToken(b, OPEN_PAREN);
    r = r && version_field(b, l + 1);
    r = r && consumeToken(b, CLOSE_PAREN);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER ':' STATIC func-type ';'
  public static boolean static_func_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "static_func_item")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, IDENTIFIER, COLON, STATIC);
    r = r && func_type(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, STATIC_FUNC_ITEM, r);
    return r;
  }

  /* ********************************************************** */
  // STREAM '<' type-usage '>' | STREAM
  public static boolean stream_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "stream_item")) return false;
    if (!nextTokenIs(b, STREAM)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = stream_item_0(b, l + 1);
    if (!r) r = consumeToken(b, STREAM);
    exit_section_(b, m, STREAM_ITEM, r);
    return r;
  }

  // STREAM '<' type-usage '>'
  private static boolean stream_item_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "stream_item_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, STREAM, LESS_THAN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // USE use-path ('as' IDENTIFIER)? ';'
  public static boolean toplevel_use_declaration(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "toplevel_use_declaration")) return false;
    if (!nextTokenIs(b, USE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, USE);
    r = r && use_path(b, l + 1);
    r = r && toplevel_use_declaration_2(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, TOPLEVEL_USE_DECLARATION, r);
    return r;
  }

  // ('as' IDENTIFIER)?
  private static boolean toplevel_use_declaration_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "toplevel_use_declaration_2")) return false;
    toplevel_use_declaration_2_0(b, l + 1);
    return true;
  }

  // 'as' IDENTIFIER
  private static boolean toplevel_use_declaration_2_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "toplevel_use_declaration_2_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, "as");
    r = r && consumeToken(b, IDENTIFIER);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // TUPLE '<' tuple-list '>'
  public static boolean tuple_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "tuple_definition")) return false;
    if (!nextTokenIs(b, TUPLE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, TUPLE, LESS_THAN);
    r = r && tuple_list(b, l + 1);
    r = r && consumeToken(b, GREATER_THAN);
    exit_section_(b, m, TUPLE_DEFINITION, r);
    return r;
  }

  /* ********************************************************** */
  // type-usage (',' type-usage)* ','?
  public static boolean tuple_list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "tuple_list")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, TUPLE_LIST, "<tuple list>");
    r = type_usage(b, l + 1);
    r = r && tuple_list_1(b, l + 1);
    r = r && tuple_list_2(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // (',' type-usage)*
  private static boolean tuple_list_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "tuple_list_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!tuple_list_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "tuple_list_1", c)) break;
    }
    return true;
  }

  // ',' type-usage
  private static boolean tuple_list_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "tuple_list_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, COMMA);
    r = r && type_usage(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // ','?
  private static boolean tuple_list_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "tuple_list_2")) return false;
    consumeToken(b, COMMA);
    return true;
  }

  /* ********************************************************** */
  // resource-definition
  //                | variant-definition
  //                | record-definition
  //                | flags-definition
  //                | enum-definition
  //                | type-definition
  public static boolean type_declaration(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "type_declaration")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _COLLAPSE_, TYPE_DECLARATION, "<type declaration>");
    r = resource_definition(b, l + 1);
    if (!r) r = variant_definition(b, l + 1);
    if (!r) r = record_definition(b, l + 1);
    if (!r) r = flags_definition(b, l + 1);
    if (!r) r = enum_definition(b, l + 1);
    if (!r) r = type_definition(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // TYPE IDENTIFIER '=' type-usage ';'
  public static boolean type_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "type_definition")) return false;
    if (!nextTokenIs(b, TYPE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, TYPE, IDENTIFIER, EQUALS);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, SEMI);
    exit_section_(b, m, TYPE_DEFINITION, r);
    return r;
  }

  /* ********************************************************** */
  // 'u8' | 'u16' | 'u32' | 'u64'
  //      | 's8' | 's16' | 's32' | 's64'
  //      | 'f32' | 'f64'
  //      | 'char'
  //      | 'bool'
  //      | 'string'
  //      | tuple-definition
  //      | list-definition
  //      | option-item
  //      | result-item
  //      | handle
  //      | future-item
  //      | stream-item
  //      | IDENTIFIER
  public static boolean type_usage(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "type_usage")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, TYPE_USAGE, "<type usage>");
    r = consumeToken(b, "u8");
    if (!r) r = consumeToken(b, "u16");
    if (!r) r = consumeToken(b, "u32");
    if (!r) r = consumeToken(b, "u64");
    if (!r) r = consumeToken(b, "s8");
    if (!r) r = consumeToken(b, "s16");
    if (!r) r = consumeToken(b, "s32");
    if (!r) r = consumeToken(b, "s64");
    if (!r) r = consumeToken(b, "f32");
    if (!r) r = consumeToken(b, "f64");
    if (!r) r = consumeToken(b, "char");
    if (!r) r = consumeToken(b, "bool");
    if (!r) r = consumeToken(b, "string");
    if (!r) r = tuple_definition(b, l + 1);
    if (!r) r = list_definition(b, l + 1);
    if (!r) r = option_item(b, l + 1);
    if (!r) r = result_item(b, l + 1);
    if (!r) r = handle(b, l + 1);
    if (!r) r = future_item(b, l + 1);
    if (!r) r = stream_item(b, l + 1);
    if (!r) r = consumeToken(b, IDENTIFIER);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // NUMBER
  public static boolean uint(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "uint")) return false;
    if (!nextTokenIs(b, NUMBER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, NUMBER);
    exit_section_(b, m, UINT, r);
    return r;
  }

  /* ********************************************************** */
  // '@unstable' '(' feature-field ')'
  public static boolean unstable_gate(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "unstable_gate")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, UNSTABLE_GATE, "<unstable gate>");
    r = consumeToken(b, "@unstable");
    r = r && consumeToken(b, OPEN_PAREN);
    r = r && feature_field(b, l + 1);
    r = r && consumeToken(b, CLOSE_PAREN);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // USE use-path '.' '{' use-names-list '}' ';'
  public static boolean use_declaration(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_declaration")) return false;
    if (!nextTokenIs(b, USE)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, USE);
    r = r && use_path(b, l + 1);
    r = r && consumeTokens(b, 0, DOT, OPEN_BRACE);
    r = r && use_names_list(b, l + 1);
    r = r && consumeTokens(b, 0, CLOSE_BRACE, SEMI);
    exit_section_(b, m, USE_DECLARATION, r);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER 'as' IDENTIFIER | IDENTIFIER
  public static boolean use_names_item(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_names_item")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_names_item_0(b, l + 1);
    if (!r) r = consumeToken(b, IDENTIFIER);
    exit_section_(b, m, USE_NAMES_ITEM, r);
    return r;
  }

  // IDENTIFIER 'as' IDENTIFIER
  private static boolean use_names_item_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_names_item_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IDENTIFIER);
    r = r && consumeToken(b, "as");
    r = r && consumeToken(b, IDENTIFIER);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // use-names-item (',' use-names-item)* ','?
  public static boolean use_names_list(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_names_list")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_names_item(b, l + 1);
    r = r && use_names_list_1(b, l + 1);
    r = r && use_names_list_2(b, l + 1);
    exit_section_(b, m, USE_NAMES_LIST, r);
    return r;
  }

  // (',' use-names-item)*
  private static boolean use_names_list_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_names_list_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!use_names_list_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "use_names_list_1", c)) break;
    }
    return true;
  }

  // ',' use-names-item
  private static boolean use_names_list_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_names_list_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, COMMA);
    r = r && use_names_item(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // ','?
  private static boolean use_names_list_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_names_list_2")) return false;
    consumeToken(b, COMMA);
    return true;
  }

  /* ********************************************************** */
  // ( IDENTIFIER !<<white_space>> ':' !<<white_space>> )+ IDENTIFIER ( !<<white_space>> '/' !<<white_space>> IDENTIFIER )+ (!<<white_space>> '@' !<<white_space>> semver)?
  //  | IDENTIFIER
  public static boolean use_path(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_path_0(b, l + 1);
    if (!r) r = consumeToken(b, IDENTIFIER);
    exit_section_(b, m, USE_PATH, r);
    return r;
  }

  // ( IDENTIFIER !<<white_space>> ':' !<<white_space>> )+ IDENTIFIER ( !<<white_space>> '/' !<<white_space>> IDENTIFIER )+ (!<<white_space>> '@' !<<white_space>> semver)?
  private static boolean use_path_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_path_0_0(b, l + 1);
    r = r && consumeToken(b, IDENTIFIER);
    r = r && use_path_0_2(b, l + 1);
    r = r && use_path_0_3(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // ( IDENTIFIER !<<white_space>> ':' !<<white_space>> )+
  private static boolean use_path_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_path_0_0_0(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!use_path_0_0_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "use_path_0_0", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  // IDENTIFIER !<<white_space>> ':' !<<white_space>>
  private static boolean use_path_0_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, IDENTIFIER);
    r = r && use_path_0_0_0_1(b, l + 1);
    r = r && consumeToken(b, COLON);
    r = r && use_path_0_0_0_3(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // !<<white_space>>
  private static boolean use_path_0_0_0_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_0_0_1")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean use_path_0_0_0_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_0_0_3")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // ( !<<white_space>> '/' !<<white_space>> IDENTIFIER )+
  private static boolean use_path_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_2")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_path_0_2_0(b, l + 1);
    while (r) {
      int c = current_position_(b);
      if (!use_path_0_2_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "use_path_0_2", c)) break;
    }
    exit_section_(b, m, null, r);
    return r;
  }

  // !<<white_space>> '/' !<<white_space>> IDENTIFIER
  private static boolean use_path_0_2_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_2_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_path_0_2_0_0(b, l + 1);
    r = r && consumeToken(b, SLASH);
    r = r && use_path_0_2_0_2(b, l + 1);
    r = r && consumeToken(b, IDENTIFIER);
    exit_section_(b, m, null, r);
    return r;
  }

  // !<<white_space>>
  private static boolean use_path_0_2_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_2_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean use_path_0_2_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_2_0_2")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // (!<<white_space>> '@' !<<white_space>> semver)?
  private static boolean use_path_0_3(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_3")) return false;
    use_path_0_3_0(b, l + 1);
    return true;
  }

  // !<<white_space>> '@' !<<white_space>> semver
  private static boolean use_path_0_3_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_3_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = use_path_0_3_0_0(b, l + 1);
    r = r && consumeToken(b, AT);
    r = r && use_path_0_3_0_2(b, l + 1);
    r = r && semver(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // !<<white_space>>
  private static boolean use_path_0_3_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_3_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // !<<white_space>>
  private static boolean use_path_0_3_0_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "use_path_0_3_0_2")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !white_space(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // IDENTIFIER '(' type-usage ')' | IDENTIFIER
  public static boolean variant_case(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "variant_case")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = variant_case_0(b, l + 1);
    if (!r) r = consumeToken(b, IDENTIFIER);
    exit_section_(b, m, VARIANT_CASE, r);
    return r;
  }

  // IDENTIFIER '(' type-usage ')'
  private static boolean variant_case_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "variant_case_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, IDENTIFIER, OPEN_PAREN);
    r = r && type_usage(b, l + 1);
    r = r && consumeToken(b, CLOSE_PAREN);
    exit_section_(b, m, null, r);
    return r;
  }

  /* ********************************************************** */
  // variant-case (',' variant-case)* ','?
  static boolean variant_cases(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "variant_cases")) return false;
    if (!nextTokenIs(b, IDENTIFIER)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = variant_case(b, l + 1);
    r = r && variant_cases_1(b, l + 1);
    r = r && variant_cases_2(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (',' variant-case)*
  private static boolean variant_cases_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "variant_cases_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!variant_cases_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "variant_cases_1", c)) break;
    }
    return true;
  }

  // ',' variant-case
  private static boolean variant_cases_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "variant_cases_1_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeToken(b, COMMA);
    r = r && variant_case(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // ','?
  private static boolean variant_cases_2(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "variant_cases_2")) return false;
    consumeToken(b, COMMA);
    return true;
  }

  /* ********************************************************** */
  // VARIANT IDENTIFIER '{' variant-cases '}'
  public static boolean variant_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "variant_definition")) return false;
    if (!nextTokenIs(b, VARIANT)) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = consumeTokens(b, 0, VARIANT, IDENTIFIER, OPEN_BRACE);
    r = r && variant_cases(b, l + 1);
    r = r && consumeToken(b, CLOSE_BRACE);
    exit_section_(b, m, VARIANT_DEFINITION, r);
    return r;
  }

  /* ********************************************************** */
  // 'version' '=' semver
  public static boolean version_field(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "version_field")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_, VERSION_FIELD, "<version field>");
    r = consumeToken(b, "version");
    r = r && consumeToken(b, EQUALS);
    r = r && semver(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  /* ********************************************************** */
  // (package-definition)? (package-item | nested-package-definition)*
  static boolean wit(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "wit")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = wit_0(b, l + 1);
    r = r && wit_1(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (package-definition)?
  private static boolean wit_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "wit_0")) return false;
    wit_0_0(b, l + 1);
    return true;
  }

  // (package-definition)
  private static boolean wit_0_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "wit_0_0")) return false;
    boolean r;
    Marker m = enter_section_(b);
    r = package_definition(b, l + 1);
    exit_section_(b, m, null, r);
    return r;
  }

  // (package-item | nested-package-definition)*
  private static boolean wit_1(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "wit_1")) return false;
    while (true) {
      int c = current_position_(b);
      if (!wit_1_0(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "wit_1", c)) break;
    }
    return true;
  }

  // package-item | nested-package-definition
  private static boolean wit_1_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "wit_1_0")) return false;
    boolean r;
    r = package_item(b, l + 1);
    if (!r) r = nested_package_definition(b, l + 1);
    return r;
  }

  /* ********************************************************** */
  // gate? WORLD IDENTIFIER '{' world-items* '}'
  public static boolean world_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_definition")) return false;
    boolean r, p;
    Marker m = enter_section_(b, l, _NONE_, WORLD_DEFINITION, "<world definition>");
    r = world_definition_0(b, l + 1);
    r = r && consumeTokens(b, 1, WORLD, IDENTIFIER, OPEN_BRACE);
    p = r; // pin = 2
    r = r && report_error_(b, world_definition_4(b, l + 1));
    r = p && consumeToken(b, CLOSE_BRACE) && r;
    exit_section_(b, l, m, r, p, null);
    return r || p;
  }

  // gate?
  private static boolean world_definition_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_definition_0")) return false;
    gate(b, l + 1);
    return true;
  }

  // world-items*
  private static boolean world_definition_4(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_definition_4")) return false;
    while (true) {
      int c = current_position_(b);
      if (!world_items(b, l + 1)) break;
      if (!empty_element_parsed_guard_(b, "world_definition_4", c)) break;
    }
    return true;
  }

  /* ********************************************************** */
  // export-definition
  //                    | import-definition
  //                    | use-declaration
  //                    | type-declaration
  //                    | include-declaration
  static boolean world_item_definition(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_item_definition")) return false;
    boolean r;
    r = export_definition(b, l + 1);
    if (!r) r = import_definition(b, l + 1);
    if (!r) r = use_declaration(b, l + 1);
    if (!r) r = type_declaration(b, l + 1);
    if (!r) r = include_declaration(b, l + 1);
    return r;
  }

  /* ********************************************************** */
  // gate? world-item-definition
  static boolean world_items(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_items")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NONE_);
    r = world_items_0(b, l + 1);
    r = r && world_item_definition(b, l + 1);
    exit_section_(b, l, m, r, false, WitParser::world_recovery);
    return r;
  }

  // gate?
  private static boolean world_items_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_items_0")) return false;
    gate(b, l + 1);
    return true;
  }

  /* ********************************************************** */
  // !( '}' | '@' | ';' | EXPORT | IMPORT | USE | TYPE | INCLUDE )
  static boolean world_recovery(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_recovery")) return false;
    boolean r;
    Marker m = enter_section_(b, l, _NOT_);
    r = !world_recovery_0(b, l + 1);
    exit_section_(b, l, m, r, false, null);
    return r;
  }

  // '}' | '@' | ';' | EXPORT | IMPORT | USE | TYPE | INCLUDE
  private static boolean world_recovery_0(PsiBuilder b, int l) {
    if (!recursion_guard_(b, l, "world_recovery_0")) return false;
    boolean r;
    r = consumeToken(b, CLOSE_BRACE);
    if (!r) r = consumeToken(b, AT);
    if (!r) r = consumeToken(b, SEMI);
    if (!r) r = consumeToken(b, EXPORT);
    if (!r) r = consumeToken(b, IMPORT);
    if (!r) r = consumeToken(b, USE);
    if (!r) r = consumeToken(b, TYPE);
    if (!r) r = consumeToken(b, INCLUDE);
    return r;
  }

}
