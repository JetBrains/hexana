(module
  (memory $m 1 1 shared)

  (func $waitNotify
    (memory.atomic.notify (i32.const 0) (i32.const 1))
    (drop)
    (memory.atomic.wait32 (i32.const 0) (i32.const 0) (i64.const -1))
    (drop)
    (memory.atomic.wait64 (i32.const 0) (i64.const 0) (i64.const -1))
    (drop)
    (atomic.fence)
  )

  (func $loadStore
    (i32.atomic.load (i32.const 0))
    (drop)
    (i32.atomic.load8_u offset=4 align=1 (i32.const 0))
    (drop)
    (i64.atomic.load32_u (i32.const 0))
    (drop)
    (i32.atomic.store (i32.const 0) (i32.const 1))
    (i64.atomic.store16 offset=8 (i32.const 0) (i64.const 1))
  )

  (func $rmw
    (i32.atomic.rmw.add (i32.const 0) (i32.const 1))
    (drop)
    (i32.atomic.rmw8.xchg_u (i32.const 0) (i32.const 1))
    (drop)
    (i64.atomic.rmw32.cmpxchg_u (i32.const 0) (i64.const 1) (i64.const 2))
    (drop)
  )

  (func $unfolded
    i32.const 0
    i32.atomic.load
    drop
    i32.const 0
    i32.const 1
    i32.atomic.rmw.or
    drop
    atomic.fence
  )
)
