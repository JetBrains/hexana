(module
  (func (export "f") (param i32) (result i32)
    (local.get 0)
    (if (result i32) (i32.eqz)
      (then
        (i32.const -1)
        (return)
      )
      (else
        (local.get 0)
      )
    )
  )
)