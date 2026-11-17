(module
  ;; any.convert_extern
  (func (export "internalize") (param externref) (result anyref)
    local.get 0
    any.convert_extern
  )

  ;; extern.convert_any
  (func (export "externalize") (param anyref) (result externref)
    local.get 0
    extern.convert_any
  )
)