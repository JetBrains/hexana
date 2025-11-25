(module
  (memory 1)

  ;; first block
  (data (i32.const 0) "Header")

  ;; second block
  (data (i32.const 64) "Payload-1")

  ;; third block
  (data (i32.const 128) "Payload-2")
)