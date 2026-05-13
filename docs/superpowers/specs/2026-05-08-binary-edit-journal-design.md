# Binary Edit Journal — Design Spec

## Overview

The lowest-level editing primitive for Hexana's binary document framework. Original files stay immutable; every change is recorded as one entry in an append-only JSON command log (sidecar file). Replaying the log on top of the original bytes reconstructs the current document state.

## Goals

- Original-file bytes never mutate on disk.
- Edit history is human-readable, diffable, version-controllable.
- The same JSON format can be produced two ways: (a) recorded incrementally as the user edits, (b) computed by diffing two binary files.
- Replay is deterministic and trivial — straight `for` loop, no offset translation.
- Format is implementable on every Kotlin target (JVM / JS / wasmJs) — no IDE dependencies.

## Non-Goals (deferred)

- Concurrent multi-user editing (no CRDT, no operational transforms).
- Sub-byte (bit-level) edits.
- Save-back-to-disk of the patched binary as a new file (separate concern; the journal is the source of truth).
- IDE wiring of the editing UX (this spec is the format and the in-memory layer; the UI layer comes later).

---

## Architecture

```
hexanaDocument/
  document/BinaryDocument.kt          — already exists; edit() will return a real EditTransaction
  document/EditTransaction.kt         — already exists; commit() implementation lands here
  document/journal/JournalEntry.kt    — JSON schema (kotlinx.serialization data classes)
  document/journal/JournalIO.kt       — read/write sidecar file
  document/journal/JournalReplay.kt   — apply(journal, originalBytes) → buffer
  document/journal/JournalDiff.kt     — Phase 2: diff(a, b) → JournalEntry list
  document/internal/PieceTableDocument.kt   — piece-table-backed BinaryDocument

idea-plugin/
  (Phase 3+ wiring; out of scope for this spec)
```

Important: every file listed above lives outside `idea-plugin`. The journal is a pure-Kotlin module with no Compose, no IntelliJ Platform dependencies.

---

## Locked-in decisions

### 1. Coordinate space — logical offsets

Every offset in the journal refers to the document state **at the moment that op was recorded**, i.e. after all prior ops in the journal have been applied. Replay is just:

```kotlin
val buffer = readOriginal()
for (entry in journal.entries) for (op in entry.ops) apply(op, buffer)
```

No translation, no shift bookkeeping. Cost: a journal entry can't reference the *original* file's offset directly once edits have happened above it. Benefit: any sequence of edits is expressible — including edits that build on previously-inserted bytes (which original-offset coordinates cannot express without CRDT-style position IDs).

### 2. Op vocabulary — three primitives

```
replace(start, end, data)    — replace [start, end) with `data`
insert(offset, data)         — equivalent to replace(offset, offset, data)
delete(start, end)           — equivalent to replace(start, end, EMPTY)
```

`replace` is kept as a primitive (not decomposed into delete+insert) because:

- A typical hex-column keystroke is a 1-byte replace; decomposing every keystroke doubles the journal size.
- Preserves user intent for any future history UI ("you replaced X with Y" reads better than "you deleted X then inserted Y").

`data` is RFC 4648 standard-alphabet base64 — no URL-safe variant, no line wrapping.

### 3. Atomicity — one entry per `commit()`

`EditTransaction.commit()` writes **one** journal entry holding the full `List<EditOp>` accumulated by that transaction. Within an entry, ops apply in array order using logical offsets advancing as we go.

Two reasons:

- **Cost.** A logical edit like "rename function `foo` to `foooo`" decomposes into delete + insert. If each op fired its own `DocumentChangeEvent` the WASM parser would re-parse twice for one keystroke. Per-commit batching means listeners (chunk tree, symbol resolver, search index) recompute exactly once.
- **Correctness.** Between the delete and the insert, the binary has the wrong ULEB128 length prefix on the name section — it's malformed. Per-commit batching never exposes that intermediate state to listeners.

This already matches the existing `DocumentChangeEvent` shape:

```kotlin
data class DocumentChangeEvent(
    val before: BinaryDocumentSnapshot,
    val after: BinaryDocumentSnapshot,
    val edits: List<EditOp>,   // ← the batch
)
```

### 4. In-memory backing — linear-scan piece table

`hexanaDocument` will gain an editable `BinaryDocument` backed by a piece table (linear scan, not RB-tree-indexed). All buffers and the piece list live in `hexanaDocument` — no IDE dependency, runs on JVM / JS / wasmJs.

```
piece-table state
─────────────────
original: ByteArray         // immutable, taken from the file on disk; never mutated
added:    ByteArray         // append-only; every insert/replace appends new bytes here
pieces:   List<Piece>       // ordered spans that together describe the current document

data class Piece(
    val source: Source,     // ORIGINAL or ADDED
    val start:  Long,       // offset into the source buffer
    val length: Long,
)
```

Op implementations:

- `replace(start, end, data)` — split the piece list at `start` and `end`; drop pieces fully inside `[start, end)`; append `data` to `added`; insert one `Piece(ADDED, …)` at the cut point.
- `insert(offset, data)` — split at `offset`; append `data` to `added`; insert one `Piece(ADDED, …)`.
- `delete(start, end)` — split at `start` and `end`; drop pieces inside.

Snapshots: clone the `List<Piece>` and freeze the buffer references. `Piece` is immutable, so a snapshot is just a frozen reference to a piece list — O(1) per snapshot, no byte copying. The `original` buffer is shared by every snapshot ever taken.

`get(offset)`: linear scan over `pieces` to find the span containing `offset`, then index into `original` or `added`. O(p) where `p` is the number of pieces. For Hexana's expected scale (≤ 50 MB binaries, ≤ tens of thousands of edits per session) the scan stays well under microsecond render budgets. If profiling later shows it's hurting, the upgrade path is to swap the `List<Piece>` for an RB-tree-indexed structure (VS Code's "piece tree") — a one-time refactor behind the same `EditableByteSequence` shape.

`added` is append-only; it grows monotonically across the session, including across undo. A separate `compact()` operation can rebuild the piece list and a fresh `added` buffer from a replay of the journal — out of scope for the editing primitive.

### 5. Original-file binding

Each journal carries `originalHash` (sha256 of the original bytes) and `originalLength`. On load, we hash the file on disk and refuse to apply the journal if either field disagrees. The mismatch UX is in §8.

### 6. Sidecar location & write cadence

- **Location**: next to the binary, with the binary's path + literal `.hexana.json` suffix. `foo.wasm` ⇒ `foo.wasm.hexana.json`. Same directory; no IDE-workspace storage.
- **Existence is opt-in**: the sidecar is created on the first `commit()`, not on document open. Read-only viewing of an unmodified file leaves the directory untouched.
- **Format**: bundled JSON (the schema in §JSON schema), one object per file, with the full `entries` array.
- **Cadence**: every `commit()` rewrites the sidecar. Pattern is **temp-file + atomic rename**:
  1. Serialize the full `Journal` object to bytes.
  2. Write to `foo.wasm.hexana.json.tmp`.
  3. `fsync` the tmp file (durability) — Phase 1 may skip this; document the trade-off.
  4. `rename(tmp, foo.wasm.hexana.json)` — atomic on POSIX and NTFS.
  Either the rename completes (new state visible) or it doesn't (old state intact). Never a half-written file.
- **The journal layer doesn't watch the filesystem.** If the user moves/renames the binary outside the IDE, finding the sidecar on next open is the IDE plugin's problem (it knows the new path). The journal layer only consumes paths the caller hands it.
- **Single-window assumption.** Two IDE windows editing the same file concurrently is **not supported in Phase 1**. The atomic-rename pattern protects against torn writes, but a "last commit wins" race can still drop entries from the other window. Documented as a known limitation; revisit if it bites.

### 7. Undo / redo

Undo and redo are **in-memory** operations owned by `BinaryDocument`. The on-disk journal **always reflects the current state** — undone entries are rewritten out of the file. Closing the IDE finalizes the in-memory state to disk; the redo stack is not persisted.

**Internal representation.** Each `BinaryDocument` carries:

```kotlin
private val original: ByteArray                          // immutable, from the file on disk
private val entries: MutableList<JournalEntry>           // every commit ever made, including ones past `cursor`
private val snapshots: MutableList<BinaryDocumentSnapshot>  // snapshots[i] = state after entries[0..i]
private var cursor: Int = -1                              // points into entries; -1 = original (no edits applied)
                                                          //   currentSnapshot = if (cursor < 0) snapshotOf(original) else snapshots[cursor]
```

**State machine.**

- `commit()` (new edit while there's a redo stack): discard `entries[cursor+1..]` and `snapshots[cursor+1..]`, append the new entry + post-commit snapshot, `cursor++`. Persist `entries[0..cursor]` to the sidecar via the atomic-rename path. Fire one `DocumentChangeEvent`.
- `undo()`: requires `cursor >= 0`. Set `cursor--`. The post-undo state is `snapshots[cursor]` (or the original buffer if `cursor == -1`). The popped entry stays in `entries[cursor+1..]` so a subsequent `redo()` can re-apply it. Persist `entries[0..cursor]` to the sidecar (rewrite with one fewer entry). Fire `DocumentChangeEvent` whose `edits` list contains the *inverse* ops of the popped entry — listeners apply the same shift logic regardless of direction.
- `redo()`: requires `cursor < entries.size - 1`. Set `cursor++`. State becomes `snapshots[cursor]`. Persist. Fire forward `DocumentChangeEvent`.
- `canUndo()` ⇔ `cursor >= 0`. `canRedo()` ⇔ `cursor < entries.size - 1`.

**Persisting `entries[0..cursor]`.** "Persist" means: serialize the slice to JSON and atomic-rename, *unless* the slice is empty — in which case delete the sidecar. This keeps §6's "created on first commit" symmetry: the sidecar exists ⇔ at least one applied entry. After the user undoes everything, the directory is back to its pre-edit state. The in-memory redo stack still has the un-undone entries, so a subsequent `redo()` recreates the sidecar.

**Why O(1) per undo/redo.** Snapshots are immutable references to a `List<Piece>` (and the immutable `original` and append-only `added` buffers behind them). Storing `snapshots: MutableList<BinaryDocumentSnapshot>` is cheap — each entry is a small frozen list reference, no byte copying. So `undo()` is just a pointer move + a journal rewrite + an event.

**Inverse-op computation for the event.** When firing the undo event, the inverse `EditOp`s are computed from the pre/post snapshots in the event itself (we have both). Listeners that already handle `Replace/Insert/Delete` work for undo without changes.

**API additions on `BinaryDocument`.**

```kotlin
public fun undo()
public fun redo()
public fun canUndo(): Boolean
public fun canRedo(): Boolean
```

These live in `hexanaDocument`. The IDE plugin wires them to keystrokes / actions; nothing in the journal layer talks to the IDE.

**Sacrificed.** Persistent redo across IDE restart. We could add it later by storing the `cursor` and the un-undone tail of `entries` on disk (e.g. an "active range" annotation), but that's an extension — it doesn't affect the file format that's locked in here.

### 8. Hash-mismatch UX

On open, the journal layer returns one of three results from a load operation:

```kotlin
sealed interface JournalLoadResult {
    data class Loaded(val journal: Journal) : JournalLoadResult
    data class Empty(val originalHash: String, val originalLength: Long) : JournalLoadResult     // sidecar absent
    data class Mismatch(val journal: Journal?, val reason: MismatchReason) : JournalLoadResult   // sidecar present but unusable
}

enum class MismatchReason { HASH, LENGTH, CORRUPT, SCHEMA_UNKNOWN }
```

The pure-Kotlin layer **never deletes or modifies the sidecar on its own** — it only reports mismatch. The IDE plugin owns the UX:

1. Show a notification: "The edit log for this file is stale (the file has changed since the log was written)."
2. Two buttons:
   - **Discard log** — delete the sidecar; open the file as if the log never existed.
   - **Backup, then discard** — rename `foo.wasm.hexana.json` → `foo.wasm.hexana.stale.<UTC-timestamp>.json` (timestamp formatted `yyyyMMddTHHmmssZ` so it's portable to Windows), then open as if the log never existed. The user can recover manually if they want to.
3. **No "apply anyway" option.** Replaying a stale log against a different file produces malformed offsets — that's data loss disguised as success. We won't offer it.
4. Diff-rebase (`diff(currentOnDisk, replay(originalFromJournal, journal))`) is **not** offered: it requires the journal to embed the original bytes, which would defeat the size win. We could add this later if there's demand, but it'd be a separate feature.

### 9. Crash recovery & corrupt-sidecar handling

**What's covered by §6's atomic-rename pattern.** A crash, power loss, or kill during a commit-driven write either leaves the previous version of the sidecar intact (rename never happened) or atomically swaps in the new version (rename completed). A torn write between the two states never persists.

**What's not covered.** Hardware corruption, external edits to the sidecar (e.g., user opens it in vim and saves a typo), and forward-incompatible schema versions. These produce a sidecar that exists but doesn't parse cleanly into our model.

**Behavior on corrupt load.** The journal layer treats every parse failure as a stale-sidecar event and reuses the §8 mismatch path:

- Malformed JSON / structural error → `Mismatch(journal = null, reason = CORRUPT)`.
- `schema` value unrecognized by this build → `Mismatch(journal = null, reason = SCHEMA_UNKNOWN)`.
- Hash or length disagreement → `Mismatch(journal, reason = HASH | LENGTH)` as before.

The plugin's notification handler treats all four `MismatchReason`s identically — same Discard / Backup-then-discard UX. The only difference is the human-readable message ("the edit log file is corrupted" vs. "the edit log is for a different version of this file"). The pure layer makes no recovery attempt.

**Why no salvage.** Bundled JSON is all-or-nothing for parsing — partial recovery would need a streaming tokenizer with custom error tolerance, which is significant code for a rare failure mode. Backup-then-discard preserves the file so a user can hand-recover with a JSON editor if they want to. Reconsider only if real-world usage shows hand-recovery is common.

**Disk-full during commit.** The `.tmp` write fails, the rename never happens, and the existing sidecar on disk is untouched. The in-memory journal still has every entry. The plugin surfaces an error notification ("couldn't save edit log: disk full") and the user can free space and retry. No format-level coverage needed; this is plumbing in the IDE error-reporting layer.

**fsync.** Phase 1 may skip fsync of the tmp file and the parent directory before declaring "saved." The atomic-rename gives us crash-consistency at the OS-call level; durability across power loss is best-effort. If a real loss-of-edits incident happens, upgrade to the strict "fsync tmp → rename → fsync parent" pattern. Documented as a known trade-off.

---

## JSON schema

```json
{
  "schema": 1,
  "originalHash": "sha256:fb1c…",
  "originalLength": 12345,
  "entries": [
    {
      "timestamp": "2026-05-08T12:34:56Z",
      "ops": [
        {"op": "replace", "start": 16, "end": 20, "data": "AAEC0w=="},
        {"op": "insert",  "offset": 100, "data": "AA=="},
        {"op": "delete",  "start": 200, "end": 210}
      ]
    },
    {
      "timestamp": "2026-05-08T12:35:11Z",
      "ops": [{"op": "replace", "start": 16, "end": 17, "data": "/w=="}]
    }
  ]
}
```

Field rules:

- `schema` — integer; bumped on incompatible format changes.
- `originalHash` — `"sha256:" + lowercase-hex(64 chars)`.
- `originalLength` — non-negative; matches the byte length of the original file.
- `entries` — array, append-only.
- `entries[].timestamp` — ISO-8601 UTC, second precision.
- `entries[].ops` — non-empty array.
- `op` ∈ `{ "replace", "insert", "delete" }`.
- Offsets (`start`, `end`, `offset`) — non-negative integers in *logical* coordinates (the document state right before the op).
- `data` — RFC 4648 standard-alphabet base64; required for `replace`/`insert`, absent for `delete`.

### Kotlin model

```kotlin
@Serializable data class Journal(
    val schema: Int = 1,
    val originalHash: String,
    val originalLength: Long,
    val entries: List<JournalEntry>,
)

@Serializable data class JournalEntry(
    val timestamp: String,
    val ops: List<JournalOp>,
)

@Serializable sealed interface JournalOp {
    @Serializable @SerialName("replace")
    data class Replace(val start: Long, val end: Long, val data: String) : JournalOp
    @Serializable @SerialName("insert")
    data class Insert(val offset: Long, val data: String) : JournalOp
    @Serializable @SerialName("delete")
    data class Delete(val start: Long, val end: Long) : JournalOp
}
```

`kotlinx.serialization` with the `op` field as discriminator. Common-target — works on JVM / JS / wasmJs.

---

## Replay semantics

```kotlin
fun replay(originalBytes: ByteArray, journal: Journal): ByteArray {
    require(sha256Hex(originalBytes) == journal.originalHash.removePrefix("sha256:"))
    require(originalBytes.size.toLong() == journal.originalLength)

    val buf = MutableByteSequence(originalBytes)  // any seq w/ replace/insert/delete; the prod impl is a piece table
    for (entry in journal.entries) {
        for (op in entry.ops) {
            when (op) {
                is JournalOp.Replace -> buf.replace(op.start, op.end, decode(op.data))
                is JournalOp.Insert  -> buf.insert(op.offset, decode(op.data))
                is JournalOp.Delete  -> buf.delete(op.start, op.end)
            }
        }
    }
    return buf.toByteArray()
}
```

Pre/post conditions:

- After `commit()`: every offset in the just-appended entry is valid against the *pre-commit* buffer.
- Replaying entries 0..k yields the same buffer as if those k+1 commits had been applied live.

---

## Diff path (Phase 2)

The same JSON format can be produced by diffing two binaries directly. This is **not implemented in Phase 1**, but the format and the op vocabulary already support it.

```
diff(A, B): JournalEntry
```

Walks A and B with a suffix-array-based longest-match algorithm (bsdiff-style) or a rolling-hash blockwise scan (rsync/xdelta-style). Emits:

```
COMMON_RANGE          → no op
B_LITERAL only        → insert(offset, base64(literal))
A_RANGE only          → delete(start, end)
A_RANGE + B_LITERAL   → replace(start, end, base64(literal))
```

Round-trip property: `replay(A, [diff(A, B)]) == B` exactly.

Use cases this enables:

- **Round-trip test** for live editing: after each commit, `assertEquals(diff(original, current), journal.flatOps())`.
- **External patcher input**: someone runs `wasm-opt`; we diff(before, after) → journal entries → apply on top of the user's current state.
- **Compaction**: when the journal grows to thousands of entries, replay then `diff(original, current) → singleEntry`. Same end state, smaller log.
- **Sharing edits** between machines/sessions.
- **Stale-journal recovery**: file replaced under us, but we have the last good buffer in memory — `diff(currentOnDisk, lastGoodBuffer)` rebuilds the journal.

Caveat: `diff(A, B)` is not unique. Many op sequences yield the same B. The chosen algorithm fixes which one we get; for deterministic tests we'd pin it. Suffix-array (bsdiff-style) is the recommended default — produces minimal output.

---

## Existing infrastructure already aligned

The `hexanaDocument` module already declares this surface:

- `BinaryDocument.edit(): EditTransaction` — Phase 1 throws; this spec is what makes it work.
- `EditTransaction.replace/insert/delete/commit/close` — already the right API.
- `EditOp.Replace/Insert/Delete` — already the right shape; trivial to map to `JournalOp`.
- `DocumentChangeEvent.edits: List<EditOp>` — already commit-grained, matching our atomicity decision.

So the journal is what *backs* the existing API; no call-site changes needed for downstream code.

---

## Deferred questions (to be resolved before implementation)

1. ~~**In-memory data structure.**~~ ✅ Resolved 2026-05-08 — linear-scan piece table in `hexanaDocument`. See Locked-in §4.
2. ~~**Sidecar location & lifecycle.**~~ ✅ Resolved 2026-05-08 — `foo.wasm.hexana.json` next to the binary; bundled JSON; atomic-rename per commit. See Locked-in §6.
3. ~~**Hash-mismatch UX.**~~ ✅ Resolved 2026-05-08 — pure layer reports `Loaded` / `Empty` / `Mismatch`; plugin offers Discard / Backup-then-discard. See Locked-in §8.
4. ~~**Undo / redo.**~~ ✅ Resolved 2026-05-08 — in-memory cursor over `entries` + `snapshots` lists; on-disk journal always reflects current state; redo not persisted across IDE restart. See Locked-in §7.
5. ~~**Crash recovery.**~~ ✅ Resolved 2026-05-08 — atomic-rename in §6 covers torn writes; corrupt / unknown-schema sidecars reuse the §8 mismatch path (Discard / Backup-then-discard, no salvage). See Locked-in §9.
6. **Diff implementation timing.** Ship `replay` first; defer `diff()` until a concrete use case (compaction, stale-recovery, external patch) lands. Implementation-order question, not a format question.

---

## Out of scope for the journal layer

The following are real concerns but they live elsewhere:

- **UI** — selection, caret, key bindings, hex-cell editing. `idea-plugin` only.
- **Re-parse incrementality.** A listener that only shifts chunk byte-ranges by Δlength instead of rebuilding the chunk tree. Optimization on top; the event already carries the data.
- **File save** — write the patched bytes back as a new binary. A separate "export" operation, not part of the editing primitive.
- **Multi-document / multi-window coherence** — two IDE windows on the same file racing on the journal.

---

## Resolved during design

**Op naming (`insert` / `delete` / `replace`).** Considered adopting JSON Patch's `add` / `remove` / `replace` for affinity with RFC 6902. Decided to stay with our own API names — the existing `EditTransaction` and `EditOp` already use `insert` / `delete` / `replace`, and internal consistency wins over loose affinity with a JSON-document standard whose `path` (JSON Pointer) doesn't map to our byte-offset addressing anyway.
