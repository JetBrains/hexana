(module
  (func (export "sat_s") (param f64) (result i32)
    local.get 0
    i32.trunc_sat_f64_s
  )
  (func (export "sat_u") (param f64) (result i32)
    local.get 0
    i32.trunc_sat_f64_u
  )
)