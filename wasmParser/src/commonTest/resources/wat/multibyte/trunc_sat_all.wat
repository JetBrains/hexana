(module
  ;; All 8 saturating truncation instructions in a single function
  (func (export "all_trunc_sat") (param f32 f64) (result i32)
    ;; i32.trunc_sat_f32_s (0xFC 0x00)
    local.get 0
    i32.trunc_sat_f32_s
    drop

    ;; i32.trunc_sat_f32_u (0xFC 0x01)
    local.get 0
    i32.trunc_sat_f32_u
    drop

    ;; i32.trunc_sat_f64_s (0xFC 0x02)
    local.get 1
    i32.trunc_sat_f64_s
    drop

    ;; i32.trunc_sat_f64_u (0xFC 0x03)
    local.get 1
    i32.trunc_sat_f64_u
    drop

    ;; i64.trunc_sat_f32_s (0xFC 0x04)
    local.get 0
    i64.trunc_sat_f32_s
    drop

    ;; i64.trunc_sat_f32_u (0xFC 0x05)
    local.get 0
    i64.trunc_sat_f32_u
    drop

    ;; i64.trunc_sat_f64_s (0xFC 0x06)
    local.get 1
    i64.trunc_sat_f64_s
    drop

    ;; i64.trunc_sat_f64_u (0xFC 0x07)
    local.get 1
    i64.trunc_sat_f64_u

    i32.wrap_i64
  )
)