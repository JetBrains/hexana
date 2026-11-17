(module
  (tag $e (param i32))
  (func $catcher (export "catcher") (result i32)
    (try_table (result i32) (catch_ref $e 0)
      (throw $e (i32.const 42))
      (i32.const 0)
    )
    ;; catch_ref delivers (i32, exnref) — drop exnref, keep i32
    (drop)
  )
)