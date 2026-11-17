(module
  (tag $e (param i32))
  (func $catcher (export "catcher") (result i32)
    (try_table (result i32) (catch $e 0)
      (throw $e (i32.const 42))
      (i32.const 0)
    )
  )
)