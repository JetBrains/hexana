(module
  ;; Import at funcidx = 0
  (import "env" "print" (func $print (param i32)))

  ;; Declared function f1 at funcidx = 1
  (func $f1 (local i32)
    local.get 0
    i32.const 42
    call $print ;; tests correct funcidx resolution
  )

  (export "f1" (func $f1))
)