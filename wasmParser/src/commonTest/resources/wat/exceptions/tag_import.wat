(module
  (import "env" "exn" (tag $imported (param i32)))
  (func $thrower (export "thrower")
    (throw $imported (i32.const 99))
  )
)