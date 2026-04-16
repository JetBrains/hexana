(module
  (func (export "sat_s") (param f32) (result i64)
    local.get 0
    i64.trunc_sat_f32_s
  )
  (func (export "sat_u") (param f32) (result i64)
    local.get 0
    i64.trunc_sat_f32_u
  )
)