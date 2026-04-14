(module
  (type $pair (func (result i32 i64)))
  (func (export "f") (param i32) (result i32 i64)
    (if (type $pair) (local.get 0)
      (then
        (i32.const 1)
        (i64.const 10)
      )
      (else
        (i32.const 0)
        (i64.const 0)
      )
    )
  )
)