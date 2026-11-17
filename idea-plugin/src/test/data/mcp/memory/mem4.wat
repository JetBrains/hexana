(module
  (memory 1)        ;; memidx = 0
  (memory 1)        ;; memidx = 1

  (data "\AA\BB\CC" )

  (func (export "init")
    ;; copy -> memory 0
    i32.const 0
    i32.const 0
    i32.const 0
    i32.const 3
    memory.init 0

    ;; copy -> memory 1
    i32.const 10
    i32.const 0
    i32.const 0
    i32.const 3
    memory.init 1
  )
)