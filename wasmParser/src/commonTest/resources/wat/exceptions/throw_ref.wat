(module
  (tag $e (param i32))
  (func $rethrow (export "rethrow") (result i32)
    (try_table (result i32) (catch_ref $e 0)
      (throw $e (i32.const 42))
      (i32.const 0)
    )
    ;; stack: [i32, exnref] — swap and throw_ref
    (throw_ref)
  )
)