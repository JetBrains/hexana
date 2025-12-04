(module
  (func $f
    (local $var i32)

    (local.get $var)
    local.get $var

    (local.set $var (i32.const 1))
    local.set $var

    (local.tee $var (i32.const 1))
    local.tee $var

    (call $1)
    call $1

    (global.get $1)
    global.get $1

    (global.set $1 (i32.const 1))
    global.set $1
  )
)
