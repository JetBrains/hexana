(module
  ;; Packed field types: i8 and i16 (only valid inside struct/array)
  (type $packed (struct (field i8) (field i16) (field (mut i8))))

  (func (export "f") (nop))
)