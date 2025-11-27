(module
  (import "env" "f0" (func))     ;; funcidx = 0
  (import "env" "f1" (func))     ;; funcidx = 1
  (func)                         ;; funcidx = 2
  (func)                         ;; funcidx = 3
  (func)                         ;; funcidx = 4

  (func (export "call_all")
    call 0
    call 1
    call 2
    call 3
    call 4
  )
)