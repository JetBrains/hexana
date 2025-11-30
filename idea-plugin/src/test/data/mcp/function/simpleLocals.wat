(module
  ;; Function index 0
  (func $f0
    (local i32 i64 f32 f64)    ;; locals 0..3
    local.get 0
    local.get 1
    local.get 2
    local.get 3
    drop drop drop drop)

  (export "f0" (func $f0))
)