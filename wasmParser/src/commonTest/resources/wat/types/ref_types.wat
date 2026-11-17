(module
  ;; Reference types: funcref and externref
  (type (func (param funcref) (result funcref)))
  (type (func (param externref) (result externref)))

  (func (export "f") (nop))
)