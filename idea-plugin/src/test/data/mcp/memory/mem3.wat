(module
  (import "sys" "m0" (memory 1)) ;; memidx = 0
  (import "sys" "m1" (memory 2)) ;; memidx = 1
  (memory 3)                    ;; memidx = 2

  (data 0 (i32.const 0) "\01")
  (data 1 (i32.const 1) "\02")
  (data 2 (i32.const 2) "\03")
)