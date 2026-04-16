(module
  (type $base (struct (field i32)))
  (type $derived (sub $base (struct (field i32) (field i32))))

  ;; br_on_cast
  (func (export "br_cast") (param (ref null $base)) (result i32)
    (block $is_derived (result (ref $derived))
      local.get 0
      br_on_cast $is_derived (ref null $base) (ref $derived)
      ;; not derived — return 0
      drop
      i32.const 0
      return
    )
    ;; is derived — read second field
    struct.get $derived 1
  )

  ;; br_on_cast_fail
  (func (export "br_cast_fail") (param (ref null $base)) (result i32)
    (block $not_derived (result (ref null $base))
      local.get 0
      br_on_cast_fail $not_derived (ref null $base) (ref $derived)
      ;; is derived — read second field
      struct.get $derived 1
      return
    )
    ;; not derived — return 0
    drop
    i32.const 0
  )
)