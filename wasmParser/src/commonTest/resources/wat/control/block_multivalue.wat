(module
  ;; Block type via type index (s33 encoding) for multi-value
  (type $mv (func (result i32 i64)))
  (func (export "f") (result i32 i64)
    (block (type $mv)
      (i32.const 1)
      (i64.const 2)
    )
  )
)