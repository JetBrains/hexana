(module
  ;; ref.i31
  (func (export "make_i31") (param i32) (result i31ref)
    local.get 0
    ref.i31
  )

  ;; i31.get_s
  (func (export "get_i31_s") (param i31ref) (result i32)
    local.get 0
    i31.get_s
  )

  ;; i31.get_u
  (func (export "get_i31_u") (param i31ref) (result i32)
    local.get 0
    i31.get_u
  )
)