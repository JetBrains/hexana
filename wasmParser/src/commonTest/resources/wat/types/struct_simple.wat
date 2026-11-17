(module
  ;; Simple struct with immutable fields
  (type $point (struct (field i32) (field i32)))
  ;; Struct with mutable fields
  (type $mutable_point (struct (field (mut i32)) (field (mut i32))))
  ;; Struct with mixed mutability
  (type $mixed (struct (field i32) (field (mut f64))))

  (func (export "f") (nop))
)