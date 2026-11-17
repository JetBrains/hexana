(module
  (tag $e (param i32))
  (export "myexn" (tag $e))
  (func $thrower (export "thrower")
    (throw $e (i32.const 1))
  )
)