(module
  ;; Import memory from the host ("js", "mem")
  (import "js" "mem" (memory 1))

  ;; Writes ASCII "Hello" at offset 0 of imported memory
  (data (i32.const 0) "Hello")
)