(module
  (tag $e (param i32))

  (func $folded
    (try $t (result i32)
      (do
        (i32.const 0))
      (catch $e
        (drop))
      (catch_all
        (i32.const 1))
    )
    (drop)
  )

  (func $unfolded
    try $t2
      nop
    catch $e
      drop
    catch_all
      nop
    end
  )

  (func $rethrowing
    try $t3
      nop
    catch_all
      rethrow $t3
    end
  )

  ;; `delegate` terminates the try in place of `end`, so the enclosing block must still be
  ;; closed by its own `end`.
  (func $delegating
    block $outer
      try $inner
        nop
      delegate 0
      nop
    end
  )

  (func $delegateByLabel
    try $t4
      nop
    delegate $t4
  )
)
