(module
  (type $point (struct (field $x (mut i32)) (field $y (mut i32))))

  ;; struct.new
  (func (export "new_point") (param i32 i32) (result (ref $point))
    local.get 0
    local.get 1
    struct.new $point
  )

  ;; struct.new_default
  (func (export "default_point") (result (ref $point))
    struct.new_default $point
  )

  ;; struct.get and struct.set
  (func (export "get_x") (param (ref null $point)) (result i32)
    local.get 0
    struct.get $point $x
  )

  (func (export "set_x") (param (ref null $point) i32)
    local.get 0
    local.get 1
    struct.set $point $x
  )
)