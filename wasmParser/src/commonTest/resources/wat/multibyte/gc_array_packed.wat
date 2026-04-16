(module
  (type $bytes (array (mut i8)))

  ;; array.get_s (sign-extending)
  (func (export "get_s") (param (ref null $bytes) i32) (result i32)
    local.get 0
    local.get 1
    array.get_s $bytes
  )

  ;; array.get_u (zero-extending)
  (func (export "get_u") (param (ref null $bytes) i32) (result i32)
    local.get 0
    local.get 1
    array.get_u $bytes
  )
)