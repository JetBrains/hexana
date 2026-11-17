(module
  (memory 1)                     ;; memory32, memidx = 0
  (memory i64 1)                ;; memory64, memidx = 1
  (memory 2)                    ;; memory32, memidx = 2

  (data (i32.const 0) "32BIT")
  (data (i64.const 128) "64BIT")   ;; note offset must be i64.const
  (data (i32.const 256) "MIXED")
)