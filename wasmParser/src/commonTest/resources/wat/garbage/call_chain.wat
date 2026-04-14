(module
  ;; Live chain: entry -> a -> b
  (func $entry (export "entry")
    (call $a)
  )
  (func $a
    (call $b)
  )
  (func $b
    (nop)
  )

  ;; Dead chain: x -> y (neither reachable from any export)
  (func $x
    (call $y)
  )
  (func $y
    (nop)
  )

  ;; Isolated dead function
  (func $isolated
    (nop)
  )
)