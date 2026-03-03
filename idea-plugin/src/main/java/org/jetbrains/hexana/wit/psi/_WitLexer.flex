package org.jetbrains.hexana.wit.psi;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static org.jetbrains.hexana.wit.psi.WitElementTypes.*;

%%

%{
  public _WitLexer() {
    this((java.io.Reader)null);
  }
%}

%public
%class _WitLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

EOL=\R
WHITE_SPACE=\s+

LINE_COMMENT="//".*
BLOCK_COMMENT="/"\*([^*]|\*+[^*/])*(\*+"/")?
NUMBER=-?(0|[1-9]([0-9]|_)*)
SEMVER=(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(\.(0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?(\+([0-9a-zA-Z-]+(\.[0-9a-zA-Z-]+)*))?
IDENTIFIER=(%?)[[:jletterdigit:]_]+

%%
<YYINITIAL> {
  {WHITE_SPACE}         { return WHITE_SPACE; }

  "/"                   { return SLASH; }
  "."                   { return DOT; }
  ","                   { return COMMA; }
  ":"                   { return COLON; }
  ";"                   { return SEMI; }
  "<"                   { return LESS_THAN; }
  ">"                   { return GREATER_THAN; }
  "{"                   { return OPEN_BRACE; }
  "}"                   { return CLOSE_BRACE; }
  "("                   { return OPEN_PAREN; }
  ")"                   { return CLOSE_PAREN; }
  "="                   { return EQUALS; }
  "@"                   { return AT; }
  "_"                   { return UNDERSCORE; }
  "->"                  { return ARROW; }
  "async"               { return ASYNC; }
  "blob"                { return BLOB; }
  "borrow"              { return BORROW; }
  "constructor"         { return CONSTRUCTOR; }
  "enum"                { return ENUM; }
  "export"              { return EXPORT; }
  "flags"               { return FLAGS; }
  "func"                { return FUNC; }
  "future"              { return FUTURE; }
  "import"              { return IMPORT; }
  "include"             { return INCLUDE; }
  "interface"           { return INTERFACE; }
  "list"                { return LIST; }
  "option"              { return OPTION; }
  "package"             { return PACKAGE; }
  "record"              { return RECORD; }
  "resource"            { return RESOURCE; }
  "restricted"          { return RESTRICTED; }
  "result"              { return RESULT; }
  "static"              { return STATIC; }
  "stream"              { return STREAM; }
  "tuple"               { return TUPLE; }
  "type"                { return TYPE; }
  "use"                 { return USE; }
  "variant"             { return VARIANT; }
  "world"               { return WORLD; }
  "ϵ"                   { return Ε; }

  {LINE_COMMENT}        { return LINE_COMMENT; }
  {BLOCK_COMMENT}       { return BLOCK_COMMENT; }
  {NUMBER}              { return NUMBER; }
  {SEMVER}              { return SEMVER; }
  {IDENTIFIER}          { return IDENTIFIER; }

}

[^] { return BAD_CHARACTER; }
