(module
  (memory 1)

  ;; passive data segment #0 (not written automatically)
  (data passive "Some-Chunk")

  ;; function to copy into memory
  (func (export "load")
    i32.const 100      ;; destination
    i32.const 0        ;; segment index
    i32.const 0        ;; source offset
    i32.const 10       ;; length
    memory.init 0      ;; copy 10 bytes from passive[0] to mem[100]
    data.drop 0
  )
)