(module
  (memory 1)        ;; memidx = 0
  (memory 2)        ;; memidx = 1

  ;; Write to memory #0
  (data 0 (i32.const 0) "A")

  ;; Write to memory #1
  (data 1 (i32.const 10) "BB")
)