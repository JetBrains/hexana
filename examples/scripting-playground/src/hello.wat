(module
  ;; Import: env.log(ptr, len) - log a string from memory
  (import "env" "log" (func $log (param i32 i32)))

  ;; Memory: one page (64 KiB)
  (memory (export "memory") 1)

  ;; Data segment: recognizable greeting string at offset 0
  (data (i32.const 0) "Hello, Hexana!")

  ;; $greet: logs the greeting using the import
  (func $greet (export "greet")
    i32.const 0   ;; ptr
    i32.const 14  ;; len ("Hello, Hexana!" is 14 bytes)
    call $log
  )

  ;; $add: simple arithmetic, exported for scripting demos
  (func $add (export "add") (param $a i32) (param $b i32) (result i32)
    local.get $a
    local.get $b
    i32.add
  )

  ;; $fib: recursive fibonacci for a recognisable call pattern
  (func $fib (export "fib") (param $n i32) (result i32)
    local.get $n
    i32.const 2
    i32.lt_s
    if (result i32)
      local.get $n
    else
      local.get $n
      i32.const 1
      i32.sub
      call $fib
      local.get $n
      i32.const 2
      i32.sub
      call $fib
      i32.add
    end
  )

  ;; $strlen: counts bytes until NUL, demonstrates data segment use
  (func $strlen (export "strlen") (param $ptr i32) (result i32)
    (local $len i32)
    i32.const 0
    local.set $len
    block $break
      loop $loop
        local.get $ptr
        local.get $len
        i32.add
        i32.load8_u
        i32.eqz
        br_if $break
        local.get $len
        i32.const 1
        i32.add
        local.set $len
        br $loop
      end
    end
    local.get $len
  )
)
