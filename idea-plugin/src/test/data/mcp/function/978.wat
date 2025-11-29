(module
  ;; ---- type section ----
  ;; type 0: unused dummy
  (type (;0;) (func))
  ;; type 1: (param i32 i32 i32) (result i32)  for func 1
  (type (;1;) (func (param i32 i32 i32) (result i32)))
  ;; type 2: (param i32 i32) (result i32) for func 0
  (type (;2;) (func (param i32 i32) (result i32)))

  ;; ---- memory ----
  (memory (;0;) 1)

  ;; ---- functions ----
  ;; func 0: your exact function
  (func (;0;) (type 2) (param i32 i32) (result i32)
    local.get 1
    local.get 0
    i32.load offset=60
    local.tee 1
    local.get 0
    i32.const -64
    i32.sub
    i32.load
    local.get 1
    i32.sub
    call 1
    drop
    ;; to satisfy (result i32) in type 2, push dummy value
    i32.const 0
  )

  ;; func 1: callee of `call 1` – consumes 3 i32s and returns i32
  ;; stack at call site: [old_local1, new_local1, (mem2 - new_local1)]
  ;; we just return the third argument
  (func (;1;) (type 1) (param i32 i32 i32) (result i32)
    local.get 2
  )

  ;; optional exports so you can play with it
  (export "f0" (func 0))
  (export "f1" (func 1))
  (export "memory" (memory 0))
)