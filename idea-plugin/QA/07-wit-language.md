# 07 — WIT Language Support

## Prerequisites
- A `.wit` file open in the IDE
- Hexana plugin installed

## Syntax & Editing

### 7.1 Syntax highlighting
**Steps:** Open a `.wit` file with interfaces, worlds, records, enums.
**Expected:** Keywords (`interface`, `world`, `record`, `func`, `enum`, etc.), comments, numbers, and punctuation are highlighted using standard IDE color scheme.

### 7.2 Semantic highlighting
**Steps:** Inspect contextual keywords like `as`, `with`, and type references.
**Expected:** Contextual keywords and type references have distinct highlighting.

### 7.3 Code completion — keywords
**Steps:** Start typing `interf` and invoke completion (Ctrl+Space).
**Expected:** `interface` keyword is suggested.

### 7.4 Code completion — gates
**Steps:** Type `@` at an annotation position and invoke completion.
**Expected:** `@since` and `@unstable` gate attributes are suggested.

### 7.5 Brace matching
**Steps:** Place cursor on an opening `{` in a WIT block.
**Expected:** Matching closing `}` is highlighted.

### 7.6 Code folding
**Steps:** Click the folding gutter icon next to an interface, world, or record definition.
**Expected:** Block collapses to a single summary line. Click again to expand.

### 7.7 Breadcrumbs
**Steps:** Place cursor inside a nested WIT definition (e.g. a field inside a record inside an interface).
**Expected:** Breadcrumb bar at the bottom/top of the editor shows the nesting: interface > record > field.

### 7.8 Line commenting
**Steps:** Select one or more lines -> Cmd+/ (Ctrl+/).
**Expected:** Lines are prefixed with `// `.

### 7.9 Block commenting
**Steps:** Select text -> Cmd+Shift+/ (Ctrl+Shift+/).
**Expected:** Selection is wrapped in `/* ... */`.

### 7.10 Documentation popup
**Steps:** Hover over a type or function name that has preceding `///` doc comments.
**Expected:** Documentation popup shows the rendered content.

## Navigation & Refactoring

### 7.11 Find usages
**Steps:** Place cursor on a type name -> Find Usages (Alt+F7).
**Expected:** All references to the type are listed in the Find tool window.

### 7.12 Rename
**Steps:** Place cursor on a type name -> Refactor -> Rename (Shift+F6). Enter a new name.
**Expected:** All usages of the type are updated to the new name.

### 7.13 Gutter navigation to WASM
**Steps:** Look for gutter icons on WIT `export` definitions that have a corresponding `.wasm` file.
**Expected:** Gutter icon with tooltip "Navigate to WASM definition in {file.name}". Clicking it opens the hex viewer at the corresponding binary location.

## Inspections

### 7.14 Empty enum
**Steps:** Write `enum empty {}` in a `.wit` file.
**Expected:** Error highlight on the empty body.

### 7.15 Empty record
**Steps:** Write `record empty {}`.
**Expected:** Error highlight.

### 7.16 Empty variant
**Steps:** Write `variant empty {}`.
**Expected:** Error highlight.

### 7.17 Duplicate world name
**Steps:** In a `world` block, add two exports or imports with the same name.
**Expected:** Error highlight on the duplicate name.

### 7.18 Missing semicolon
**Steps:** Remove a semicolon after a `use` statement.
**Expected:** Warning highlight. Quick fix available: "Add semicolon".

### 7.19 Missing semicolon — quick fix
**Steps:** Place cursor on the warning -> invoke quick fix (Alt+Enter) -> "Add semicolon".
**Expected:** Semicolon is inserted at the correct position.

### 7.20 Gate — both @since and @unstable
**Steps:** Add both `@since(version = 0.2.0)` and `@unstable(feature = x)` to the same item.
**Expected:** Error: cannot have both gates on the same item.

### 7.21 Gate — missing on child
**Steps:** Add `@since` to a parent interface but not to a child item inside it.
**Expected:** Error: gated parent requires gated children.

### 7.22 Gate — version ordering
**Steps:** Set child `@since(version = 0.1.0)` inside a parent with `@since(version = 0.2.0)`.
**Expected:** Error: child version must not be lower than parent version.

## Formatting

### 7.23 Reformat file
**Steps:** Open a poorly formatted `.wit` file -> Cmd+Alt+L (Ctrl+Alt+L).
**Expected:** File is reformatted according to WIT code style settings (indentation, spacing, blank lines).

### 7.24 Code style settings
**Steps:** Open Settings -> Editor -> Code Style -> WIT.
**Expected:** Settings page with Spacing, Blank Lines, Wrapping tabs. Changes apply on next reformat.
