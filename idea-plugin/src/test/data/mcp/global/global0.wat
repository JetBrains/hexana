(module
  (import "env" "g0" (global i32))      ;; globalidx = 0
  (global i32 (i32.const 10))           ;; globalidx = 1
  (global (mut i64) (i64.const 20))     ;; globalidx = 2

  (func (export "get_all")
    global.get 0
    global.get 1
    global.get 2
  )

  (func (export "set_mut")
    i64.const 777
    global.set 2                      ;; only this one is mutable
  )
)