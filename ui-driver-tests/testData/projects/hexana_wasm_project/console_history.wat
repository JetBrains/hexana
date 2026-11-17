;; Regenerate with: wat2wasm console_history.wat -o console_history.wasm
(module
  (import "wasi_snapshot_preview1" "fd_write"
    (func $fd_write (param i32 i32 i32 i32) (result i32)))
  (memory (export "memory") 1)
  (data (i32.const 32) "console-history-start\0a")
  (data (i32.const 64) "console-history-line\0a")
  (data (i32.const 96) "console-history-end\0a")
  (func $print (param $address i32) (param $length i32)
    (i32.store (i32.const 0) (local.get $address))
    (i32.store (i32.const 4) (local.get $length))
    (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8))))
  (func (export "_start") (local $remaining i32)
    (call $print (i32.const 32) (i32.const 22))
    (local.set $remaining (i32.const 256))
    (loop $lines
      (call $print (i32.const 64) (i32.const 21))
      (local.set $remaining (i32.sub (local.get $remaining) (i32.const 1)))
      (br_if $lines (local.get $remaining)))
    (call $print (i32.const 96) (i32.const 20))))
