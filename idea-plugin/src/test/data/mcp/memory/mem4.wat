(module
  (memory 1 2)   ;; memory #0
  (memory 3 3)   ;; memory #1, fixed size

  ;; write into memory #1 at address 32
  (data (i32.const 32) "SECOND-MEMORY")
)