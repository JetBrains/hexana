(module
  (type $arr (array (mut i32)))

  ;; array.new
  (func (export "new_arr") (param i32 i32) (result (ref $arr))
    local.get 0
    local.get 1
    array.new $arr
  )

  ;; array.new_default
  (func (export "default_arr") (param i32) (result (ref $arr))
    local.get 0
    array.new_default $arr
  )

  ;; array.new_fixed
  (func (export "fixed_arr") (result (ref $arr))
    i32.const 10
    i32.const 20
    i32.const 30
    array.new_fixed $arr 3
  )

  ;; array.get and array.set
  (func (export "get_elem") (param (ref null $arr) i32) (result i32)
    local.get 0
    local.get 1
    array.get $arr
  )

  (func (export "set_elem") (param (ref null $arr) i32 i32)
    local.get 0
    local.get 1
    local.get 2
    array.set $arr
  )

  ;; array.len
  (func (export "arr_len") (param (ref null $arr)) (result i32)
    local.get 0
    array.len
  )
)