(module
  (func (export "f") (param i32)
    (if (local.get 0)
      (then (nop))
    )
  )
)