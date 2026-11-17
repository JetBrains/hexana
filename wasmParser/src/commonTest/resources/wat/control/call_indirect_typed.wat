(module
  (type $binop (func (param i32 i32) (result i32)))
  (table 2 funcref)
  (elem (i32.const 0) $add $sub)

  (func $add (type $binop) (i32.add (local.get 0) (local.get 1)))
  (func $sub (type $binop) (i32.sub (local.get 0) (local.get 1)))

  (func (export "f") (param i32) (result i32)
    ;; call_indirect dispatches through type $binop
    (call_indirect (type $binop)
      (i32.const 10) (i32.const 3)
      (local.get 0)
    )
  )
)