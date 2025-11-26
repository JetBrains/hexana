(module
  (import "env" "x" (memory 2)) ;; memidx = 0 (assignment continues!)
  (import "env" "y" (memory 1)) ;; memidx = 1
  (memory 1)                    ;; memidx = 2
  (memory 5)                    ;; memidx = 3
  (memory 1)                    ;; memidx = 4

  ;; Check your resolver on these:
  (data 0 (i32.const 0) "M0")
  (data 1 (i32.const 1) "M1")
  (data 2 (i32.const 2) "M2")
  (data 3 (i32.const 3) "M3")
  (data 4 (i32.const 4) "M4")
)