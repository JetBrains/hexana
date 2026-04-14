(module
  (func $add (param i32 i32) (result i32)
    (i32.add (local.get 0) (local.get 1))
  )
  (func (export "f") (result i32)
    (call $add (i32.const 3) (i32.const 4))
  )
)