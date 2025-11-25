(module
  (memory 1)
  ;; writes [0x01 0x02 0x03] at memory offset 16
  (data (i32.const 16) "\01\02\03")
)