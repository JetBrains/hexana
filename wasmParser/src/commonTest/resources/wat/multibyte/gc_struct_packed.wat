(module
  (type $packed (struct (field $b (mut i8)) (field $s (mut i16))))

  ;; struct.get_s (sign-extending read of packed field)
  (func (export "get_b_s") (param (ref null $packed)) (result i32)
    local.get 0
    struct.get_s $packed $b
  )

  ;; struct.get_u (zero-extending read of packed field)
  (func (export "get_b_u") (param (ref null $packed)) (result i32)
    local.get 0
    struct.get_u $packed $b
  )
)