;; E2 propagation fixture (paramless tag to keep catch branch types trivial).
;;  $callee: throw $t0, no local handler -> escapes.
;;  $contained (export): calls $callee inside try_table catching $t0 -> caught, entry clean.
;;  $leaky (export): calls $callee, no handler -> $t0 escapes to the entry.
(module
  (type $v (func))
  (tag $t0 (type $v))
  (func $callee
    throw $t0)
  (func $contained
    block $b
      try_table (catch $t0 $b)
        call $callee
      end
    end)
  (func $leaky
    call $callee)
  (export "contained" (func $contained))
  (export "leaky" (func $leaky))
)
