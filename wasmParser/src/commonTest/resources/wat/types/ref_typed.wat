(module
  (type $s (struct (field i32)))

  ;; Typed references: nullable and non-nullable
  (type (func (param (ref null $s)) (result (ref $s))))
  ;; Abstract heap types
  (type (func (param anyref)))
  (type (func (param eqref)))
  (type (func (param i31ref)))
  (type (func (param structref)))
  (type (func (param arrayref)))
  (type (func (param nullref)))
  (type (func (param nullexternref)))
  (type (func (param nullfuncref)))

  (func (export "f") (nop))
)