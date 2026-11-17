(module
  (tag $e (param i32))
  (func $thrower (export "thrower")
    (throw $e (i32.const 42))
  )
)