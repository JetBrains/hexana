(module
  (type $arr (array (mut i32)))
  (data $d "\01\00\00\00\02\00\00\00\03\00\00\00")
  (elem $e func $dummy)
  (type $farr (array (mut funcref)))

  (func $dummy (nop))

  ;; array.new_data
  (func (export "from_data") (result (ref $arr))
    i32.const 0
    i32.const 3
    array.new_data $arr $d
  )

  ;; array.fill
  (func (export "fill") (param (ref $arr) i32 i32 i32)
    local.get 0
    local.get 1
    local.get 2
    local.get 3
    array.fill $arr
  )

  ;; array.copy
  (func (export "copy") (param (ref $arr) i32 (ref $arr) i32 i32)
    local.get 0
    local.get 1
    local.get 2
    local.get 3
    local.get 4
    array.copy $arr $arr
  )

  ;; array.init_data
  (func (export "init_data") (param (ref $arr) i32 i32 i32)
    local.get 0
    local.get 1
    local.get 2
    local.get 3
    array.init_data $arr $d
  )
)