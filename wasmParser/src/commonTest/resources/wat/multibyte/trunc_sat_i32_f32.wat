(module
  (func (export "sat_s") (param f32) (result i32)
    local.get 0
    i32.trunc_sat_f32_s
  )
  (func (export "sat_u") (param f32) (result i32)
    local.get 0
    i32.trunc_sat_f32_u
  )
)