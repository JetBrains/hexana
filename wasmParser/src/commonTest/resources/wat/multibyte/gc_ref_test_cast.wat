(module
  (type $base (struct (field i32)))
  (type $derived (sub $base (struct (field i32) (field i32))))

  ;; ref.test
  (func (export "test_derived") (param (ref null $base)) (result i32)
    local.get 0
    ref.test (ref $derived)
  )

  ;; ref.test (null)
  (func (export "test_derived_null") (param (ref null $base)) (result i32)
    local.get 0
    ref.test (ref null $derived)
  )

  ;; ref.cast
  (func (export "cast_derived") (param (ref null $base)) (result (ref $derived))
    local.get 0
    ref.cast (ref $derived)
  )

  ;; ref.cast (null)
  (func (export "cast_derived_null") (param (ref null $base)) (result (ref null $derived))
    local.get 0
    ref.cast (ref null $derived)
  )
)