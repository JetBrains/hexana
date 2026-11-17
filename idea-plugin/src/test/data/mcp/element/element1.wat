;; elemkind = 1
;; decodes to: elem funcref* passive

(module
  (func $a)
  (func $b)

  ;; passive element segment with two function refs
  (elem funcref
    (ref.func $a)
    (ref.func $b))
)