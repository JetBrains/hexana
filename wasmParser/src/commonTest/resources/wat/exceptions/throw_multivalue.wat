(module
  (tag $e (param i32 i64))
  (func $thrower (export "thrower")
    (throw $e (i32.const 1) (i64.const 2))
  )
)