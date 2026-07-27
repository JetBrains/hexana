;; Fixture for DispatchCandidatesTest. Covers:
;;  - $t0 and $t0dup: structurally identical func types at different type
;;    indices — candidates must merge across them (structural matching).
;;  - $ext: imported function placed in the table (import candidates).
;;  - active elem: $ext $add1 $mul2 $sum are statically known candidates.
;;  - passive elem: $passive can enter a table via table.init — included.
;;  - declare elem: $orphan never populates a table — excluded.
;;  - $t2: func type with no table entries — empty candidate set; $caller2
;;    call_indirect's through it, so its per-signature call-graph hub exists
;;    but has zero candidate successors (trap-only site).
(module
  (type $t0 (func (param i32) (result i32)))
  (type $t0dup (func (param i32) (result i32)))
  (type $t1 (func (param i32 i32) (result i32)))
  (type $t2 (func (param f64) (result f64)))
  (import "env" "ext" (func $ext (type $t0)))
  (table 8 funcref)
  (func $add1 (type $t0) local.get 0 i32.const 1 i32.add)
  (func $mul2 (type $t0dup) local.get 0 i32.const 2 i32.mul)
  (func $sum (type $t1) local.get 0 local.get 1 i32.add)
  (func $orphan (type $t0) local.get 0)
  (func $passive (type $t0) local.get 0)
  (func $caller (param i32) (result i32)
    local.get 0
    i32.const 0
    call_indirect (type $t0dup))
  (func $caller2 (param f64) (result f64)
    local.get 0
    i32.const 0
    call_indirect (type $t2))
  (elem (i32.const 0) $ext $add1 $mul2 $sum)
  (elem func $passive)
  (elem declare func $orphan)
  (export "caller" (func $caller))
)
