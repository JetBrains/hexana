;; TypeGraph fixture
;; Type index layout:
;;   rec group #0 (indices 0..2):
;;     0 = $Base    STRUCT  sub-open  { i32 }
;;     1 = $Mid     STRUCT  sub $Base { i32, (ref null $BaseArr) }
;;     2 = $BaseArr ARRAY   sub-open  (mut i32)
;;   rec group #1 (indices 3..4):
;;     3 = $Leaf    STRUCT  sub final $Mid { i32, (ref null $BaseArr), i32 }
;;     4 = $Other   STRUCT  { (ref null $BaseArr), (ref null $Base) }
;;   non-rec (indices 5..6):
;;     5 = $Sig     FUNC    (param (ref null $Base)) (result i32)
;;     6 = $SubArr  ARRAY   sub $BaseArr (mut i32)
;;
;; GC structural subtyping rules:
;;   $Mid <: $Base  => $Mid fields must be a supersequence of $Base's fields (same prefix)
;;   $Leaf <: $Mid  => $Leaf fields must start with $Mid's fields
;;   $SubArr <: $BaseArr => same element type (both mut i32)
;;
;; Functions (0 imports):
;;   funcidx 0 = $fn_gc    — exercises ref.test, ref.cast, struct.get, array.new_default, call_ref
;;   funcidx 1 = $fn_other — minimal placeholder

(module
  (rec
    (type $Base (sub (struct (field i32))))
    (type $Mid  (sub $Base (struct (field i32) (field (ref null $BaseArr)))))
    (type $BaseArr (sub (array (mut i32))))
  )
  (rec
    (type $Leaf  (sub final $Mid (struct (field i32) (field (ref null $BaseArr)) (field i32))))
    (type $Other (struct (field (ref null $BaseArr)) (field (ref null $Base))))
  )
  (type $Sig (func (param (ref null $Base)) (result i32)))
  (type $SubArr (sub $BaseArr (array (mut i32))))

  (table 1 funcref)
  (elem (i32.const 0) $fn_other)

  (func $fn_gc (export "fn_gc") (param $sig (ref null $Sig)) (result i32)
    ;; array.new_default $BaseArr — references type 2
    (array.new_default $BaseArr (i32.const 4))
    drop

    ;; ref.test (ref $Mid) — references type 1
    (ref.null $Base)
    (ref.test (ref $Mid))
    drop

    ;; ref.cast (ref null $Mid) — references type 1
    (ref.null $Base)
    (ref.cast (ref null $Mid))
    drop

    ;; struct.get $Mid 0 — references type 1
    (ref.null $Mid)
    (struct.get $Mid 0)
    drop

    ;; call_ref $Sig — references type 5
    (ref.null $Base)
    (local.get $sig)
    (call_ref $Sig)
    drop

    (i32.const 0)
  )

  (func $fn_other (export "fn_other") (result i32)
    (i32.const 42)
  )
)
