(module
  (memory 2)

  (data passive "ConfigBlock")
  (data passive "Shader:XYZ")
  (data passive "\01\02\03\04")

  (func (export "init")
    ;; copy segment 0 into memory[0]
    i32.const 0
    i32.const 0
    i32.const 0
    i32.const 11
    memory.init 0

    ;; copy segment 1 into memory[128]
    i32.const 128
    i32.const 1
    i32.const 0
    i32.const 10
    memory.init 1
  )
)