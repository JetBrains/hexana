(module
  ;; All four numeric types as params and returns
  (type (func (param i32) (result i32)))
  (type (func (param i64) (result i64)))
  (type (func (param f32) (result f32)))
  (type (func (param f64) (result f64)))

  (func (export "f") (nop))
)