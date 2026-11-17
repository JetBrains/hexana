;; Imports a fixed-size shared memory (the layout produced by pthread-enabled toolchains) followed by a few function
;; imports. See README.md in this directory for how shared-memory-import.wasm is built.
(module
  (import "i" "memory" (memory 4096 4096 shared))
  (import "env" "thread_spawn" (func $spawn (param i32) (result i32)))
  (import "env" "thread_join" (func (param i32)))
  (import "wasi" "proc_exit" (func (param i32)))
  (func (export "run") (param i32) (result i32)
    local.get 0
    call $spawn))
