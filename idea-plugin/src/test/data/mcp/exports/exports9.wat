(module
  (func $f (export "A"))
  (memory $m (export "Mem") 1)
  (global $g i32 (i32.const 3))

  (export "func2" (func $f))
  (export "glob2" (global $g))
)