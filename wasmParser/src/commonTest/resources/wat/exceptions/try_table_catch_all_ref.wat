(module
  (tag $e (param i32))
  (func $catcher (export "catcher") (result i32)
    (try_table (result i32) (catch_all_ref 0)
      (throw $e (i32.const 42))
      (i32.const 0)
    )
    ;; catch_all_ref delivers (exnref) — drop it
    (drop)
    (i32.const -1)
  )
)