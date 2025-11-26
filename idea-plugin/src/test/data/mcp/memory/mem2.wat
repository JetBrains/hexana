(module
  (import "env" "memA" (memory 1)) ;; memidx = 0
  (memory 1)                      ;; memidx = 1
  (memory 2)                      ;; memidx = 2

  (data 0 (i32.const 4) "AAA")    ;; to imported memory
  (data 1 (i32.const 8) "BBB")    ;; to local memory #1
  (data 2 (i32.const 12) "CCC")   ;; to local memory #2
)