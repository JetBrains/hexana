# 03 — Hex Search

## Prerequisites
- A `.wasm` file open in the Hexana hex viewer

## Test Cases

### 3.1 Open search bar
**Steps:** Press Cmd/Ctrl+F while the hex viewer is focused.
**Expected:** Search bar appears above the hex view with a text input field.

### 3.2 Text search
**Steps:** Type `asm` in the search field.
**Expected:** Matching bytes are highlighted in the hex viewer. Counter shows "N of M" (e.g. "1 of 3").

### 3.3 Binary mode search
**Steps:** Enable the "Binary Mode" toggle in the search bar -> type `00 61 73 6d`.
**Expected:** Matches the WASM magic bytes (`\0asm`). First match highlighted.

### 3.4 Find Next
**Steps:** After a search with multiple results, press F3 or Cmd/Ctrl+G.
**Expected:** Selection moves to the next match. Counter updates.

### 3.5 Find Previous
**Steps:** Press Shift+F3.
**Expected:** Selection moves to the previous match. Counter updates.

### 3.6 No results
**Steps:** Search for a string that does not exist in the file (e.g. `zzzzzzz`).
**Expected:** Counter shows "0 of 0" or equivalent. No highlight.

### 3.7 Close search
**Steps:** Press Escape while the search bar is open.
**Expected:** Search bar closes. Focus returns to the hex viewer. Highlights are cleared.

### 3.8 Incremental search
**Steps:** Type a query character by character.
**Expected:** Results update live as each character is typed.
