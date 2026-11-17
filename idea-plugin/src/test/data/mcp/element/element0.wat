;; elemkind = 0
;; decodes to: elem (ref.func x y ...) (active 0 offset-expr)

(module
  (table 0 funcref)
  (func $f0)
  (func $f1)
  (elem (i32.const 0) $f0 $f1)
)