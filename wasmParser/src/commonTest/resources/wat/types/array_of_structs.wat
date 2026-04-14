(module
  (type $point (struct (field f32) (field f32)))
  ;; Array of struct references
  (type $points (array (mut (ref null $point))))

  (func (export "f") (nop))
)