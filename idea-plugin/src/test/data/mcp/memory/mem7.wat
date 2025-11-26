(module
  ;; imports
  (import "A" "import0" (memory 1))   ;; memidx = 0
  (import "A" "import1" (memory 2))   ;; memidx = 1

  ;; locals
  (memory 1)                          ;; memidx = 2
  (memory i64 1)                      ;; memidx = 3
  (memory 3 3)                        ;; memidx = 4

  ;; active
  (data 0 (i32.const 10) "I0")
  (data 1 (i32.const 20) "I1")
  (data 2 (i32.const 30) "L2")
  (data 3 (i64.const 40) "L3:64")
  (data 4 (i32.const 50) "L4")

  ;; passive segments
  (data "PASS0")
  (data "PASS1")

  (func (export "init")
    ;; memory.init 0 (import0)
    i32.const 100
    i32.const 0
    i32.const 0
    i32.const 5
    memory.init 0

    ;; memory.init 3 (local memory64)
    i64.const 200
    i32.const 1
    i32.const 0
    i32.const 5
    memory.init 3
  )
)