---
title: WIT Language Support in Hexana (0.9)
description: Full reference for Hexana's WIT (WebAssembly Interface Types) language support — parser, inspections, completion, formatting, navigation.
version: "0.9"
language: wit
---

# WIT Language Support

**WIT** (WebAssembly Interface Types) is the interface description language of the WebAssembly Component Model. Hexana 0.9 ships a full language implementation for `.wit` files.

## Capabilities

### Editing

- **Lexer + parser** (`WitParserDefinition`) with a complete PSI tree.
- **Brace matcher** (`WitBraceMatcher`).
- **Commenter** (`WitCommenter`) — `Cmd/Ctrl+/` line comments, block comments.
- **Folding** (`WitFoldingBuilder`) — package, interface, world, record, variant, enum, and function bodies are foldable.
- **Breadcrumbs** (`WitBreadcrumbsProvider`) — visible at the top of the editor when the caret is inside a nested declaration.

### Highlighting

- **Syntax highlighter** (`WitSyntaxHighlighter`) — colours keywords, identifiers, literals, comments, semicolons, punctuation.
- **Semantic keyword highlighter** (`WitSemanticKeywordHighlighter`, registered with `order="first"`) — distinguishes contextual keywords from regular identifiers.

### Completion

Two completion contributors:

- `WitKeywordCompletionContributor` — completes WIT keywords (`interface`, `world`, `package`, `import`, `export`, `use`, `include`, `type`, `record`, `variant`, `enum`, `flags`, `resource`, `func`, etc.) based on parser context.
- `WitGateCompletionContributor` — completes `@since`, `@unstable`, `@deprecated` gates and their parameter names.

### Inspections

Five inspections, all wired through `HexanaBundle` for translation:

| Inspection | Level | Condition |
|---|---|---|
| `WitEmptyDefinitionInspection` | ERROR | An interface, world, or other definition is declared with no body. |
| `WitWorldNameUniquenessInspection` | ERROR | Two worlds with the same name in the same package. |
| `WitMissingSemicolonInspection` | WARNING | A statement is missing its terminating semicolon. |
| `WitGateInspection` | ERROR | Malformed gate (`@since`, `@unstable`, `@deprecated`) — bad arguments or invalid placement. |
| `WitUseDeclarationInspection` | ERROR | A `use` declaration is missing names. |

### Navigation

- **Find Usages** via `WitFindUsagesHandlerFactory` — reference-aware across files.
- **Goto Symbol** via `HexanaGotoSymbolContributor` — every interface, world, record, variant, function, and type appears in `Cmd/Ctrl+Alt+Shift+N`.
- **Goto Related Symbols** line-marker (`WitGoToRelatedSymbolsProvider`) — gutter icons that jump to related declarations (e.g. a `use` to the definition it imports).
- **Cross-file resolve** through `WitComponentIndex` plus the built-in WIT type definitions seeded by `WitBuiltInDefinitionsContributor` (`indexedRootsProvider`).

### Rename and refactor

- **Rename input validator** (`WitRenameInputValidator`) — rejects names that would violate WIT identifier rules.
- **Element manipulators** for `WitHandle`, `WitUsePath`, `WitUseNamesItem`, `WitIncludeNamesItem` — the platform uses these to perform safe in-place rewrites during Rename, Move, and Inline.

### Formatting

- **Formatter** (`WitFormattingBuilderModel`) — applied by `Reformat Code` (`Cmd/Ctrl+Alt+L`).
- **Line-wrap strategy** (`WitLineWrapPositionStrategy`) — controls where the editor wraps long lines.
- **Code style settings** — `WitCodeStyleSettingsProvider` and `WitLanguageCodeStyleSettingsProvider` register a `WIT` tab under **Settings → Editor → Code Style** with indent and wrapping options.

### Documentation

- **Documentation provider** (`WitDocumentationProvider`) — `F1` / hover shows declaration kind, parent package, and any preceding `///` doc comments.

## How Hexana resolves WIT symbols

1. **Local lookup** within the current file (interface, world, record fields, function parameters, type aliases).
2. **`use` and `include` resolution** — follows `use a:b/c.{x, y}` to the named declarations in the imported package.
3. **`WitComponentIndex`** — file-based index keyed by fully qualified WIT name (`namespace:package/interface.symbol`). Resolution falls back to this when the symbol is in a different file.
4. **Built-in types** — `WitBuiltInDefinitionsContributor` registers a synthetic source root containing the WIT primitives (`bool`, `u8`–`u64`, `s8`–`s64`, `f32`, `f64`, `char`, `string`, `list<T>`, `option<T>`, `result<T, E>`, `tuple<…>`).

If resolution fails at every layer, the reference is highlighted as unresolved and a quick-fix may offer to create the missing declaration.

## Limitations in 0.9

- WIT formatting is conservative — it preserves existing whitespace where the formatter doesn't have an explicit rule, rather than aggressively reflowing.
- No quick-fixes are wired for the five inspections in 0.9; inspections report problems but do not auto-correct.
- Component-Model `@gate` checking validates syntax only; semver-comparison against an actual target version is not yet implemented.
- Find Usages on a WIT symbol does not yet search across `.wasm` binaries that import / export it — that bridge is on the roadmap.

## See also

- [`features.md`](features.md) — full Hexana feature reference.
- [`file-types.md`](file-types.md) — how WIT files are routed.
- The IntelliJ Platform [Language API documentation](https://plugins.jetbrains.com/docs/intellij/custom-language-support.html) — for contributors extending Hexana's WIT support.
