(module
  (func (export "A"))
  (memory (export "M") 1)
  (table (export "T") 10 funcref)
  (global (export "G") i32 (i32.const 7))
)