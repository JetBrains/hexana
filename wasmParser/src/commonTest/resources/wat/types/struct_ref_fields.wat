(module
  (type $inner (struct (field i32)))
  ;; Struct with reference-typed fields
  (type $outer (struct
    (field (ref null $inner))
    (field (mut (ref null $inner)))
    (field funcref)
    (field externref)
  ))

  (func (export "f") (nop))
)