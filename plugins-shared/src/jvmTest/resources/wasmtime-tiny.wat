(module
  (memory (export "memory") 1)
  (func $add (export "add") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.add)
  (func $sum_to (export "sum_to") (param i32) (result i32)
    (local i32 i32)
    block
      loop
        local.get 1
        local.get 0
        i32.ge_u
        br_if 1
        local.get 2
        local.get 1
        i32.add
        local.set 2
        local.get 1
        i32.const 1
        i32.add
        local.set 1
        br 0
      end
    end
    local.get 2)
  (func $store_load (export "store_load") (param i32 i32) (result i32)
    local.get 0
    local.get 1
    i32.store
    local.get 0
    i32.load))
