(module
  ;; Recursive type group — mutually referential types
  (rec
    (type $node (struct (field i32) (field (ref null $list))))
    (type $list (struct (field (ref null $node)) (field (ref null $list))))
  )

  (func (export "f") (nop))
)