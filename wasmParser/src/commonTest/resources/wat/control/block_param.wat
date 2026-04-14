(module
  ;; Block with param type — block consumes values from the stack
  (type $consume (func (param i32) (result i32)))
  (func (export "f") (result i32)
    (i32.const 10)
    (block (type $consume)
      (i32.const 1)
      (i32.add)
    )
  )
)